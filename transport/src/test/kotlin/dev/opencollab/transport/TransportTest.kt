package dev.opencollab.transport

import dev.opencollab.core.files.MirrorWorkspace
import dev.opencollab.core.files.ProjectFiles
import dev.opencollab.core.files.SharingRules
import dev.opencollab.core.ot.TextOperation
import dev.opencollab.core.protocol.Role
import dev.opencollab.core.session.ClientHandler
import dev.opencollab.core.session.ClientState
import dev.opencollab.core.session.CollabClient
import dev.opencollab.core.session.DocumentSource
import dev.opencollab.core.session.HostConfig
import dev.opencollab.core.session.HostSession
import dev.opencollab.core.session.ReconnectPolicy
import dev.opencollab.core.transport.Connection
import dev.opencollab.core.transport.ConnectionListener
import dev.opencollab.core.transport.InMemoryTransport
import dev.opencollab.core.util.SerialExecutor
import dev.opencollab.core.util.SystemScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

fun eventually(timeoutMs: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(10)
    }
    throw AssertionError("Condition not met within ${timeoutMs}ms")
}

class Collector : ConnectionListener {
    val messages = CopyOnWriteArrayList<String>()
    @Volatile var closed: String? = null
    override fun onMessage(text: String) { messages += text }
    override fun onClosed(reason: String) { closed = reason }
}

/** A minimal editor model on its own "EDT" for end-to-end transport tests. */
class TextIde(name: String) : ClientHandler {
    val edt = SerialExecutor("edt-$name")
    val texts = HashMap<String, String>()
    lateinit var client: CollabClient
    override fun applyRemoteEdit(path: String, op: TextOperation) { texts[path] = op.apply(texts.getValue(path)) }
    fun <T> onEdt(block: () -> T): T {
        val f = CompletableFuture<T>(); edt.execute { f.complete(block()) }; return f.get(10, TimeUnit.SECONDS)
    }
    fun type(path: String, offset: Int, s: String) = onEdt {
        val t = texts.getValue(path)
        val op = TextOperation.replace(t.length, offset, 0, s)
        texts[path] = op.apply(t)
        client.localEdit(path, op)
    }
}

class TransportTest {
    @TempDir lateinit var tmp: Path

    @Test
    fun `ephemeral identity has a stable SHA-256 fingerprint`() {
        val id = TlsIdentity.ephemeral()
        assertEquals(64, TlsIdentity.normalizeFingerprint(id.fingerprint).length)
        assertEquals(id.fingerprint, TlsIdentity.fingerprintOf(id.certificate))
    }

    @Test
    fun `direct TLS connection delivers messages both ways`() {
        val identity = TlsIdentity.ephemeral()
        val server = DirectHostTransport("127.0.0.1", 0, identity)
        val serverSide = CompletableFuture<Connection>()
        server.start { serverSide.complete(it) }
        try {
            val uri = URI("wss://127.0.0.1:${server.boundPort}")
            val client = WebSocketConnector(uri, TrustMode.Pinned(identity.fingerprint)).connect()
            val clientMsgs = Collector().also(client::setListener)
            val host = serverSide.get(5, TimeUnit.SECONDS)
            val hostMsgs = Collector().also(host::setListener)
            client.send("hello host")
            host.send("hello guest")
            val big = "x".repeat(2_000_000)
            client.send(big)
            eventually { hostMsgs.messages.size == 2 && clientMsgs.messages.size == 1 }
            assertEquals("hello host", hostMsgs.messages[0])
            assertEquals(big, hostMsgs.messages[1])
            client.close("bye")
            eventually { hostMsgs.closed != null }
        } finally {
            server.close()
        }
    }

    @Test
    fun `client refuses a host whose certificate does not match the invitation`() {
        val real = TlsIdentity.ephemeral()
        val impostor = TlsIdentity.ephemeral()
        val server = DirectHostTransport("127.0.0.1", 0, impostor)
        server.start { }
        try {
            val ex = assertThrows(IOException::class.java) {
                WebSocketConnector(URI("wss://127.0.0.1:${server.boundPort}"), TrustMode.Pinned(real.fingerprint)).connect()
            }
            assertTrue(ex.message!!.contains("Cannot connect"), ex.message)
        } finally {
            server.close()
        }
    }

