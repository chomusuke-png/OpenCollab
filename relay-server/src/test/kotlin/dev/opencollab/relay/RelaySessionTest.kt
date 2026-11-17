package dev.opencollab.relay

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
import dev.opencollab.core.util.SerialExecutor
import dev.opencollab.core.util.SystemScheduler
import dev.opencollab.transport.Invite
import dev.opencollab.transport.RelayHostTransport
import dev.opencollab.transport.TlsIdentity
import dev.opencollab.transport.TrustMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Full session (snapshot + co-editing) where the guest can only reach the host through the relay. */
class RelaySessionTest {
    @TempDir lateinit var tmp: Path

    private class Ide : ClientHandler {
        val edt = SerialExecutor("edt")
        val texts = ConcurrentHashMap<String, String>()
        lateinit var client: CollabClient
        override fun applyRemoteEdit(path: String, op: TextOperation) { texts[path] = op.apply(texts.getValue(path)) }
        fun <T> onEdt(block: () -> T): T { val f = CompletableFuture<T>(); edt.execute { f.complete(block()) }; return f.get(10, TimeUnit.SECONDS) }
    }

    private fun eventually(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(10) }
        throw AssertionError("Condition not met")
    }

    @Test
    fun `guest joins through the relay and co-edits with end-to-end TLS`() {
        val relayIdentity = TlsIdentity.ephemeral("relay")
        val relayLog = CopyOnWriteArrayList<String>()
        val relay = RelayServer(RelayConfig(bind = "127.0.0.1", port = 0, tls = relayIdentity.serverContext(), hostSecret = "0123456789abcdef-secret")) { relayLog += it }
        relay.start()
        val scheduler = SystemScheduler()
        val hostDir = Files.createDirectories(tmp.resolve("host"))
        Files.writeString(hostDir.resolve("Notes.md"), "# Notes\n")
        Files.writeString(hostDir.resolve(".env"), "TOKEN=abc\n")
        val files = ProjectFiles(hostDir, SharingRules())
        val session = HostSession(HostConfig("relayed"), files, DocumentSource { files.read(it)?.toString(Charsets.UTF_8) }, scheduler)
        val hostIdentity = TlsIdentity.ephemeral()
        val relayUri = URI("wss://127.0.0.1:${relay.port}")
        val transport = RelayHostTransport(relayUri, "0123456789abcdef-secret", TrustMode.PinnedRelay(relayIdentity.fingerprint), hostIdentity)
        transport.start(session::accept)
        try {
            val invite = Invite(
                project = "relayed", role = Role.EDITOR, token = session.config.editorToken, fingerprint = hostIdentity.fingerprint,
                relay = relayUri.toString(), room = transport.room, relayFingerprint = relayIdentity.fingerprint,
            )
            val decoded = Invite.decode(invite.encode())
            val guests = (1..2).map { n ->
                Ide().also { ide ->
                    ide.client = CollabClient(decoded.connector(), "guest$n", decoded.token, ide, ide.edt, scheduler,
                        MirrorWorkspace(Files.createDirectories(tmp.resolve("g$n"))), ReconnectPolicy(maxAttempts = 2))
                    ide.client.start()
                }
            }
            guests.forEach { g -> eventually { g.client.state == ClientState.CONNECTED } }
            assertEquals("# Notes\n", Files.readString(tmp.resolve("g1/Notes.md")))
            assertFalse(Files.exists(tmp.resolve("g1/.env")))
            guests.forEach { g -> g.onEdt { g.texts["Notes.md"] = "# Notes\n"; g.client.openDocument("Notes.md", "# Notes\n") } }
            guests.forEach { g -> eventually { g.onEdt { g.client.isDocumentSynced("Notes.md") } } }
            repeat(25) { i ->
                guests.forEachIndexed { n, g ->
                    g.onEdt {
                        val t = g.texts.getValue("Notes.md")
                        val op = TextOperation.replace(t.length, t.length, 0, "g$n-$i\n")
                        g.texts["Notes.md"] = op.apply(t)
                        g.client.localEdit("Notes.md", op)
                    }
                }
            }
            eventually {
                val t = session.documentText("Notes.md")
                t != null && guests.all { it.texts["Notes.md"] == t }
            }
            // "# Notes" + 2 guests x 25 lines (+ the empty string after the final newline)
            assertEquals(52, session.documentText("Notes.md")!!.lines().size)
            assertFalse(relayLog.any { it.contains("Notes") || it.contains("g0-") })
            guests.forEach { it.client.close() }
        } finally {
            session.close()
            transport.close()
            relay.stop()
            scheduler.close()
        }
    }
}
