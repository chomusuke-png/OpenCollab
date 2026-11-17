package dev.opencollab.core.ot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Simulates the host-authoritative engine with several clients, random interleavings,
 * random network delays and duplicated deliveries, then checks convergence. Clients send every
 * edit at once, so each may have many operations in flight.
 */
class SyncEngineTest {
    private sealed interface ToServer { val client: Int }
    private data class Submit(override val client: Int, val out: ClientDocument.Outgoing) : ToServer
    private sealed interface ToClient
    private data class Ack(val opId: String, val revision: Int) : ToClient
    private data class Remote(val op: TextOperation, val revision: Int) : ToClient

    private class SimClient(val id: Int, val doc: ClientDocument, var text: String) {
        val inbox = ArrayDeque<ToClient>()
    }

    @RepeatedTest(200)
    fun `random concurrent edits converge on all clients`() = simulate(Random(System.nanoTime()), editWeight = 2, serverWeight = 1, clientWeight = 1)

    /** Slow network: the host and clients rarely process messages, so many edits are in flight at once. */
    @RepeatedTest(100)
    fun `many operations in flight per client still converge`() = simulate(Random(System.nanoTime()), editWeight = 8, serverWeight = 1, clientWeight = 1)

    private fun simulate(rnd: Random, editWeight: Int, serverWeight: Int, clientWeight: Int) {
        val initial = "fun main() {\n  println(\"hi\")\n}\n"
        val server = ServerDocument("Main.kt", initial, maxHistory = 10_000)
        val clients = (0 until rnd.nextInt(2, 5)).map {
            SimClient(it, ClientDocument.synced("Main.kt", "c$it", initial, 0), initial)
        }
        val toServer = ArrayDeque<ToServer>()
        val sentLog = mutableListOf<ToServer>()

        fun serverStep() {
            val msg = toServer.removeFirst() as Submit
            when (val r = server.receive("c${msg.client}", msg.out.opId, msg.out.baseRevision, msg.out.op)) {
                is ServerDocument.Result.Applied -> clients.forEach { c ->
                    if (c.id == msg.client) c.inbox.addLast(Ack(msg.out.opId, r.revision))
                    else c.inbox.addLast(Remote(r.op, r.revision))
                }
                is ServerDocument.Result.Duplicate -> Unit // duplicate delivery: already acknowledged
                is ServerDocument.Result.Rejected -> error("unexpected rejection ${r.message}")
            }
        }

        fun clientStep(c: SimClient) {
            when (val m = c.inbox.removeFirst()) {
                is Ack -> c.doc.acknowledge(m.opId, m.revision)
                is Remote -> c.text = c.doc.applyServer(m.op, m.revision).apply(c.text)
            }
        }

        val total = editWeight + serverWeight + clientWeight + 1
        repeat(300) {
            val c = clients[rnd.nextInt(clients.size)]
            val pick = rnd.nextInt(total)
            when {
                pick < editWeight -> {
                    val op = randomOp(rnd, c.text)
                    c.text = op.apply(c.text)
                    c.doc.applyLocal(op)?.let { val s = Submit(c.id, it); toServer.addLast(s); sentLog += s }
                }
                pick < editWeight + serverWeight -> if (toServer.isNotEmpty()) serverStep()
                pick < editWeight + serverWeight + clientWeight -> if (c.inbox.isNotEmpty()) clientStep(c)
                else -> if (sentLog.isNotEmpty() && rnd.nextInt(10) == 0) toServer.addLast(sentLog.random(rnd)) // duplicate
            }
        }
        while (toServer.isNotEmpty() || clients.any { it.inbox.isNotEmpty() }) {
            while (toServer.isNotEmpty()) serverStep()
            clients.forEach { c -> while (c.inbox.isNotEmpty()) clientStep(c) }
        }
        clients.forEach { c ->
            assertEquals(server.text, c.text, "client ${c.id} diverged")
            assertFalse(c.doc.hasPendingChanges)
            assertEquals(server.revision, c.doc.revision)
        }
        // History + checkpoint reproduce the current text.
        var replay = server.checkpointText
        server.historySince(server.checkpointRevision)!!.forEach { replay = it.op.apply(replay) }
        assertEquals(server.text, replay)
    }

    @Test
    fun `duplicate operation is applied once`() {
        val server = ServerDocument("a.txt", "abc")
        val op = TextOperation.replace(3, 3, 0, "d")
        val first = server.receive("p1", "op-1", 0, op)
        assertTrue(first is ServerDocument.Result.Applied)
        val second = server.receive("p1", "op-1", 0, op)
        assertEquals(ServerDocument.Result.Duplicate(1), second)
        assertEquals("abcd", server.text)
        // Same op id from a different author is a different operation.
        assertTrue(server.receive("p2", "op-1", 1, TextOperation.replace(4, 0, 0, "<")) is ServerDocument.Result.Applied)
        assertEquals("<abcd", server.text)
    }

    @Test
    fun `stale operation older than checkpoint is rejected and recovered by resync`() {
        val server = ServerDocument("a.txt", "hello", maxHistory = 2)
        val client = ClientDocument.synced("a.txt", "slow", "hello", 0)
        var clientText = "hello"
        // The slow client edits at revision 0 ...
        val local = TextOperation.replace(5, 5, 0, " world")
        clientText = local.apply(clientText)
        val out = client.applyLocal(local)!!
        // ... while others push the history beyond the checkpoint.
        repeat(4) { i ->
            server.receive("other", "o$i", server.revision, TextOperation.replace(server.text.length, 0, 0, "$i"))
        }
        val rejected = server.receive("slow", out.opId, out.baseRevision, out.op)
        assertEquals(ServerDocument.RejectReason.STALE, (rejected as ServerDocument.Result.Rejected).reason)

        val applied = if (server.appliedRevisionOf("slow", out.opId) != null) 1 else 0
        val resync = client.resync(server.text, server.revision, applied)
        clientText = resync.local.apply(clientText)
        val again = resync.outgoing!!
        val r = server.receive("slow", again.opId, again.baseRevision, again.op) as ServerDocument.Result.Applied
        client.acknowledge(again.opId, r.revision)
        assertEquals(server.text, clientText)
        assertEquals("3210hello world", server.text)
    }

