/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import kotlin.coroutines.*
import kotlin.jvm.*
import kotlin.reflect.*

expect class TestClass {
    fun createInstance(): Any
}

expect class SequentialSpecification<T>

expect fun <T : Any> SequentialSpecification<T>.getInitialState(): T

expect fun chooseSequentialSpecification(sequentialSpecificationByUser: SequentialSpecification<*>?, testClass: TestClass): SequentialSpecification<*>

//@Volatile
internal var storedLastCancellableCont: CancellableContinuation<*>? = null

expect fun storeCancellableContinuation(cont: CancellableContinuation<*>)

internal fun executeActor(testInstance: Any, actor: Actor) = executeActor(testInstance, actor, null)
internal expect fun executeActor(
    instance: Any,
    actor: Actor,
    completion: Continuation<Any?>?
): Result

internal expect fun createLincheckResult(res: Any?, wasSuspended: Boolean = false): Result

expect fun loadSequentialSpecification(sequentialSpecification: SequentialSpecification<*>): SequentialSpecification<out Any>

internal expect fun executeValidationFunction(instance: Any, validationFunction: ValidationFunction): Throwable?
internal inline fun executeValidationFunctions(instance: Any, validationFunctions: List<ValidationFunction>,
                                               onError: (functionName: String, exception: Throwable) -> Unit) {
    for (f in validationFunctions) {
        val validationException = executeValidationFunction(instance, f)
        if (validationException != null) {
            onError(f.name, validationException)
            return
        }
    }
}

/**
 * loader class type should be ClassLoader in jvm
 */
internal expect fun ExecutionScenario.convertForLoader(loader: Any): ExecutionScenario

/**
 * Returns `true` if the continuation was cancelled by [CancellableContinuation.cancel].
 */
fun <T> kotlin.Result<T>.cancelledByLincheck() = exceptionOrNull() === cancellationByLincheckException

private val cancellationByLincheckException = Exception("Cancelled by lincheck")

internal enum class CancellationResult { CANCELLED_BEFORE_RESUMPTION, CANCELLED_AFTER_RESUMPTION, CANCELLATION_FAILED }

internal class StoreExceptionHandler : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {
    var exception: Throwable? = null

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        this.exception = exception
    }
}

class LincheckAssertionError(
    failure: LincheckFailure
) : AssertionError("\n" + failure)

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
internal fun <T> CancellableContinuation<T>.cancelByLincheck(promptCancellation: Boolean): CancellationResult {
    val exceptionHandler = context[CoroutineExceptionHandler] as StoreExceptionHandler
    exceptionHandler.exception = null
    val cancelled = cancel(cancellationByLincheckException)
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

/**
 * Returns scenario for the specified thread. Note that initial and post parts
 * are represented as threads with ids `0` and `threads + 1` respectively.
 */
internal operator fun ExecutionScenario.get(threadId: Int): List<Actor> = when (threadId) {
    0 -> initExecution
    threads + 1 -> postExecution
    else -> parallelExecution[threadId - 1]
}

/**
 * Returns results for the specified thread. Note that initial and post parts
 * are represented as threads with ids `0` and `threads + 1` respectively.
 */
internal operator fun ExecutionResult.get(threadId: Int): List<Result> = when (threadId) {
    0 -> initResults
    parallelResultsWithClock.size + 1 -> postResults
    else -> parallelResultsWithClock[threadId - 1].map { it.result }
}

fun wrapInvalidAccessFromUnnamedModuleExceptionWithDescription(e: Throwable): Throwable {
    if (e.message?.contains("to unnamed module") ?: false) {
        return RuntimeException(ADD_OPENS_MESSAGE, e)
    }
    return e
}

private val ADD_OPENS_MESSAGE = "It seems that you use Java 9+ and the code uses Unsafe or similar constructions that are not accessible from unnamed modules.\n" +
    "Please add the following lines to your test running configuration:\n" +
    "--add-opens java.base/jdk.internal.misc=ALL-UNNAMED\n" +
    "--add-exports java.base/jdk.internal.util=ALL-UNNAMED"

internal val String.canonicalClassName get() = this.replace('/', '.')
internal val String.internalClassName get() = this.replace('.', '/')

internal interface Finalizable {
    fun finalize()
}

/**
 * Collects the current thread dump and keeps only those
 * threads that are related to the specified [runner].
 */
internal expect fun collectThreadDump(runner: Runner): ThreadDump

internal expect fun nativeFreeze(any: Any)

class LincheckAtomicArray<T>(size: Int) {
    val array = atomicArrayOfNulls<T>(size)
    init {
        nativeFreeze(this)
    }
}

class LincheckAtomicIntArray(size: Int) {
    val array = AtomicIntArray(size)
    init {
        nativeFreeze(this)
    }
}

fun LincheckAtomicIntArray.toArray(): IntArray = IntArray(this.array.size) { this.array[it].value }
fun IntArray.toLincheckAtomicIntArray(): LincheckAtomicIntArray {
    val ans = LincheckAtomicIntArray(this.size)
    for (i in this.indices) {
        ans.array[i].value = this[i]
    }
    return ans
}