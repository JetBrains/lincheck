package org.jetbrains.kotlinx.lincheck.test.paramgen

import org.jetbrains.kotlinx.lincheck.paramgen.DoubleGen
import org.junit.Test
import org.junit.Assert.*

class DoubleGenTest {
    @Test
    fun generateDefault() {
        DoubleGen("").generate()
    }

    @Test
    fun generateInBounds() {
        val value = DoubleGen("30:40").generate()
        assertTrue(value in 30.0..40.0)
    }

    @Test
    fun generateWithStep() {
        val value = DoubleGen("30:10:40").generate()
        assertTrue(value == 30.0 || value == 40.0)
    }

    @Test
    fun generateWithZeroStep() {
        DoubleGen("30:0:40").generate()
    }


}