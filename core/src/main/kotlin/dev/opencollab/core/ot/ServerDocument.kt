package dev.opencollab.core.ot

/**
 * The host-authoritative copy of one shared document.
 *
 * Every accepted operation increments [revision]. Clients send each edit at once, so an author
 * may have several operations in flight: operation k is based on the revision the author had
 * seen plus the author's own operations 1..k-1. For each author the server therefore keeps a
 * *bridge* (as in the Jupiter algorithm): the other authors' operations that author has not
 * seen yet, transformed so they apply after that author's latest operation. An incoming
 * operation is transformed against the part of its author's bridge it had not seen, and the
 * bridge is transformed against it in turn. Without operations of its own in flight, an
 * author's operation is transformed against the history since its base revision, as usual.
 *
 * Operation IDs are unique per author; a re-sent operation (e.g. after a reconnect) is detected
 * and acknowledged again without being applied twice.
 *
 * History is bounded: once it exceeds [maxHistory] entries the oldest entries are folded into a
 * checkpoint. Operations based on a revision older than the checkpoint, or that would need a
 * bridge dropped meanwhile, are rejected as stale, and the client recovers from a fresh snapshot
 * (see [ClientDocument.resync]).
 *
 * Not thread-safe: the owning session serialises access.
 */
class ServerDocument(
    val path: String,
    initialText: String,
    private val maxHistory: Int = 2_000,
    private val maxRememberedOpIds: Int = 10_000,
) {
    data class HistoryEntry(val op: TextOperation, val opId: String, val author: String, val revision: Int)

    sealed interface Result {
        /** Accepted; [op] is the transformed operation that produced [revision]. */
        data class Applied(val op: TextOperation, val revision: Int) : Result
        /** Already applied earlier at [revision]. */
        data class Duplicate(val revision: Int) : Result
        data class Rejected(val reason: RejectReason, val message: String) : Result
    }

    enum class RejectReason { STALE, INVALID }

    var text: String = initialText
        private set
    var revision: Int = 0
        private set
    /** Revision of [checkpointText]; history covers (checkpointRevision, revision]. */
    var checkpointRevision: Int = 0
        private set
    var checkpointText: String = initialText
        private set

    private val history = ArrayDeque<HistoryEntry>()
    private val appliedOps = object : LinkedHashMap<String, Int>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > maxRememberedOpIds
    }

    /** Another author's operation at [revision], as it applies after this author's latest operation. */
    private class Bridged(val op: TextOperation, val revision: Int)
    /** Per author: the others' operations applied after that author's latest one, in order. */
    private val bridges = HashMap<String, ArrayDeque<Bridged>>()
    /** Per author: revision of their latest applied operation. */
    private val lastOwnRevision = HashMap<String, Int>()

    fun historySize(): Int = history.size

    fun appliedRevisionOf(author: String, opId: String): Int? = appliedOps[key(author, opId)]

    fun receive(author: String, opId: String, baseRevision: Int, op: TextOperation): Result {
        appliedOps[key(author, opId)]?.let { return Result.Duplicate(it) }
        if (baseRevision > revision || baseRevision < 0) {
            return Result.Rejected(RejectReason.INVALID, "Base revision $baseRevision is outside 0..$revision")
        }
        val unseen = unseenBy(author, baseRevision)
            ?: return Result.Rejected(RejectReason.STALE, "Base revision $baseRevision of $author can no longer be transformed (checkpoint $checkpointRevision)")
        var transformed = op
        val rebridged = ArrayList<Bridged>(unseen.size)
        try {
            for (other in unseen) {
                val (mine, theirs) = TextOperation.transform(transformed, other.op)
                transformed = mine
                rebridged += Bridged(theirs, other.revision)
            }
            if (transformed.baseLength != text.length) {
                return Result.Rejected(RejectReason.INVALID, "Operation length does not match revision $baseRevision")
            }
            text = transformed.apply(text)
        } catch (e: IllegalArgumentException) {
            return Result.Rejected(RejectReason.INVALID, e.message ?: "invalid operation")
        } catch (e: IllegalStateException) {
            return Result.Rejected(RejectReason.INVALID, e.message ?: "invalid operation")
        }
        revision++
        history.addLast(HistoryEntry(transformed, opId, author, revision))
        appliedOps[key(author, opId)] = revision

        // This author has now seen nothing after its own operation except what it had not seen
        // yet, which now applies after this operation. Everybody else gets this operation as is:
        // it applies to the current text, i.e. after everything already in their bridges.
        bridges[author] = ArrayDeque(rebridged)
        lastOwnRevision[author] = revision
        for ((other, bridge) in bridges) if (other != author) bridge.addLast(Bridged(transformed, revision))
        pruneHistory()
        return Result.Applied(transformed, revision)
    }

    /**
     * The operations [author] had not seen in a context based on [baseRevision] (plus that author's
     * own operations since), each as it applies in that context; null if they are no longer known.
     */
    private fun unseenBy(author: String, baseRevision: Int): List<Bridged>? =
        if ((lastOwnRevision[author] ?: -1) > baseRevision) {
            // Earlier operations of this author are in flight: its context includes them, which the
            // bridge accounts for (it does not need the history, so a pruned one is fine). Drop
            // what the author had seen.
            bridges[author]?.filter { it.revision > baseRevision }
        } else if (baseRevision < checkpointRevision) {
            null
        } else {
            history.filter { it.revision > baseRevision }.map { Bridged(it.op, it.revision) }
        }

    /**
     * Maps offsets (a caret, selection ends) [author] took in a context based on [baseRevision] to
     * the current text, the same way as its operations. Null if they can no longer be mapped.
     */
    fun mapPositions(author: String, baseRevision: Int, offsets: List<Int>): List<Int>? {
        if (baseRevision < 0 || baseRevision > revision) return null
        val unseen = unseenBy(author, baseRevision) ?: return null
        return offsets.map { offset -> unseen.fold(offset) { at, other -> other.op.transformIndex(at) }.coerceIn(0, text.length) }
    }

    /** Entries with revision > [sinceRevision], or null if that part of history was pruned. */
    fun historySince(sinceRevision: Int): List<HistoryEntry>? {
        if (sinceRevision < checkpointRevision || sinceRevision > revision) return null
        return history.filter { it.revision > sinceRevision }
    }

    /** Folds all history into a checkpoint at the current revision (e.g. after a save). */
    fun checkpoint() {
        history.clear()
        checkpointRevision = revision
        checkpointText = text
        bridges.clear() // operations in flight from before now are stale and recover by resync
    }

    private fun pruneHistory() {
        while (history.size > maxHistory) {
            val oldest = history.removeFirst()
            checkpointText = oldest.op.apply(checkpointText)
            checkpointRevision = oldest.revision
        }
        // A bridge as long as the history belongs to an author idle for that long: its operations
        // in flight, if any, are stale anyway. Keeps memory bounded per author.
        bridges.values.removeIf { it.size > maxHistory }
    }

    private fun key(author: String, opId: String) = "$author\u0000$opId"
}