    @Test
    fun `stale operation within history is transformed`() {
        val server = ServerDocument("a.txt", "abc")
        server.receive("p1", "1", 0, TextOperation.replace(3, 0, 0, "X")) // Xabc
        val r = server.receive("p2", "1", 0, TextOperation.replace(3, 3, 0, "Y")) // based on rev 0
        assertTrue(r is ServerDocument.Result.Applied)
        assertEquals("XabcY", server.text)
    }

    @Test
    fun `invalid operations are rejected without changing state`() {
        val server = ServerDocument("a.txt", "abc")
        val tooLong = TextOperation.replace(10, 0, 0, "x")
        assertTrue(server.receive("p", "1", 0, tooLong) is ServerDocument.Result.Rejected)
        assertTrue(server.receive("p", "2", 5, TextOperation.identity(3)) is ServerDocument.Result.Rejected)
        assertEquals("abc", server.text)
        assertEquals(0, server.revision)
        assertNull(server.appliedRevisionOf("p", "1"))
    }

    @Test
    fun `unsynced document rebases local edits onto server state`() {
        // Guest edited its stale disk copy before the server state arrived.
        val doc = ClientDocument.unsynced("a.txt", "g", "line1\nline2\n")
        var local = "line1\nline2\n"
        val edit = TextOperation.replace(local.length, local.length, 0, "guest\n")
        local = edit.apply(local)
        assertNull(doc.applyLocal(edit))
        val serverText = "HOST\nline1\nline2\n"
        val resync = doc.resync(serverText, 7, appliedCount = 0)
        local = resync.local.apply(local)
        assertEquals("HOST\nline1\nline2\nguest\n", local)
        val out = resync.outgoing!!
        assertEquals(7, out.baseRevision)
        assertEquals(local, out.op.apply(serverText))
    }

    @Test
    fun `resync does not resend an operation the server already applied`() {
        val server = ServerDocument("a.txt", "abc")
        val doc = ClientDocument.synced("a.txt", "g", "abc", 0)
        var local = "abc"
        val e1 = TextOperation.replace(3, 0, 0, "1")
        local = e1.apply(local)
        val out = doc.applyLocal(e1)!!
        server.receive("g", out.opId, out.baseRevision, out.op) // applied, but the ack was lost
        val e2 = TextOperation.replace(4, 4, 0, "2")
        local = e2.apply(local)
        doc.applyLocal(e2)!! // sent at once, but lost with the connection
        val applied = doc.inFlightOpIds.takeWhile { server.appliedRevisionOf("g", it) != null }.size
        assertEquals(1, applied)
        val resync = doc.resync(server.text, server.revision, applied)
        local = resync.local.apply(local)
        val again = resync.outgoing!!
        server.receive("g", again.opId, again.baseRevision, again.op)
        assertEquals("1abc2", server.text)
        assertEquals(server.text, local)
    }

    @Test
    fun `an author's edits in flight are placed against only what it had not seen`() {
        val server = ServerDocument("a.txt", "hello")
        val alice = ClientDocument.synced("a.txt", "a", "hello", 0)
        val bob = ClientDocument.synced("a.txt", "b", "hello", 0)
        var aliceText = "hello"
        var bobText = "hello"
        // Alice types "!" then "?" at the end, both sent before any acknowledgement (based on revision 0).
        val a1 = TextOperation.replace(5, 5, 0, "!").also { aliceText = it.apply(aliceText) }.let { alice.applyLocal(it)!! }
        val a2 = TextOperation.replace(6, 6, 0, "?").also { aliceText = it.apply(aliceText) }.let { alice.applyLocal(it)!! }
        assertEquals(0, a2.baseRevision)
        // Meanwhile Bob types ">" at the start, which reaches the host first.
        val b1 = TextOperation.replace(5, 0, 0, ">").also { bobText = it.apply(bobText) }.let { bob.applyLocal(it)!! }
        val rb = server.receive("b", b1.opId, b1.baseRevision, b1.op) as ServerDocument.Result.Applied
        val ra1 = server.receive("a", a1.opId, a1.baseRevision, a1.op) as ServerDocument.Result.Applied
        val ra2 = server.receive("a", a2.opId, a2.baseRevision, a2.op) as ServerDocument.Result.Applied
        assertEquals(">hello!?", server.text)
        // Alice gets Bob's edit, then her two acknowledgements; Bob gets Alice's edits.
        aliceText = alice.applyServer(rb.op, rb.revision).apply(aliceText)
        alice.acknowledge(a1.opId, ra1.revision)
        alice.acknowledge(a2.opId, ra2.revision)
        bob.acknowledge(b1.opId, rb.revision)
        bobText = bob.applyServer(ra1.op, ra1.revision).apply(bobText)
        bobText = bob.applyServer(ra2.op, ra2.revision).apply(bobText)
        assertEquals(server.text, aliceText)
        assertEquals(server.text, bobText)
        assertFalse(alice.hasPendingChanges)
    }
}
