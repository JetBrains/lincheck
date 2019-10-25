package org.jetbrains.kotlinx.lincheck.tests.custom.deadlock

import org.jetbrains.kotlinx.lincheck.ErrorType
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.tests.AbstractLinCheckTest

class DeadLockTest : AbstractLinCheckTest(expectedError = ErrorType.DEADLOCK) {
    private var counter = 0
    private var lock1 = Any()
    private var lock2 = Any()

    @Operation
    fun inc12(): Int {
        synchronized(lock1) {
            synchronized(lock2) {
                return counter++
            }
        }
    }

    @Operation
    fun inc21(): Int {
        synchronized(lock2) {
            synchronized(lock1) {
                return counter++
            }
        }
    }

    override fun extractState(): Any = counter
}