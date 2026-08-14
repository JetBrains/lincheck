package org.lincheck.docs

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import kotlin.test.Test
import kotlin.test.assertEquals

class UnsafeCounter {
    private var value: Int = 0

    fun inc() = value++

    fun get(): Int = value
}

class UnsafeCounterTest {
    @Test
    fun testIncrement() {
        val counter = UnsafeCounter()
        assertEquals(0, counter.get(), "Initial value should be 0")
        counter.inc()
        assertEquals(1, counter.get(), "Value after one increment should be 1")
    }
}

class UnsafeCounterConcurrentTest {
    private val c = UnsafeCounter()

    @Operation
    fun inc() = c.inc()

    @Operation
    fun get() = c.get()

    @Test
    fun modelCheckingTest() {
        ModelCheckingOptions().check(this::class)
    }
}
