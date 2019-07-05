package org.jetbrains.kotlinx.lincheck.test.paramgen

import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.junit.Test
import org.junit.Assert.*

class IntGenTest {
    @Test
    fun testWithoutParams() {
        IntGen("").generate()
    }

    @Test
    fun testWithBounds() {
        val gen = IntGen("1:5")
        for (i in 0 until 10) {
            val value = gen.generate()
            assertTrue(value in 1..5)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun testUnknownParameters() {
        IntGen("1:3:5")
    }

    @Test
    fun testOneUniqueGenerate() {
        IntGen("unique").generate()
    }

    @Test
    fun testUniqueness() {
        val gen = IntGen("unique")
        val set = mutableSetOf<Int>()

        val iterations = 100

        for (i in 0 until iterations) {
            val value = gen.generate()
            assertFalse(set.contains(value))
            set.add(value)
        }
    }
}