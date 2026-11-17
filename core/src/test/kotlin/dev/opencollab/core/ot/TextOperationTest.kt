package dev.opencollab.core.ot

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import kotlin.random.Random

internal fun randomString(rnd: Random, max: Int = 6): String {
    val alphabet = "abcdefghij \n{}😀"
    val sb = StringBuilder()
    repeat(rnd.nextInt(1, max + 1)) {
        val ch = alphabet[rnd.nextInt(alphabet.length)]
        if (ch.isHighSurrogate()) { sb.append("😀") } else if (!ch.isLowSurrogate()) sb.append(ch)
    }
    return sb.toString().ifEmpty { "x" }
}

/** Random operation applicable to [text]; never splits surrogate pairs. */
internal fun randomOp(rnd: Random, text: String): TextOperation {
    val b = TextOperation.Builder()
    var i = 0
    while (i < text.length) {
        val remaining = text.length - i
        var n = rnd.nextInt(1, minOf(remaining, 8) + 1)
        if (i + n < text.length && text[i + n].isLowSurrogate()) n++
        when (rnd.nextInt(4)) {
            0 -> b.retain(n)
            1 -> b.delete(n)
            2 -> { b.insert(randomString(rnd)); b.retain(n) }
            else -> b.retain(n)
        }
        i += n
    }
    if (rnd.nextBoolean()) b.insert(randomString(rnd))
    return b.build()
}

class TextOperationTest {
    @Test
    fun `apply insert retain delete`() {
        val op = TextOperation.Builder().retain(6).insert("big ").delete(5).insert("cat").retain(1).build()
        assertEquals("hello big cat!", op.apply("hello world!"))
        assertEquals(12, op.baseLength)
        assertEquals(14, op.targetLength)
    }

    @Test
    fun `apply rejects wrong base length`() {
        val op = TextOperation.replace(3, 0, 1, "x")
        assertThrows(IllegalArgumentException::class.java) { op.apply("abcd") }
    }

    @Test
    fun `builder normalises insert before delete`() {
        val a = TextOperation.Builder().delete(2).insert("x").build()
        val b = TextOperation.Builder().insert("x").delete(2).build()
        assertEquals(a, b)
    }

    @Test
    fun `wire round trip validates`() {
        val op = TextOperation.Builder().retain(2).insert("hi").delete(1).build()
        assertEquals(op, TextOperation.fromWire(op.toWire()))
        assertThrows(IllegalArgumentException::class.java) { TextOperation.fromWire(listOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { TextOperation.fromWire(listOf("")) }
    }

    @Test
    fun `concurrent inserts at same position are ordered deterministically`() {
        val base = "ab"
        val a = TextOperation.replace(2, 1, 0, "X")
        val b = TextOperation.replace(2, 1, 0, "Y")
        val (aPrime, bPrime) = TextOperation.transform(a, b)
        val viaA = bPrime.apply(a.apply(base))
        val viaB = aPrime.apply(b.apply(base))
        assertEquals(viaA, viaB)
        assertEquals("aXYb", viaA)
    }

    @Test
    fun `overlapping deletes converge`() {
        val base = "0123456789"
        val a = TextOperation.replace(10, 2, 5, "")
        val b = TextOperation.replace(10, 4, 5, "Z")
        val (aPrime, bPrime) = TextOperation.transform(a, b)
        assertEquals(bPrime.apply(a.apply(base)), aPrime.apply(b.apply(base)))
        assertEquals("01Z9", aPrime.apply(b.apply(base)))
    }

    @RepeatedTest(300)
    fun `transform satisfies convergence (TP1) on random input`() {
        val rnd = Random(System.nanoTime())
        val base = randomString(rnd, 30)
        val a = randomOp(rnd, base)
        val b = randomOp(rnd, base)
        val (aPrime, bPrime) = TextOperation.transform(a, b)
        assertEquals(bPrime.apply(a.apply(base)), aPrime.apply(b.apply(base)))
    }

    @RepeatedTest(300)
    fun `compose equals sequential application`() {
        val rnd = Random(System.nanoTime())
        val base = randomString(rnd, 30)
        val a = randomOp(rnd, base)
        val afterA = a.apply(base)
        val b = randomOp(rnd, afterA)
        assertEquals(b.apply(afterA), a.compose(b).apply(base))
    }

    @RepeatedTest(100)
    fun `diff produces an operation reaching the target`() {
        val rnd = Random(System.nanoTime())
        val from = randomString(rnd, 40)
        val to = randomOp(rnd, from).apply(from)
        assertEquals(to, TextDiff.diff(from, to).apply(from))
    }

    @Test
    fun `diff never splits surrogate pairs`() {
        val from = "a😀b"
        val to = "a😁b"
        val op = TextDiff.diff(from, to)
        assertEquals(to, op.apply(from))
        op.toWire().filterIsInstance<String>().forEach { s ->
            assertTrue(!s.first().isLowSurrogate() && !s.last().isHighSurrogate())
        }
    }

    @Test
    fun `transformIndex moves caret after remote insert before it`() {
        val op = TextOperation.replace(10, 2, 0, "abc")
        assertEquals(8, op.transformIndex(5))
        assertEquals(1, op.transformIndex(1))
        val del = TextOperation.replace(10, 2, 4, "")
        assertEquals(2, del.transformIndex(4))
        assertEquals(4, del.transformIndex(8))
    }
}
