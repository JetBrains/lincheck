package org.jetbrains.kotlinx.lincheck.test.paramgen

import org.jetbrains.kotlinx.lincheck.paramgen.StringGen
import org.junit.Test
import org.junit.Assert.*

class StringGenTest {
    @Test
    fun testGenerateDefault() {
        StringGen("").generate()
    }

    @Test
    fun testGenerateBoundedString() {
        val value = StringGen("1").generate()
        assertEquals(0, value.length)
    }

    @Test
    fun testGenerateCustomAlphabet() {
        val value = StringGen("5:()").generate()
        assertTrue(value.all { it in "()" })
    }

    @Test
    fun testGenerateUnique() {
        StringGen("unique").generate()
    }
}