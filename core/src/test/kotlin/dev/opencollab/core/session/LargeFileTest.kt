package dev.opencollab.core.session

import dev.opencollab.core.files.Hashes
import dev.opencollab.core.files.SharingRules
import dev.opencollab.core.protocol.FileMutation
import dev.opencollab.core.protocol.FileOpKind
import dev.opencollab.core.protocol.Message
import dev.opencollab.core.protocol.MessageCodec
import dev.opencollab.core.protocol.PROTOCOL_VERSION
import dev.opencollab.core.protocol.ProtocolException
import dev.opencollab.core.session.Harness.Companion.awaitState
import dev.opencollab.core.session.Harness.Companion.eventually
import dev.opencollab.core.transport.ConnectionListener
import dev.opencollab.core.transport.InMemoryTransport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class LargeFileTest {
    @TempDir lateinit var tmp: Path
    private val host get() = tmp.resolve("host")
    private val rules = SharingRules(maxFileBytes = 512L * 1024 * 1024)

    private fun bigFile(path: Path, megabytes: Int): String {
        Files.createDirectories(path.parent)
        val rnd = Random(megabytes)
        Files.newOutputStream(path).use { out ->
            val block = ByteArray(1024 * 1024)
            repeat(megabytes) { rnd.nextBytes(block); out.write(block) }
            out.write(byteArrayOf(1, 2, 3)) // not a multiple of the chunk size
        }
        return Hashes.sha256(path)
    }

    @Test
    fun `files far beyond the old limit are streamed to guests and verified`() {
        Harness.write(host, "README.md", "big assets inside\n")
        val sha = bigFile(host.resolve("assets/video.bin"), 120)
        Harness(host, rules).use { h ->
            val g = h.guest("alice", tmp.resolve("alice").also { Files.createDirectories(it) })
            awaitState(g, ClientState.CONNECTED, timeoutMs = 120_000)
            val copy = tmp.resolve("alice/assets/video.bin")
            assertEquals(Files.size(host.resolve("assets/video.bin")), Files.size(copy))
            assertEquals(sha, Hashes.sha256(copy))
            // No partial files are left behind.
            Files.list(tmp.resolve("alice/.opencollab/tmp")).use { assertEquals(0, it.count()) }
        }
    }

    @Test
    fun `host never has more than the transfer window unacknowledged`() {
        bigFile(host.resolve("big.bin"), 40)
        Harness(host, rules).use { h ->
            // A raw participant that receives chunks but never acknowledges them.
            val (client, server) = InMemoryTransport.pair("stalled", "host<-stalled")
            val received = AtomicLong()
            val welcomed = CopyOnWriteArrayList<Message>()
            client.setListener(object : ConnectionListener {
                override fun onMessage(text: String) {
                    when (val m = MessageCodec.decode(text)) {
                        is Message.FileChunk -> received.addAndGet(MessageCodec.decodeBase64(m.dataBase64).size.toLong())
                        else -> welcomed += m
                    }
                }
                override fun onClosed(reason: String) {}
            })
            h.session.accept(server)
            client.send(MessageCodec.encode(Message.Hello(PROTOCOL_VERSION, "stalled", h.session.config.editorToken)))
            eventually { welcomed.any { it is Message.Welcome } }
            client.send(MessageCodec.encode(Message.RequestFiles("r1", listOf("big.bin"))))
            eventually { received.get() >= MessageCodec.TRANSFER_WINDOW_BYTES }
            Thread.sleep(1_500)
            val inFlight = received.get()
            assertTrue(inFlight <= MessageCodec.TRANSFER_WINDOW_BYTES + MessageCodec.CHUNK_BYTES, "sent $inFlight bytes without acks")
            // Acknowledging releases the next window.
            client.send(MessageCodec.encode(Message.FileChunkAck("r1", inFlight)))
            eventually { received.get() > inFlight }
        }
    }

    @Test
    fun `large host changes are announced without content and fetched by guests`() {
        Harness.write(host, "README.md", "x\n")
        Harness(host, rules).use { h ->
            val g = h.guest("alice", tmp.resolve("alice").also { Files.createDirectories(it) })
            awaitState(g, ClientState.CONNECTED)
            val sha = bigFile(host.resolve("data/dump.bin"), 20)
            h.hostIde.client.requestFileChange(FileMutation(FileOpKind.CREATE_FILE, "data/dump.bin", contentBase64 = null))
            val copy = tmp.resolve("alice/data/dump.bin")
            eventually(60_000) { Files.exists(copy) && Hashes.sha256(copy) == sha }
        }
    }

    @Test
    fun `guests cannot upload files larger than the inline limit`() {
        Harness.write(host, "README.md", "x\n")
        Harness(host, rules).use { h ->
            val g = h.guest("alice", tmp.resolve("alice").also { Files.createDirectories(it) })
            awaitState(g, ClientState.CONNECTED)
            g.client.requestFileChange(FileMutation(FileOpKind.CREATE_FILE, "huge.bin", contentBase64 = null))
            eventually { g.failedFileRequests.isNotEmpty() }
            assertTrue(g.failedFileRequests.first().second.contains("guests cannot upload"))
            assertFalse(Files.exists(host.resolve("huge.bin")))
        }
        val tooBig = "A".repeat(MessageCodec.base64Length(MessageCodec.MAX_INLINE_FILE_BYTES) + 4)
        val raw = """{"t":"fileRequest","requestId":"r","mutation":{"kind":"WRITE","path":"a.bin","contentBase64":"$tooBig"}}"""
        assertThrows<ProtocolException> { MessageCodec.decode(raw) }
    }
}
