package org.jetbrains.lincheck.util

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class StoreExceptionHandler :
    AbstractCoroutineContextElement(CoroutineExceptionHandler),
    CoroutineExceptionHandler
{
    var exception: Throwable? = null

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        this.exception = exception
    }
}

internal fun <T> CancellableContinuation<T>.cancelByLincheck(promptCancellation: Boolean): CancellationResult {
    val exceptionHandler = context[CoroutineExceptionHandler] as StoreExceptionHandler
    exceptionHandler.exception = null
    val cancelled = runOutsideIgnoredSection {
        cancel(cancellationByLincheckException)
    }
    exceptionHandler.exception?.let {
        throw it.cause!! // let's throw the original exception, ignoring the internal coroutines details
    }
    return when {
        cancelled -> CancellationResult.CANCELLED_BEFORE_RESUMPTION
        promptCancellation -> {
            context[Job]!!.cancel() // we should always put a job into the context for prompt cancellation
            CancellationResult.CANCELLED_AFTER_RESUMPTION
        }
        else -> CancellationResult.CANCELLATION_FAILED
    }
}

internal enum class CancellationResult { CANCELLED_BEFORE_RESUMPTION, CANCELLED_AFTER_RESUMPTION, CANCELLATION_FAILED }

/**
 * Returns `true` if the continuation was cancelled by [CancellableContinuation.cancel].
 */
fun <T> Result<T>.cancelledByLincheck() = exceptionOrNull() === cancellationByLincheckException

private val cancellationByLincheckException = Exception("Cancelled by lincheck")