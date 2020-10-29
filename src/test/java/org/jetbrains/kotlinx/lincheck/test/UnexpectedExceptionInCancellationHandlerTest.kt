package org.jetbrains.kotlinx.lincheck.test

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.*

class UnexpectedExceptionInCancellationHandlerTest: AbstractLincheckTest(UnexpectedExceptionFailure::class) {
    @Operation
    suspend fun foo() {
        suspendCancellableCoroutine<Unit> {
            throw AssertionError("This exception is unexpected")
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
        threads(1)
        actorsPerThread(1)
        requireStateEquivalenceImplCheck(false)
        sequentialSpecification(UnexpectedExceptionInCancellationHandlerTestSequential::class.java)
    }
}

class UnexpectedExceptionInCancellationHandlerTestSequential() {
    suspend fun foo() {
        suspendCancellableCoroutine<Unit> {}
    }
}