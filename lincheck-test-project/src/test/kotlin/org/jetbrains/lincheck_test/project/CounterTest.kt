package org.jetbrains.lincheck_test.project

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import kotlin.test.Test
import java.util.concurrent.atomic.AtomicInteger

class CounterTest {
    @Volatile
    var counter: Int = 0

    @Operation
    fun inc(): Int {
        return counter++
    }

    @Operation
    fun get(): Int = counter

    @Test
    fun test() = ModelCheckingOptions().check(this::class.java)

}

class CounterTestWithAtomicMethods {
    @Volatile
    var counter: Int = 0

    @Volatile
    var idle = AtomicInteger(0)

    @Operation
    fun inc(): Int {
        idle.incrementAndGet()
        idle.incrementAndGet()
        idle.incrementAndGet()
        idle.incrementAndGet()
        idle.incrementAndGet()
        idle.incrementAndGet()
        return counter++
    }

    @Operation
    fun get(): Int = counter

    @Test
    fun test() = ModelCheckingOptions().check(this::class.java)

}