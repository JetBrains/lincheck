package org.jetbrains.kotlinx.lincheck.tests.atomicfu

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.ErrorType
import org.jetbrains.kotlinx.lincheck.tests.AbstractLinCheckTest

class AtomicLongTest : AbstractLinCheckTest(expectedError = ErrorType.NO_ERROR) {
    private var value = atomic(0L)

    @Operation
    fun set(key: Long) {
        value.value = key
    }

    @Operation
    fun get(): Long = value.value

    @Operation
    fun getAndIncrement(): Long = value.getAndIncrement()

    override fun extractState(): Any = value.value
}

class AtomicLongWrongTest : AbstractLinCheckTest(expectedError = ErrorType.INCORRECT_RESULTS) {
    private var value = atomic(0L)

    @Operation
    fun set(key: Long) {
        // twice is more reliable
        value.value = key
        value.value = key
    }

    @Operation
    fun get(): Long = value.value

    @Operation
    fun getAndIncrement(): Long = value.getAndIncrement()

    override fun extractState(): Any = value.value
}