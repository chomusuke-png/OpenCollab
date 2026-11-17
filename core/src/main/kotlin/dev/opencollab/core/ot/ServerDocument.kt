package dev.opencollab.core.ot

/**
 * The host-authoritative copy of one shared document.
 *
 * Every accepted operation increments [revision]. Operations based on an older revision are
 * transformed against the history recorded since that revision. Operation IDs are unique
 * per author; a re-sent operation (e.g. after a reconnect) is detected and acknowledged
 * again without being applied twice.
 *
 * History is bounded: once it exceeds [maxHistory] entries the oldest entries are folded
 * into a checkpoint. Operations based on a revision older than the checkpoint are rejected
 * as stale, and the client recovers from a fresh snapshot (see [ClientDocument.resync]).
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

    fun historySize(): Int = history.size

    fun appliedRevisionOf(author: String, opId: String): Int? = appliedOps[key(author, opId)]

    fun receive(author: String, opId: String, baseRevision: Int, op: TextOperation): Result {
        appliedOps[key(author, opId)]?.let { return Result.Duplicate(it) }
        if (baseRevision > revision || baseRevision < 0) {
            return Result.Rejected(RejectReason.INVALID, "Base revision $baseRevision is outside 0..$revision")
        }
        if (baseRevision < checkpointRevision) {
            return Result.Rejected(RejectReason.STALE, "Base revision $baseRevision predates checkpoint $checkpointRevision")
        }
        var transformed = op
        try {
            for (entry in history) {
                if (entry.revision <= baseRevision) continue
                transformed = TextOperation.transform(transformed, entry.op).first
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
        pruneHistory()
        return Result.Applied(transformed, revision)
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
    }

    private fun pruneHistory() {
        while (history.size > maxHistory) {
            val oldest = history.removeFirst()
            checkpointText = oldest.op.apply(checkpointText)
            checkpointRevision = oldest.revision
        }
    }

    private fun key(author: String, opId: String) = "$author\u0000$opId"
}
