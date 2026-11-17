package dev.opencollab.core.ot

/**
 * Produces an operation transforming [from] into [to]. Used for recovery paths (stale
 * operations, reconnects after history was pruned, documents edited before they were
 * registered). The result is a single replace region bounded by the common prefix and
 * suffix, which is always correct; it never splits a UTF-16 surrogate pair.
 */
object TextDiff {
    fun diff(from: String, to: String): TextOperation {
        if (from == to) return TextOperation.identity(from.length)
        val maxPrefix = minOf(from.length, to.length)
        var prefix = 0
        while (prefix < maxPrefix && from[prefix] == to[prefix]) prefix++
        if (prefix > 0 && from[prefix - 1].isHighSurrogate()) prefix--

        val maxSuffix = minOf(from.length, to.length) - prefix
        var suffix = 0
        while (suffix < maxSuffix && from[from.length - 1 - suffix] == to[to.length - 1 - suffix]) suffix++
        if (suffix > 0 && from[from.length - suffix].isLowSurrogate()) suffix--

        return TextOperation.Builder()
            .retain(prefix)
            .insert(to.substring(prefix, to.length - suffix))
            .delete(from.length - prefix - suffix)
            .retain(suffix)
            .build()
    }
}
