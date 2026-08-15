package org.lincheck.docs

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalAtomicApi::class)
class SafeCounter {
    private var value = AtomicInt(0)

    fun inc() = value.addAndFetch(1)

    fun get(): Int = value.load()
}

class SafeCounterTest {
    @Test
    fun testIncrement() {
        val counter = SafeCounter()
        assertEquals(0, counter.get(), "Initial value should be 0")
        counter.inc()
        assertEquals(1, counter.get(), "Value after one increment should be 1")
    }
}

class SafeCounterConcurrentTest {
    private val c = SafeCounter()

    @Operation
    fun inc() = c.inc()

    @Operation
    fun get() = c.get()

    @Test
    fun modelCheckingTest() {
        ModelCheckingOptions().check(this::class)
    }
}
