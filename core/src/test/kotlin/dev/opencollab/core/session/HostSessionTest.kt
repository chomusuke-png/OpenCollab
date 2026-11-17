package dev.opencollab.core.session

import dev.opencollab.core.files.Hashes
import dev.opencollab.core.protocol.FileMutation
import dev.opencollab.core.protocol.FileOpKind
import dev.opencollab.core.protocol.Message
import dev.opencollab.core.protocol.MessageCodec
import dev.opencollab.core.protocol.PROTOCOL_VERSION
import dev.opencollab.core.protocol.Role
import dev.opencollab.core.protocol.TextRange
import dev.opencollab.core.session.Harness.Companion.awaitState
import dev.opencollab.core.session.Harness.Companion.eventually
import dev.opencollab.core.session.Harness.Companion.write
import dev.opencollab.core.transport.ConnectionListener
import dev.opencollab.core.transport.InMemoryTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

class HostSessionTest {
    @TempDir lateinit var tmp: Path
    private val host get() = tmp.resolve("host")
    private fun guestDir(name: String) = tmp.resolve(name).also { Files.createDirectories(it) }

    private fun sampleProject() {
        write(host, "src/Main.kt", "fun main() {\n    println(\"hello\")\n}\n")
        write(host, "README.md", "# Demo\n")
        write(host, ".env", "API_KEY=supersecret\n")
        write(host, "config/credentials.json", "{\"token\":\"x\"}")
        write(host, "build/out.class", "binary")
        write(host, ".git/config", "[core]")
        write(host, ".idea/workspace.xml", "<local/>")
        write(host, ".idea/misc.xml", "<project/>")
        write(host, "keys/server.pem", "-----BEGIN PRIVATE KEY-----")
    }

    @Test
    fun `host measures every participant's ping and reports the session age`() {
        sampleProject()
        Harness(host, config = HostConfig("demo", pingIntervalMillis = 50)).use { h ->
            Thread.sleep(300) // the session has been running a while before the guest joins
            val g = h.guest("alice", guestDir("alice"))
            awaitState(g, ClientState.CONNECTED)
            assertTrue(g.welcome!!.sessionAgeMillis >= 300, "session age ${g.welcome!!.sessionAgeMillis}")
            val alice = g.client.participantId
            val hostId = h.hostIde.client.participantId
            // Everyone, host included, receives every participant's round-trip time.
            eventually { alice in g.latencies && hostId in g.latencies && alice in h.hostIde.latencies }
            assertTrue(g.latencies.values.all { it in 0..5_000 }, "latencies ${g.latencies}")
        }
    }

    @Test
    fun `snapshot shares project files but never secrets or local state`() {
        sampleProject()
        Harness(host).use { h ->
            val g = h.guest("alice", guestDir("alice"))
            awaitState(g, ClientState.CONNECTED)
            val mirror = tmp.resolve("alice")
            assertEquals(Files.readString(host.resolve("src/Main.kt")), Files.readString(mirror.resolve("src/Main.kt")))
            assertTrue(Files.exists(mirror.resolve("README.md")))
            assertTrue(Files.exists(mirror.resolve(".idea/misc.xml")))
            for (secret in listOf(".env", "config/credentials.json", "build/out.class", ".git/config", ".idea/workspace.xml", "keys/server.pem")) {
                assertFalse(Files.exists(mirror.resolve(secret)), "$secret must not be shared")
            }
            assertTrue(g.client.mirror!!.isMirror())
        }
    }

    @Test
    fun `two guests and host edit the same document concurrently and converge`() {
        sampleProject()
        Harness(host).use { h ->
            val a = h.guest("alice", guestDir("alice"))
            val b = h.guest("bob", guestDir("bob"))
            awaitState(a, ClientState.CONNECTED)
            awaitState(b, ClientState.CONNECTED)
            val path = "src/Main.kt"
            val disk = Files.readString(host.resolve(path))
            a.open(path, disk)
            b.open(path, disk)
            eventually { a.onEdt { a.client.isDocumentSynced(path) } && b.onEdt { b.client.isDocumentSynced(path) } }

            val rnd = Random(42)
            val participants = listOf(a, b, h.hostIde)
            val threads = participants.map { ide ->
                Thread {
                    repeat(60) { i ->
                        val len = ide.text(path)!!.length
                        if (rnd.nextInt(4) == 0 && len > 2) {
                            ide.type(path, rnd.nextInt(len - 1), "", deleteLength = 1)
                        } else {
                            ide.type(path, rnd.nextInt(len + 1), "${ide.client.participantId.take(1)}$i")
                        }
                        if (i % 7 == 0) Thread.sleep(rnd.nextLong(3))
                    }
                }
            }
            threads.forEach(Thread::start)
            threads.forEach(Thread::join)
            eventually(15_000) {
                val t = h.session.documentText(path)
                t != null && participants.all { it.text(path) == t }
            }
            // Edits typed while an operation is in flight are composed, so revisions <= edits.
            assertTrue(h.session.documentRevision(path)!! in 3..180)
        }
    }