    @Test
    fun `system trust rejects self-signed certificates and plain ws is not allowed`() {
        val identity = TlsIdentity.ephemeral()
        val server = DirectHostTransport("127.0.0.1", 0, identity)
        server.start { }
        try {
            assertThrows(IOException::class.java) {
                WebSocketConnector(URI("wss://127.0.0.1:${server.boundPort}"), TrustMode.SystemCertificateAuthorities).connect()
            }
            assertThrows(IllegalArgumentException::class.java) {
                WebSocketConnector(URI("ws://127.0.0.1:${server.boundPort}"), TrustMode.Pinned(identity.fingerprint))
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `TLS tunnel over an untrusted channel authenticates the host and hides plaintext`() {
        val identity = TlsIdentity.ephemeral()
        val (guestEnd, hostEnd) = InMemoryTransport.pair("guest", "host")
        // Record what an observer in the middle (the relay) would see.
        val hostSide = CompletableFuture<Connection>()
        Thread { hostSide.complete(TlsTunnel.server(hostEnd, identity)) }.start()
        val client = TlsTunnel.client(guestEnd, TrustMode.Pinned(identity.fingerprint))
        val host = hostSide.get(5, TimeUnit.SECONDS)
        val hostMsgs = Collector().also(host::setListener)
        val clientMsgs = Collector().also(client::setListener)
        val secret = "fun secretAlgorithm() = 42"
        repeat(20) { client.send("$secret #$it") }
        host.send("ack")
        eventually { hostMsgs.messages.size == 20 && clientMsgs.messages.size == 1 }
        assertEquals("$secret #19", hostMsgs.messages.last())

        val (g2, h2) = InMemoryTransport.pair("guest2", "host2")
        Thread { runCatching { TlsTunnel.server(h2, TlsIdentity.ephemeral()) } }.start()
        assertThrows(IOException::class.java) { TlsTunnel.client(g2, TrustMode.Pinned(identity.fingerprint), timeoutMillis = 5_000) }
    }

    @Test
    fun `invitation round trip and validation`() {
        val fp = TlsIdentity.ephemeral().fingerprint
        val invite = Invite(project = "demo", role = Role.EDITOR, token = "tok", fingerprint = fp, direct = listOf("wss://10.0.0.2:47800"))
        assertEquals(invite, Invite.decode(invite.encode()))
        assertEquals(invite, Invite.decode("  " + invite.encode().chunked(10).joinToString("\n") + " "))
        assertThrows(IllegalArgumentException::class.java) { Invite.decode("hello") }
        assertThrows(IllegalArgumentException::class.java) { Invite.decode(invite.copy(direct = listOf("ws://x:1")).encode()) }
        assertThrows(IllegalArgumentException::class.java) { Invite.decode(invite.copy(role = Role.HOST).encode()) }
        assertThrows(IllegalArgumentException::class.java) { Invite.decode(invite.copy(fingerprint = "AB").encode()) }
    }

    @Test
    fun `full session over direct TLS - host and guest co-edit and converge`() {
        val hostDir = Files.createDirectories(tmp.resolve("host"))
        Files.writeString(hostDir.resolve("Main.kt"), "fun main() {}\n")
        val guestDir = Files.createDirectories(tmp.resolve("guest"))
        val scheduler = SystemScheduler()
        val identity = TlsIdentity.ephemeral()
        val transport = DirectHostTransport("127.0.0.1", 0, identity)
        val hostIde = TextIde("host")
        val files = ProjectFiles(hostDir, SharingRules())
        val session = HostSession(HostConfig("demo"), files, DocumentSource { path ->
            val f = CompletableFuture<String?>()
            hostIde.edt.execute {
                val t = hostIde.texts[path] ?: files.read(path)?.toString(Charsets.UTF_8)
                if (t != null) { hostIde.texts[path] = t; hostIde.client.preRegisterDocument(path, t) }
                f.complete(t)
            }
            f.get(10, TimeUnit.SECONDS)
        }, scheduler)
        transport.start(session::accept)
        val memory = InMemoryTransport.Server().also { it.start(session::accept) }
        hostIde.client = CollabClient(memory.connector("bridge"), "Host", session.config.hostToken, hostIde, hostIde.edt, scheduler, null)
        hostIde.client.start()

        val invite = Invite(project = "demo", role = Role.EDITOR, token = session.config.editorToken,
            fingerprint = identity.fingerprint, direct = transport.endpoints)
        val decoded = Invite.decode(invite.encode())
        val guest = TextIde("guest")
        guest.client = CollabClient(decoded.connector(), "Guest", decoded.token, guest, guest.edt, scheduler,
            MirrorWorkspace(guestDir), ReconnectPolicy(maxAttempts = 3))
        guest.client.start()
        try {
            eventually { guest.client.state == ClientState.CONNECTED }
            assertEquals("fun main() {}\n", Files.readString(guestDir.resolve("Main.kt")))
            guest.onEdt { guest.texts["Main.kt"] = "fun main() {}\n"; guest.client.openDocument("Main.kt", "fun main() {}\n") }
            eventually { guest.onEdt { guest.client.isDocumentSynced("Main.kt") } }
            repeat(30) {
                guest.type("Main.kt", 12, "g")
                hostIde.type("Main.kt", 0, "h")
            }
            eventually {
                val t = session.documentText("Main.kt")
                t != null && t == guest.onEdt { guest.texts["Main.kt"] } && t == hostIde.onEdt { hostIde.texts["Main.kt"] }
            }
            val text = session.documentText("Main.kt")!!
            assertEquals(30, text.count { it == 'g' })
            assertEquals(30, text.count { it == 'h' })
            assertTrue(text.endsWith("}\n"), text)
        } finally {
            guest.client.close()
            hostIde.client.close()
            session.close()
            transport.close()
            scheduler.close()
        }
    }
}
