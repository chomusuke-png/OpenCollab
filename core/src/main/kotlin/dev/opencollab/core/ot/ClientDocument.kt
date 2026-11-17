package dev.opencollab.core.ot

/**
 * Client side of the OT protocol for one document (the classic ot.js client state machine):
 * at most one operation is in flight awaiting acknowledgement; further local edits are
 * composed into a buffer that is sent once the in-flight operation is acknowledged.
 *
 * The client also keeps a shadow copy of the last known server text, which makes
 * recovery possible when the server can no longer transform a stale operation or when
 * the document was edited locally before it was registered with the server.
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

    private class Pending(val opId: String, val baseRevision: Int, val op: TextOperation)

    var revision: Int = initialRevision
        private set

    /** Server text at [revision] (excludes local unacknowledged changes). */
    var shadow: String = initialShadow
        private set

    /** False until the first server state has been received. */
    var isSynced: Boolean = synced
        private set

    private var outstanding: Pending? = null
    private var buffer: TextOperation? = null
    private var seq = 0L

    val hasPendingChanges: Boolean get() = outstanding != null || buffer != null
    val outstandingOpId: String? get() = outstanding?.opId

    /** Records a local edit; returns an operation to send now, if any. */
    fun applyLocal(op: TextOperation): Outgoing? {
        if (op.isNoop && op.baseLength == op.targetLength) return null
        if (!isSynced || outstanding != null) {
            buffer = buffer?.compose(op) ?: op
            return null
        }
        val pending = Pending(nextOpId(), revision, op)
        outstanding = pending
        return Outgoing(pending.opId, pending.baseRevision, pending.op)
    }

    /**
     * Applies an operation from another participant that produced [newRevision]. Returns
     * the operation to apply to the local editor document.
     */
    fun applyServer(op: TextOperation, newRevision: Int): TextOperation {
        check(isSynced) { "Document $path is not synced" }
        if (newRevision != revision + 1) {
            throw OutOfOrderException("Expected revision ${revision + 1} but got $newRevision for $path")
        }
        shadow = op.apply(shadow)
        revision = newRevision
        var incoming = op
        outstanding?.let { pending ->
            val (pendingPrime, incomingPrime) = TextOperation.transform(pending.op, incoming)
            outstanding = Pending(pending.opId, pending.baseRevision, pendingPrime)
            incoming = incomingPrime
        }
        buffer?.let { buf ->
            val (bufferPrime, incomingPrime) = TextOperation.transform(buf, incoming)
            buffer = bufferPrime
            incoming = incomingPrime
        }
        return incoming
    }

    /** Handles the acknowledgement of our in-flight operation; returns the next operation to send. */
    fun acknowledge(opId: String, newRevision: Int): Outgoing? {
        val pending = outstanding ?: throw IllegalStateException("Unexpected ack $opId for $path")
        check(pending.opId == opId) { "Ack for $opId but ${pending.opId} is in flight" }
        if (newRevision != revision + 1) {
            throw OutOfOrderException("Ack revision $newRevision does not follow ${revision}")
        }
        shadow = pending.op.apply(shadow)
        revision = newRevision
        outstanding = null
        val buf = buffer ?: return null
        buffer = null
        val next = Pending(nextOpId(), revision, buf)
        outstanding = next
        return Outgoing(next.opId, next.baseRevision, next.op)
    }

    /** The in-flight operation to re-send unchanged after a reconnect (same ID → deduplicated). */
    fun outstandingForResend(): Outgoing? = outstanding?.let { Outgoing(it.opId, it.baseRevision, it.op) }

    /**
     * Recovers from a fresh server snapshot ([serverText] at [serverRevision]).
     * [outstandingApplied] tells whether the server already applied our in-flight operation.
     * Local unacknowledged changes are rebased on top of the snapshot and re-sent.
     */
    fun resync(serverText: String, serverRevision: Int, outstandingApplied: Boolean, discardLocal: Boolean = false): Resync {
        if (discardLocal) {
            // Drop unacknowledged local changes (e.g. the host refused them): local text -> server text.
            var localText = shadow
            outstanding?.let { localText = it.op.apply(localText) }
            buffer?.let { localText = it.apply(localText) }
            shadow = serverText
            revision = serverRevision
            isSynced = true
            outstanding = null
            buffer = null
            return Resync(TextDiff.diff(localText, serverText), null)
        }
        var base = shadow
        var pendingLocal: TextOperation? = null
        outstanding?.let { pending ->
            if (outstandingApplied) base = pending.op.apply(base) else pendingLocal = pending.op
        }
        buffer?.let { buf -> pendingLocal = pendingLocal?.compose(buf) ?: buf }

        val serverDelta = TextDiff.diff(base, serverText)
        val local = pendingLocal ?: TextOperation.identity(base.length)
        val (localPrime, serverPrime) = TextOperation.transform(local, serverDelta)

        shadow = serverText
        revision = serverRevision
        isSynced = true
        outstanding = null
        buffer = null
        val outgoing = if (localPrime.isNoop && localPrime.baseLength == localPrime.targetLength) {
            null
        } else {
            val p = Pending(nextOpId(), revision, localPrime)
            outstanding = p
            Outgoing(p.opId, p.baseRevision, p.op)
        }
        return Resync(serverPrime, outgoing)
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
