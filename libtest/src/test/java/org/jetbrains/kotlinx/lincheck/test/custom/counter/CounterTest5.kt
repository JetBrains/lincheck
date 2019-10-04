package org.jetbrains.kotlinx.lincheck.test.custom.counter

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest
import sun.misc.Unsafe
import tests.custom.counter.CounterWrong3

class CounterTest5 : AbstractLincheckTest(true, false) {
    override fun extractState(): Any {
        return counter.get()
    }

    val counter = CounterWrong3()

    @Operation
    suspend fun incrementAndGet() = counter.incrementAndGet()
}