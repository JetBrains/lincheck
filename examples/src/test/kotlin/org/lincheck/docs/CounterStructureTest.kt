package org.lincheck.docs

import org.jetbrains.lincheck.datastructures.*
import kotlin.test.*

class Counter {
    var value = 0

    fun inc(): Int = ++value
    fun dec(): Int = --value
}

class CounterStructureTest {
    private val c = Counter()

    @Operation
    fun inc() = c.inc()

    @Operation
    fun dec() = c.dec()

    @Test
    fun test() = ModelCheckingOptions().check(this::class)

    @Test
    fun testWithIterations() = ModelCheckingOptions()
        .iterations(100) // Specify the number of generated scenarios
        .check(this::class)

    @Test
    fun stressTest() = StressOptions().check(this::class)
}