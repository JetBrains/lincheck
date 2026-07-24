package org.lincheck.docs

import org.jetbrains.lincheck.datastructures.*
import kotlin.test.Test

class Counter {
    var value = 0

    fun inc(): Int = ++value
    fun dec(): Int = --value
}

class CounterStructureTest {
    // Initial state
    private val c = Counter()

    // Concurrent operations
    @Operation
    fun inc() = c.inc()

    @Operation
    fun dec() = c.dec()

    @Test
    fun test() = ModelCheckingOptions().check(this::class)
}