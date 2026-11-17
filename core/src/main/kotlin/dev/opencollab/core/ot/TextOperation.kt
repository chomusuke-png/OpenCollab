package dev.opencollab.core.ot

/**
 * A text operation in the classic retain / insert / delete form used by centralised OT
 * systems (Jupiter, Google Wave, ot.js). Lengths are in UTF-16 code units, matching both
 * Kotlin strings and IntelliJ document offsets.
 *
 * Wire encoding (same as ot.js): a positive integer is a retain, a negative integer is a
 * delete, and a string is an insert.
 *
 * Instances are immutable and always normalised (no zero-length components, adjacent
 * components of the same kind merged, inserts placed before deletes at the same position).
 */
class TextOperation private constructor(
    internal val ops: List<Any>,
    val baseLength: Int,
    val targetLength: Int,
) {
    val isNoop: Boolean get() = ops.isEmpty() || (ops.size == 1 && ops[0] is Int && (ops[0] as Int) > 0)

    /** Encoded components for the wire format. */
    fun toWire(): List<Any> = ops

    fun apply(text: String): String {
        require(text.length == baseLength) {
            "Operation base length $baseLength does not match text length ${text.length}"
        }
        val sb = StringBuilder(targetLength)
        var index = 0
        for (op in ops) {
            when {
                op is String -> sb.append(op)
                (op as Int) > 0 -> {
                    sb.append(text, index, index + op)
                    index += op
                }
                else -> index -= op
            }
        }
        check(index == text.length) { "Operation did not consume the whole text" }
        return sb.toString()
    }

    /** Returns an operation equivalent to applying `this` and then [other]. */
    fun compose(other: TextOperation): TextOperation {
        require(targetLength == other.baseLength) {
            "Cannot compose: target length $targetLength != base length ${other.baseLength}"
        }
        val result = Builder()
        val ops1 = ops
        val ops2 = other.ops
        var i1 = 0
        var i2 = 0
        var op1: Any? = ops1.getOrNull(i1++)
        var op2: Any? = ops2.getOrNull(i2++)
        while (true) {
            if (op1 == null && op2 == null) break
            if (op1 is Int && op1 < 0) {
                result.delete(-op1)
                op1 = ops1.getOrNull(i1++)
                continue
            }
            if (op2 is String) {
                result.insert(op2)
                op2 = ops2.getOrNull(i2++)
                continue
            }
            requireNotNull(op1) { "Cannot compose: first operation is too short" }
            requireNotNull(op2) { "Cannot compose: first operation is too long" }
            if (op1 is Int && op2 is Int && op1 > 0 && op2 > 0) {
                when {
                    op1 > op2 -> { result.retain(op2); op1 -= op2; op2 = ops2.getOrNull(i2++) }
                    op1 == op2 -> { result.retain(op1); op1 = ops1.getOrNull(i1++); op2 = ops2.getOrNull(i2++) }
                    else -> { result.retain(op1); op2 -= op1; op1 = ops1.getOrNull(i1++) }
                }
            } else if (op1 is String && op2 is Int && op2 < 0) {
                when {
                    op1.length > -op2 -> { op1 = op1.substring(-op2); op2 = ops2.getOrNull(i2++) }
                    op1.length == -op2 -> { op1 = ops1.getOrNull(i1++); op2 = ops2.getOrNull(i2++) }
                    else -> { op2 += op1.length; op1 = ops1.getOrNull(i1++) }
                }
            } else if (op1 is String && op2 is Int && op2 > 0) {
                when {
                    op1.length > op2 -> { result.insert(op1.substring(0, op2)); op1 = op1.substring(op2); op2 = ops2.getOrNull(i2++) }
                    op1.length == op2 -> { result.insert(op1); op1 = ops1.getOrNull(i1++); op2 = ops2.getOrNull(i2++) }
                    else -> { result.insert(op1); op2 -= op1.length; op1 = ops1.getOrNull(i1++) }
                }
            } else if (op1 is Int && op1 > 0 && op2 is Int && op2 < 0) {
                when {
                    op1 > -op2 -> { result.delete(-op2); op1 += op2; op2 = ops2.getOrNull(i2++) }
                    op1 == -op2 -> { result.delete(-op2); op1 = ops1.getOrNull(i1++); op2 = ops2.getOrNull(i2++) }
                    else -> { result.delete(op1); op2 += op1; op1 = ops1.getOrNull(i1++) }
                }
            } else {
                throw IllegalStateException("Cannot compose: unexpected components $op1 / $op2")
            }
        }
        return result.build()
    }

    /** Maps a caret/selection offset in the base text to the corresponding offset in the target text. */
    fun transformIndex(index: Int, insertBefore: Boolean = false): Int {
        var newIndex = index
        var pos = 0
        for (op in ops) {
            if (pos > index) break
            when {
                op is String -> {
                    if (pos < index || (pos == index && insertBefore)) newIndex += op.length
                }
                (op as Int) > 0 -> pos += op
                else -> {
                    newIndex -= minOf(-op, index - pos)
                    pos -= op
                }
            }
        }
        return newIndex.coerceIn(0, targetLength)
    }

    override fun equals(other: Any?): Boolean =
        other is TextOperation && ops == other.ops && baseLength == other.baseLength
    override fun hashCode(): Int = ops.hashCode() * 31 + baseLength
    override fun toString(): String = ops.joinToString(prefix = "[", postfix = "]") {
        when {
            it is String -> "ins(\"${it.take(20)}\")"
            (it as Int) > 0 -> "ret($it)"
            else -> "del(${-it})"
        }
    }

    class Builder {
        private val ops = ArrayList<Any>()
        private var baseLength = 0
        private var targetLength = 0

        fun retain(n: Int): Builder {
            require(n >= 0) { "retain must be non-negative" }
            if (n == 0) return this
            baseLength += n
            targetLength += n
            val last = ops.lastOrNull()
            if (last is Int && last > 0) ops[ops.size - 1] = last + n else ops.add(n)
            return this
        }

        fun insert(s: String): Builder {
            if (s.isEmpty()) return this
            targetLength += s.length
            val last = ops.lastOrNull()
            if (last is String) {
                ops[ops.size - 1] = last + s
            } else if (last is Int && last < 0) {
                // Keep inserts before deletes so equal operations have one canonical form.
                val prev = ops.getOrNull(ops.size - 2)
                if (prev is String) {
                    ops[ops.size - 2] = prev + s
                } else {
                    ops.add(ops.size - 1, s)
                }
            } else {
                ops.add(s)
            }
            return this
        }

        fun delete(n: Int): Builder {
            require(n >= 0) { "delete must be non-negative" }
            if (n == 0) return this
            baseLength += n
            val last = ops.lastOrNull()
            if (last is Int && last < 0) ops[ops.size - 1] = last - n else ops.add(-n)
            return this
        }

        fun build(): TextOperation {
            // Drop a trailing retain-only operation? No: retains carry the base length.
            return TextOperation(ops.toList(), baseLength, targetLength)
        }
    }

    companion object {
        fun identity(length: Int): TextOperation = Builder().retain(length).build()

        /** A single replace of [deleteLength] chars at [offset] by [insert] in a text of [length]. */
        fun replace(length: Int, offset: Int, deleteLength: Int, insert: String): TextOperation {
            require(offset in 0..length && deleteLength >= 0 && offset + deleteLength <= length) {
                "Invalid replace range offset=$offset delete=$deleteLength length=$length"
            }
            return Builder().retain(offset).insert(insert).delete(deleteLength)
                .retain(length - offset - deleteLength).build()
        }

        /**
         * Decodes the ot.js wire format, validating every component. Throws
         * [IllegalArgumentException] for malformed input.
         */
        fun fromWire(components: List<Any>): TextOperation {
            val b = Builder()
            for (c in components) {
                when (c) {
                    is String -> {
                        require(c.isNotEmpty()) { "Empty insert component" }
                        b.insert(c)
                    }
                    is Int -> {
                        require(c != 0) { "Zero-length component" }
                        if (c > 0) b.retain(c) else b.delete(-c)
                    }
                    else -> throw IllegalArgumentException("Invalid component type ${c::class.simpleName}")
                }
            }
            return b.build()
        }

        /**
         * Transforms concurrent operations [a] and [b] (same base) into (a', b') such that
         * b' ∘ a == a' ∘ b. When both insert at the same position, [a]'s insert goes first.
         */
        fun transform(a: TextOperation, b: TextOperation): Pair<TextOperation, TextOperation> {
            require(a.baseLength == b.baseLength) {
                "Cannot transform operations with different base lengths (${a.baseLength} vs ${b.baseLength})"
            }
            val aPrime = Builder()
            val bPrime = Builder()
            val ops1 = a.ops
            val ops2 = b.ops
            var i1 = 0
            var i2 = 0
            var op1: Any? = ops1.getOrNull(i1++)
            var op2: Any? = ops2.getOrNull(i2++)
            while (true) {
                if (op1 == null && op2 == null) break
                if (op1 is String) {
                    aPrime.insert(op1); bPrime.retain(op1.length)
                    op1 = ops1.getOrNull(i1++)
                    continue
                }
                if (op2 is String) {
                    aPrime.retain(op2.length); bPrime.insert(op2)
                    op2 = ops2.getOrNull(i2++)
                    continue
                }
                requireNotNull(op1) { "Cannot transform: first operation is too short" }
                requireNotNull(op2) { "Cannot transform: first operation is too long" }
                op1 as Int
                op2 as Int
                if (op1 > 0 && op2 > 0) {
                    val min: Int
                    when {
                        op1 > op2 -> { min = op2; op1 -= op2; op2 = ops2.getOrNull(i2++) }
                        op1 == op2 -> { min = op2; op1 = ops1.getOrNull(i1++); op2 = ops2.getOrNull(i2++) }
                        else -> { min = op1; op2 -= op1; op1 = ops1.getOrNull(i1++) }
                    }
                    aPrime.retain(min); bPrime.retain(min)
                } else if (op1 < 0 && op2 < 0) {
                    when {
                        -op1 > -op2 -> { op1 -= op2; op2 = ops2.getOrNull(i2++) }
                        op1 == op2 -> { op1 = ops1.getOrNull(i1++); op2 = ops2.getOrNull(i2++) }
                        else -> { op2 -= op1; op1 = ops1.getOrNull(i1++) }
                    }
                } else if (op1 < 0 && op2 > 0) {
                    val min: Int
                    when {
                        -op1 > op2 -> { min = op2; op1 += op2; op2 = ops2.getOrNull(i2++) }
                        -op1 == op2 -> { min = op2; op1 = ops1.getOrNull(i1++); op2 = ops2.getOrNull(i2++) }
                        else -> { min = -op1; op2 += op1; op1 = ops1.getOrNull(i1++) }
                    }
                    aPrime.delete(min)
                } else if (op1 > 0 && op2 < 0) {
                    val min: Int
                    when {
                        op1 > -op2 -> { min = -op2; op1 += op2; op2 = ops2.getOrNull(i2++) }
                        op1 == -op2 -> { min = op1; op1 = ops1.getOrNull(i1++); op2 = ops2.getOrNull(i2++) }
                        else -> { min = op1; op2 += op1; op1 = ops1.getOrNull(i1++) }
                    }
                    bPrime.delete(min)
                } else {
                    throw IllegalStateException("Cannot transform: unexpected components")
                }
            }
            return aPrime.build() to bPrime.build()
        }
    }
}