    @Test
    fun `viewer edits are rejected by the host and rolled back locally`() {
        sampleProject()
        Harness(host).use { h ->
            val v = h.guest("viewer", guestDir("viewer"), token = h.session.config.viewerToken)
            awaitState(v, ClientState.CONNECTED)
            assertEquals(Role.VIEWER, v.client.role)
            val path = "README.md"
            v.open(path, "# Demo\n")
            eventually { v.onEdt { v.client.isDocumentSynced(path) } }
            v.type(path, 0, "HACKED ")
            eventually { v.text(path) == "# Demo\n" }
            assertEquals("# Demo\n", h.session.documentText(path))

            v.client.requestFileChange(FileMutation(FileOpKind.CREATE_FILE, "evil.txt", contentBase64 = MessageCodec.encodeBase64("x".toByteArray())))
            eventually { v.failedFileRequests.isNotEmpty() }
            assertFalse(Files.exists(host.resolve("evil.txt")))
        }
    }

    @Test
    fun `host can promote a viewer and guests cannot change roles`() {
        sampleProject()
        Harness(host).use { h ->
            val v = h.guest("viewer", guestDir("viewer"), token = h.session.config.viewerToken)
            val e = h.guest("editor", guestDir("editor"))
            awaitState(v, ClientState.CONNECTED)
            awaitState(e, ClientState.CONNECTED)
            // An editor trying to promote the viewer is refused by the host.
            e.client.setRole(v.client.participantId, Role.EDITOR)
            Thread.sleep(300)
            assertEquals(Role.VIEWER, v.client.role)
            // The host's own bridge may.
            h.hostIde.client.setRole(v.client.participantId, Role.EDITOR)
            eventually { v.client.role == Role.EDITOR }
            // Nobody can be made HOST.
            h.session.setRole(v.client.participantId, Role.HOST)
            Thread.sleep(200)
            assertEquals(Role.EDITOR, v.client.role)
        }
    }

    @Test
    fun `guest file operations are validated against traversal, exclusions and read-only paths`() {
        sampleProject()
        Harness(host).use { h ->
            val g = h.guest("alice", guestDir("alice"))
            awaitState(g, ClientState.CONNECTED)
            fun create(path: String) = g.client.requestFileChange(
                FileMutation(FileOpKind.CREATE_FILE, path, contentBase64 = MessageCodec.encodeBase64("pwn".toByteArray())),
            )
            create(".env.local")                       // excluded secret
            create(".git/hooks/pre-commit")            // VCS internals
            create("gradlew")                          // guest read-only
            create(".idea/runConfigurations/x.xml")    // guest read-only
            eventually { g.failedFileRequests.size == 4 }
            assertFalse(Files.exists(host.resolve(".git/hooks/pre-commit")))
            assertFalse(Files.exists(host.resolve(".env.local")))
            assertFalse(Files.exists(host.resolve("gradlew")))
            // Deleting a directory that contains unshared secrets is refused.
            g.client.requestFileChange(FileMutation(FileOpKind.DELETE, "config"))
            eventually { g.failedFileRequests.size == 5 }
            assertTrue(Files.exists(host.resolve("config/credentials.json")))

            // A legitimate create/rename/delete is applied on the host.
            create("src/Util.kt")
            eventually { Files.exists(host.resolve("src/Util.kt")) }
            g.client.requestFileChange(FileMutation(FileOpKind.RENAME, "src/Util.kt", newPath = "src/util/Util.kt"))
            eventually { Files.exists(host.resolve("src/util/Util.kt")) && !Files.exists(host.resolve("src/Util.kt")) }
            g.client.requestFileChange(FileMutation(FileOpKind.DELETE, "src/util"))
            eventually { !Files.exists(host.resolve("src/util")) }
        }
    }

