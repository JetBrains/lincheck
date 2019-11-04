package org.jetbrains.kotlinx.lincheck.tests.custom.deadlock

import org.jetbrains.kotlinx.lincheck.ErrorType
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.tests.AbstractLinCheckTest
import java.util.concurrent.atomic.AtomicBoolean

class LiveLockTest : AbstractLinCheckTest(expectedError = ErrorType.LIVELOCK) {
    private var counter = 0
    private var lock1 = AtomicBoolean(false)
    private var lock2 = AtomicBoolean(false)

    @Operation
    fun inc12(): Int {
        return lock1.synchronized {
            lock2.synchronized {
                counter++
            }
        }
    }

    @Operation
    fun inc21(): Int {
        return lock2.synchronized {
            lock1.synchronized {
                counter++
            }
        }
    }

    override fun extractState(): Any = counter

    private fun AtomicBoolean.synchronized(block: () -> Int): Int {
        while (!this.compareAndSet(false, true));
        val result = block()
        this.set(false)
        return result
    }
}