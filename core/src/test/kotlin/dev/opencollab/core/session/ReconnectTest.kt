package dev.opencollab.core.session

import dev.opencollab.core.protocol.FileMutation
import dev.opencollab.core.protocol.FileOpKind
import dev.opencollab.core.protocol.MessageCodec
import dev.opencollab.core.session.Harness.Companion.awaitState
import dev.opencollab.core.session.Harness.Companion.eventually
import dev.opencollab.core.session.Harness.Companion.write
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ReconnectTest {
    @TempDir lateinit var tmp: Path
    private val host get() = tmp.resolve("host")
    private fun guestDir(name: String) = tmp.resolve(name).also { Files.createDirectories(it) }

    private fun project() {
        write(host, "a.txt", "0123456789\n")
        write(host, "b.txt", "bee\n")
    }

    @Test
    fun `edits made while disconnected are recovered from operation history`() {
        project()
        Harness(host).use { h ->
            val g = h.guest("alice", guestDir("alice"))
            awaitState(g, ClientState.CONNECTED)
            val pid = g.client.participantId
            g.open("a.txt", "0123456789\n")
            eventually { g.onEdt { g.client.isDocumentSynced("a.txt") } }
            g.type("a.txt", 0, "[g1]")
            eventually { h.session.documentText("a.txt") == "[g1]0123456789\n" }

            h.links.last().close("network down")
            eventually { g.client.state == ClientState.RECONNECTING || g.client.state == ClientState.SYNCING }
            // Both sides keep typing during the outage.
            g.type("a.txt", 4, "[offline]")
            h.hostIde.type("a.txt", h.hostIde.text("a.txt")!!.length, "[host]")

            awaitState(g, ClientState.CONNECTED)
            assertEquals(pid, g.client.participantId, "participant identity is resumed")
            eventually {
                val t = h.session.documentText("a.txt")
                t == g.text("a.txt") && t == h.hostIde.text("a.txt")
            }
            assertEquals("[g1][offline]0123456789\n[host]", h.session.documentText("a.txt"))
        }
    }

    @Test
    fun `reconnect after history was pruned recovers from a snapshot without duplicating edits`() {
        project()
        Harness(host, config = HostConfig("demo", maxHistoryPerDocument = 3)).use { h ->
            val g = h.guest("alice", guestDir("alice"))
            awaitState(g, ClientState.CONNECTED)
            g.open("a.txt", "0123456789\n")
            eventually { g.onEdt { g.client.isDocumentSynced("a.txt") } }

            h.links.last().close("network down")
            eventually { g.client.state != ClientState.CONNECTED }
            g.type("a.txt", 0, "G")
            repeat(10) { h.hostIde.type("a.txt", 11, "h") } // beyond the 3-entry history window
            awaitState(g, ClientState.CONNECTED)
            eventually {
                val t = h.session.documentText("a.txt")
                t == g.text("a.txt") && t == h.hostIde.text("a.txt")
            }
            assertEquals("G0123456789\nhhhhhhhhhh", h.session.documentText("a.txt"))
        }
    }

    @Test
    fun `file events missed while disconnected are replayed`() {
        project()
        Harness(host).use { h ->
            val g = h.guest("alice", guestDir("alice"))
            awaitState(g, ClientState.CONNECTED)
            h.links.last().close("network down")
            eventually { g.client.state != ClientState.CONNECTED }
            write(host, "c.txt", "sea")
            h.hostIde.client.requestFileChange(FileMutation(FileOpKind.CREATE_FILE, "c.txt", contentBase64 = MessageCodec.encodeBase64("sea".toByteArray())))
            Files.delete(host.resolve("b.txt"))
            h.hostIde.client.requestFileChange(FileMutation(FileOpKind.DELETE, "b.txt"))
            awaitState(g, ClientState.CONNECTED)
            val mirror = tmp.resolve("alice")
            eventually { Files.exists(mirror.resolve("c.txt")) && !Files.exists(mirror.resolve("b.txt")) }
        }
    }

    @Test
    fun `rejoining an existing mirror only downloads changed files and removes stale ones`() {
        project()
        val dir = guestDir("alice")
        Harness(host).use { h ->
            val g = h.guest("alice", dir)
            awaitState(g, ClientState.CONNECTED)
            g.client.close()
        }
        // Between sessions: host changes a file and deletes another; guest has a local-only file.
        write(host, "a.txt", "changed\n")
        Files.delete(host.resolve("b.txt"))
        write(dir, "my-notes.txt", "guest local file")
        Harness(host).use { h ->
            val g = h.guest("alice", dir)
            awaitState(g, ClientState.CONNECTED)
            assertEquals("changed\n", Files.readString(dir.resolve("a.txt")))
            assertTrue(!Files.exists(dir.resolve("b.txt")))
            assertTrue(Files.exists(dir.resolve("my-notes.txt")), "guest-local files are never deleted")
        }
    }

    @Test
    fun `joining refuses a non-empty folder that is not a mirror`() {
        val dir = guestDir("alice")
        write(dir, "important.txt", "do not touch")
        val ws = dev.opencollab.core.files.MirrorWorkspace(dir)
        assertTrue(!ws.canAdopt())
        assertTrue(dev.opencollab.core.files.MirrorWorkspace(guestDir("empty")).canAdopt())
    }
}