    @Test
    fun `raw traversal paths are rejected at the protocol layer`() {
        sampleProject()
        Harness(host).use { h ->
            val (client, server) = InMemoryTransport.pair("attacker", "host<-attacker")
            val received = CopyOnWriteArrayList<Message>()
            client.setListener(object : ConnectionListener {
                override fun onMessage(text: String) { received += MessageCodec.decode(text) }
                override fun onClosed(reason: String) {}
            })
            h.session.accept(server)
            client.send(MessageCodec.encode(Message.Hello(PROTOCOL_VERSION, "mallory", h.session.config.editorToken)))
            eventually { received.any { it is Message.Welcome } }
            for (bad in listOf("../outside.txt", "/etc/passwd", "C:/Windows/x", "src/../../x", "a\\..\\b", "CON", "src/./x", "x/")) {
                val raw = """{"t":"fileRequest","requestId":"r1","mutation":{"kind":"CREATE_FILE","path":${kotlinx.serialization.json.JsonPrimitive(bad)},"contentBase64":"eA=="}}"""
                client.send(raw)
                client.send("""{"t":"openDoc","path":${kotlinx.serialization.json.JsonPrimitive(bad)}}""")
            }
            eventually { received.count { it is Message.Error && it.code == "protocol" } >= 16 }
            assertFalse(Files.exists(tmp.resolve("outside.txt")))
            assertFalse(Files.exists(tmp.resolve("x")))
        }
    }

    @Test
    fun `invalid tokens and wrong protocol versions are refused`() {
        sampleProject()
        Harness(host).use { h ->
            val g = h.guest("eve", guestDir("eve"), token = "not-the-token")
            eventually { g.stateDetails.any { it.contains("refused") } }
            assertEquals(ClientState.DISCONNECTED, g.client.state)
            assertEquals(1, h.session.participants().size) // only the host bridge

            val (client, server) = InMemoryTransport.pair("old", "host<-old")
            val received = CopyOnWriteArrayList<Message>()
            client.setListener(object : ConnectionListener {
                override fun onMessage(text: String) { received += MessageCodec.decode(text) }
                override fun onClosed(reason: String) {}
            })
            h.session.accept(server)
            client.send(MessageCodec.encode(Message.Hello(99, "old", h.session.config.editorToken)))
            eventually { received.any { it is Message.HandshakeRejected } }
        }
    }

    @Test
    fun `messages before authentication are not processed`() {
        sampleProject()
        Harness(host).use { h ->
            val (client, server) = InMemoryTransport.pair("anon", "host<-anon")
            var closed = false
            client.setListener(object : ConnectionListener {
                override fun onMessage(text: String) {}
                override fun onClosed(reason: String) { closed = true }
            })
            h.session.accept(server)
            client.send(MessageCodec.encode(Message.RequestFiles("r", listOf("src/Main.kt"))))
            eventually { closed }
        }
    }

    @Test
    fun `presence is relayed, attributed by the host, and cleared on disconnect`() {
        sampleProject()
        Harness(host).use { h ->
            val a = h.guest("alice", guestDir("alice"))
            val b = h.guest("bob", guestDir("bob"))
            awaitState(a, ClientState.CONNECTED)
            awaitState(b, ClientState.CONNECTED)
            repeat(50) { i -> a.client.updatePresence("src/Main.kt", i, listOf(TextRange(0, i))) }
            eventually { b.presence[a.client.participantId]?.caret == 49 }
            assertEquals(a.client.participantId, b.presence[a.client.participantId]!!.participantId)
            eventually { h.hostIde.presence[a.client.participantId]?.caret == 49 }
            a.client.close()
            eventually { b.presence[a.client.participantId] == null }
            eventually { h.session.participants().size == 2 }
        }
    }

