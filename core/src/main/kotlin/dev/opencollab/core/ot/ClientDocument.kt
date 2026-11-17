package dev.opencollab.core.ot

/**
 * Client side of the OT protocol for one document.
 *
 * Every local edit is sent at once: several operations may be in flight, in order, each based
 * on the server revision this client had seen when it was made plus the client's own earlier
 * operations. The server transforms them against what the client had not seen yet (see
 * [ServerDocument]), so others see each keystroke as it happens instead of a group per round
 * trip. Acknowledgements and other participants' operations arrive in server order; an
 * incoming operation is transformed past the operations still in flight.
 *
 * The client also keeps a shadow copy of the last known server text, which makes recovery
 * possible when the server can no longer transform a stale operation or when the document was
 * edited locally before it was registered with the server.
 *
 * Not thread-safe: callers must invoke it from the thread that owns the editor document.
 */
class ClientDocument private constructor(
    val path: String,
    private val opIdPrefix: String,
    initialShadow: String,
    initialRevision: Int,
    synced: Boolean,
) {
    /** Operation to transmit to the server. */
    data class Outgoing(val opId: String, val baseRevision: Int, val op: TextOperation)

    /** Result of a resync: [local] must be applied to the editor document, then [outgoing] sent. */
    data class Resync(val local: TextOperation, val outgoing: Outgoing?)

    /** An operation sent and not acknowledged yet, as it applies after the earlier ones in flight. */
    private class InFlight(val opId: String, var op: TextOperation)

    var revision: Int = initialRevision
        private set

    /** Server text at [revision] (excludes local unacknowledged changes). */
    var shadow: String = initialShadow
        private set

    /** False until the first server state has been received. */
    var isSynced: Boolean = synced
        private set

    private val inFlight = ArrayDeque<InFlight>()
    /** Local edits made before the first server state; sent by [resync]. */
    private var unsynced: TextOperation? = null
    private var seq = 0L

    val hasPendingChanges: Boolean get() = inFlight.isNotEmpty() || unsynced != null

    /** Local edits not sent yet (only before the first sync: afterwards every edit is sent at once). */
    val hasUnsentChanges: Boolean get() = unsynced != null

    /** Ids of the operations in flight, oldest first (sent when resuming, see [resync]). */
    val inFlightOpIds: List<String> get() = inFlight.map { it.opId }

    /** The operation the next acknowledgement must be for. */
    val firstInFlightOpId: String? get() = inFlight.firstOrNull()?.opId

    /** Maps an offset in the server text at [revision] to the local text (past our edits in flight). */
    fun toLocalOffset(offset: Int): Int = inFlight.fold(offset) { at, sent -> sent.op.transformIndex(at) }

    /** Records a local edit; returns the operation to send now (null before the first sync). */
    fun applyLocal(op: TextOperation): Outgoing? {
        if (op.isNoop && op.baseLength == op.targetLength) return null
        if (!isSynced) {
            unsynced = unsynced?.compose(op) ?: op
            return null
        }
        val sent = InFlight(nextOpId(), op)
        inFlight.addLast(sent)
        return Outgoing(sent.opId, revision, op)
    }

    /**
     * Applies an operation from another participant that produced [newRevision] (it applies to
     * the server text at [revision]). Returns the operation to apply to the local editor document.
     */
    fun applyServer(op: TextOperation, newRevision: Int): TextOperation {
        check(isSynced) { "Document $path is not synced" }
        if (newRevision != revision + 1) {
            throw OutOfOrderException("Expected revision ${revision + 1} but got $newRevision for $path")
        }
        shadow = op.apply(shadow)
        revision = newRevision
        var incoming = op
        for (sent in inFlight) {
            val (sentPrime, incomingPrime) = TextOperation.transform(sent.op, incoming)
            sent.op = sentPrime
            incoming = incomingPrime
        }
        return incoming
    }

    /** Handles the acknowledgement of our oldest operation in flight. */
    fun acknowledge(opId: String, newRevision: Int) {
        val sent = inFlight.firstOrNull() ?: throw IllegalStateException("Unexpected ack $opId for $path")
        check(sent.opId == opId) { "Ack for $opId but ${sent.opId} is the oldest in flight" }
        if (newRevision != revision + 1) {
            throw OutOfOrderException("Ack revision $newRevision does not follow $revision")
        }
        shadow = sent.op.apply(shadow)
        revision = newRevision
        inFlight.removeFirst()
    }

    /**
     * After reconnecting and catching up: the operations still in flight, to send again in order
     * (same ids, so the server ignores any it already applied), based on the current revision.
     */
    fun inFlightForResend(): List<Outgoing> = inFlight.map { Outgoing(it.opId, revision, it.op) }

    /**
     * Recovers from a fresh server snapshot ([serverText] at [serverRevision]). The server had
     * already applied the first [appliedCount] operations in flight; the rest, and edits made
     * before the first sync, are rebased on top of the snapshot and sent again as one operation.
     */
    fun resync(serverText: String, serverRevision: Int, appliedCount: Int, discardLocal: Boolean = false): Resync {
        var localText = shadow
        inFlight.forEach { localText = it.op.apply(localText) }
        unsynced?.let { localText = it.apply(localText) }
        if (discardLocal) {
            // Drop unacknowledged local changes (e.g. the host refused them): local text -> server text.
            reset(serverText, serverRevision)
            return Resync(TextDiff.diff(localText, serverText), null)
        }
        var base = shadow
        var pendingLocal: TextOperation? = null
        inFlight.forEachIndexed { i, sent ->
            if (i < appliedCount) base = sent.op.apply(base) else pendingLocal = pendingLocal?.compose(sent.op) ?: sent.op
        }
        unsynced?.let { edits -> pendingLocal = pendingLocal?.compose(edits) ?: edits }

        val serverDelta = TextDiff.diff(base, serverText)
        val local = pendingLocal ?: TextOperation.identity(base.length)
        val (localPrime, serverPrime) = TextOperation.transform(local, serverDelta)

        reset(serverText, serverRevision)
        val outgoing = if (localPrime.isNoop && localPrime.baseLength == localPrime.targetLength) {
            null
        } else {
            val sent = InFlight(nextOpId(), localPrime)
            inFlight.addLast(sent)
            Outgoing(sent.opId, revision, localPrime)
        }
        return Resync(serverPrime, outgoing)
    }

    private fun reset(serverText: String, serverRevision: Int) {
        shadow = serverText
        revision = serverRevision
        isSynced = true
        inFlight.clear()
        unsynced = null
    }

    private fun nextOpId(): String = "$opIdPrefix-${++seq}"

    class OutOfOrderException(message: String) : RuntimeException(message)

    companion object {
        /** A document whose server state is not known yet; [localText] is its current local content. */
        fun unsynced(path: String, opIdPrefix: String, localText: String) =
            ClientDocument(path, opIdPrefix, localText, 0, synced = false)

        fun synced(path: String, opIdPrefix: String, serverText: String, revision: Int) =
            ClientDocument(path, opIdPrefix, serverText, revision, synced = true)
    }
}
