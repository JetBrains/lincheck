package org.jetbrains.lincheck_test.project

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import kotlin.test.Test

class CoroutineCancellationTraceReportingTest {
    @Volatile
    var correct = true

    @InternalCoroutinesApi
    @Operation(cancellableOnSuspension = true)
    suspend fun cancelledOp() {
        suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation {
                correct = false
            }
        }
    }

    @Operation
    fun isAbsurd(): Boolean = correct && !correct


    @Test
    fun test() = ModelCheckingOptions().apply {
        actorsPerThread(1)
        actorsBefore(0)
        actorsAfter(0)
    }.check(this::class.java)

}