    @Test
    fun `host file changes propagate incrementally to guests`() {
        sampleProject()
        Harness(host).use { h ->
            val g = h.guest("alice", guestDir("alice"))
            awaitState(g, ClientState.CONNECTED)
            val mirror = tmp.resolve("alice")
            fun hostReports(m: FileMutation) = h.hostIde.client.requestFileChange(m)

            write(host, "src/New.kt", "class New")
            hostReports(FileMutation(FileOpKind.CREATE_FILE, "src/New.kt", contentBase64 = MessageCodec.encodeBase64("class New".toByteArray())))
            eventually { Files.exists(mirror.resolve("src/New.kt")) }
            hostReports(FileMutation(FileOpKind.WRITE, "src/New.kt", contentBase64 = MessageCodec.encodeBase64("class New2".toByteArray())))
            eventually { Files.readString(mirror.resolve("src/New.kt")) == "class New2" }
            hostReports(FileMutation(FileOpKind.RENAME, "src/New.kt", newPath = "src/Renamed.kt"))
            eventually { Files.exists(mirror.resolve("src/Renamed.kt")) && !Files.exists(mirror.resolve("src/New.kt")) }
            hostReports(FileMutation(FileOpKind.DELETE, "src/Renamed.kt"))
            eventually { !Files.exists(mirror.resolve("src/Renamed.kt")) }
            // Changes to excluded files are never broadcast.
            hostReports(FileMutation(FileOpKind.WRITE, ".env", contentBase64 = MessageCodec.encodeBase64("API_KEY=new".toByteArray())))
            Thread.sleep(200)
            assertFalse(Files.exists(mirror.resolve(".env")))
            // Renaming a shared file to an excluded name becomes a delete for guests.
            write(host, "notes.txt", "n")
            hostReports(FileMutation(FileOpKind.CREATE_FILE, "notes.txt", contentBase64 = MessageCodec.encodeBase64("n".toByteArray())))
            eventually { Files.exists(mirror.resolve("notes.txt")) }
            hostReports(FileMutation(FileOpKind.RENAME, "notes.txt", newPath = "secrets.txt"))
            eventually { !Files.exists(mirror.resolve("notes.txt")) }
            assertFalse(Files.exists(mirror.resolve("secrets.txt")))
        }
    }

    @Test
    fun `unsaved host document content is what guests see, disk stays separate`() {
        sampleProject()
        Harness(host).use { h ->
            val path = "README.md"
            h.hostIde.onEdt { h.hostIde.texts[path] = "# Demo (unsaved)\n" } // host has unsaved edits
            val g = h.guest("alice", guestDir("alice"))
            awaitState(g, ClientState.CONNECTED)
            val mirror = tmp.resolve("alice")
            assertEquals("# Demo\n", Files.readString(mirror.resolve(path))) // disk snapshot
            g.open(path, Files.readString(mirror.resolve(path)))
            eventually { g.text(path) == "# Demo (unsaved)\n" } // live document
            assertEquals("# Demo\n", Files.readString(host.resolve(path)))
        }
    }

    @Test
    fun `guest edits made before the document state arrives are rebased, not lost`() {
        sampleProject()
        Harness(host).use { h ->
            val path = "README.md"
            h.hostIde.onEdt { h.hostIde.texts[path] = "HOST\n# Demo\n" }
            val g = h.guest("alice", guestDir("alice"))
            awaitState(g, ClientState.CONNECTED)
            g.onEdt {
                g.texts[path] = "# Demo\n"
                g.client.openDocument(path, "# Demo\n")
                val op = dev.opencollab.core.ot.TextOperation.replace(7, 7, 0, "guest\n")
                g.texts[path] = op.apply("# Demo\n")
                g.client.localEdit(path, op)
            }
            eventually { h.session.documentText(path) == "HOST\n# Demo\nguest\n" }
            eventually { g.text(path) == "HOST\n# Demo\nguest\n" && h.hostIde.text(path) == "HOST\n# Demo\nguest\n" }
        }
    }

    @Test
    fun `file chunks are verified against their hash`() {
        sampleProject()
        val big = ByteArray(700_000) { (it % 251).toByte() }
        Files.write(host.resolve("assets.bin"), big)
        Harness(host).use { h ->
            val g = h.guest("alice", guestDir("alice"))
            awaitState(g, ClientState.CONNECTED)
            val copy = Files.readAllBytes(tmp.resolve("alice/assets.bin"))
            assertEquals(Hashes.sha256(big), Hashes.sha256(copy))
        }
        val ws = dev.opencollab.core.files.MirrorWorkspace(guestDir("x"))
        org.junit.jupiter.api.assertThrows<java.io.IOException> {
            ws.acceptChunk("a.txt", 0, "abc".toByteArray(), true, "deadbeef", 100)
        }
        assertNull(ws.localHash("a.txt"))
    }
}
