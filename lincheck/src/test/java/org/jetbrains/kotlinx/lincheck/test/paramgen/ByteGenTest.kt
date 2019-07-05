package org.jetbrains.kotlinx.lincheck.test.paramgen

import org.jetbrains.kotlinx.lincheck.paramgen.ByteGen
import org.junit.Test

class ByteGenTest {
    @Test(expected = IllegalArgumentException::class)
    fun testIncorrectBounds() {
        ByteGen("300:400")
    }

    @Test
    fun testUnique() {
        ByteGen("unique").generate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun testTooManyUniques() {
        val gen = ByteGen("unique")

        for (i in Byte.MIN_VALUE..Byte.MAX_VALUE + 1) // more than byte capacity
            gen.generate()
    }
}