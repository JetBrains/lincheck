/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.ForcibleExecutionFinishException
import org.jetbrains.kotlinx.lincheck.verifier.*
import sun.nio.ch.lincheck.*
import java.lang.reflect.*
import java.lang.reflect.Method
import kotlin.collections.HashMap
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

fun chooseSequentialSpecification(sequentialSpecificationByUser: Class<*>?, testClass: Class<*>): Class<*> =
    if (sequentialSpecificationByUser === DummySequentialSpecification::class.java || sequentialSpecificationByUser == null) testClass
    else sequentialSpecificationByUser

 fun executeActor(testInstance: Any, actor: Actor) = executeActor(testInstance, actor, null)

/**
 * Executes the specified actor on the sequential specification instance and returns its result.
 */
 fun executeActor(
    instance: Any,
    actor: Actor,
    completion: Continuation<Any?>?
): Result {
    try {
        val m = getMethod(instance, actor.method)
        val args = (if (actor.isSuspendable) actor.arguments + completion else actor.arguments)
        val res = m.invoke(instance, *args.toTypedArray())
        return if (m.returnType.isAssignableFrom(Void.TYPE)) VoidResult else createLincheckResult(res)
    } catch (invE: Throwable) {
        // If the exception is thrown not during the method invocation - fail immediately
        if (invE !is InvocationTargetException)
            throw invE
        // Exception thrown not during the method invocation should contain underlying exception
        return ExceptionResult.create(
            invE.cause?.takeIf { exceptionCanBeValidExecutionResult(it) }
                ?: throw invE
        )
    } catch (e: Exception) {
        e.catch(
            NoSuchMethodException::class.java,
            IllegalAccessException::class.java
        ) {
            throw IllegalStateException("Cannot invoke method " + actor.method, e)
        }
    }
}

internal inline fun executeValidationFunctions(instance: Any, validationFunctions: List<Method>,
                                               onError: (functionName: String, exception: Throwable) -> Unit) {
    for (f in validationFunctions) {
        val validationException = executeValidationFunction(instance, f)
        if (validationException != null) {
            onError(f.name, validationException)
            return
        }
    }
}

private fun executeValidationFunction(instance: Any, validationFunction: Method): Throwable? {
    try {
        validationFunction.invoke(instance)
    } catch (e: Exception) { // We don't catch any Errors - the only correct way is to re-throw them
        // There are some exception types that can be thrown from this method:
        return when (e) {
            // It's our fault if we supplied null instead of method or instance
            is NullPointerException -> LincheckInternalBugException(e)
            // It's our fault as it can appear if this validation function has parameters, but we had to check it before
            is IllegalArgumentException -> LincheckInternalBugException(e)
            // Something wrong with access to some classes, just report it
            is IllegalAccessException -> e
            // Regular validation function exception
            is InvocationTargetException -> {
                val validationException = e.targetException
                val wrapperExceptionStackTraceLength = e.stackTrace.size
                // drop stacktrace related to Lincheck call, keeping only stacktrace starting from validation function call
                validationException.stackTrace = validationException.stackTrace.dropLast(wrapperExceptionStackTraceLength).toTypedArray()
                validationException
            }
            else -> LincheckInternalBugException(e)
        }
    }
    return null
}

private val methodsCache = HashMap<Class<*>, HashMap<Method, Method>>()

/**
 * Get the same [method] for [instance] solving the different class loaders problem.
 */
@Synchronized
 fun getMethod(instance: Any, method: Method) = runInIgnoredSection {
    methodsCache.computeIfAbsent(instance.javaClass) { HashMap() }
        .computeIfAbsent(method) { instance.javaClass.getMethod(method.name, method.parameterTypes) }
}

/**
 * Finds a method withe the specified [name] and (parameters)[parameterTypes]
 * ignoring the difference in class loaders for these parameter types.
 */
private fun Class<out Any>.getMethod(name: String, parameterTypes: Array<Class<out Any>>): Method =
    methods.find { method ->
        method.name == name && method.parameterTypes.map { it.name } == parameterTypes.map { it.name }
    } ?: throw NoSuchMethodException("${getName()}.$name(${parameterTypes.joinToString(",")})")

/**
 * Creates [Result] of corresponding type from any given value.
 *
 * Java [Void] and Kotlin [Unit] classes are represented as [VoidResult].
 *
 * Instances of [Throwable] are represented as [ExceptionResult].
 *
 * The special [COROUTINE_SUSPENDED] value returned when some coroutine suspended it's execution
 * is represented as [NoResult].
 *
 * Success values of [kotlin.Result] instances are represented as either [VoidResult] or [ValueResult].
 * Failure values of [kotlin.Result] instances are represented as [ExceptionResult].
 */
 fun createLincheckResult(res: Any?, wasSuspended: Boolean = false) = when {
    (res != null && res.javaClass.isAssignableFrom(Void.TYPE)) || res is Unit -> if (wasSuspended) SuspendedVoidResult else VoidResult
    res != null && res is Throwable -> ExceptionResult.create(res, wasSuspended)
    res === COROUTINE_SUSPENDED -> Suspended
    res is kotlin.Result<Any?> -> res.toLinCheckResult(wasSuspended)
    else -> ValueResult(res, wasSuspended)
}

private fun kotlin.Result<Any?>.toLinCheckResult(wasSuspended: Boolean) =
    if (isSuccess) {
        when (val value = getOrNull()) {
            is Unit -> if (wasSuspended) SuspendedVoidResult else VoidResult
            // Throwable was returned as a successful result
            is Throwable -> ValueResult(value::class.java, wasSuspended)
            else -> ValueResult(value, wasSuspended)
        }
    } else ExceptionResult.create(exceptionOrNull()!!, wasSuspended)

inline fun <R> Throwable.catch(vararg exceptions: Class<*>, block: () -> R): R {
    if (exceptions.any { this::class.java.isAssignableFrom(it) }) {
        return block()
    } else throw this
}

 class StoreExceptionHandler :
    AbstractCoroutineContextElement(CoroutineExceptionHandler),
    CoroutineExceptionHandler
{
    var exception: Throwable? = null

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        this.exception = exception
    }
}

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
 fun <T> CancellableContinuation<T>.cancelByLincheck(promptCancellation: Boolean): CancellationResult {
    val exceptionHandler = context[CoroutineExceptionHandler] as StoreExceptionHandler
    exceptionHandler.exception = null

    val currentThread = Thread.currentThread() as? TestThread
    val inIgnoredSection = currentThread?.inIgnoredSection ?: false
    currentThread?.inIgnoredSection = false
    val cancelled = try {
        cancel(cancellationByLincheckException)
    } finally {
        currentThread?.inIgnoredSection = inIgnoredSection
    }

    exceptionHandler.exception?.let {
        throw it.cause!! // let's throw the original exception, ignoring the  coroutines details
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

 enum class CancellationResult { CANCELLED_BEFORE_RESUMPTION, CANCELLED_AFTER_RESUMPTION, CANCELLATION_FAILED }

/**
 * Returns `true` if the continuation was cancelled by [CancellableContinuation.cancel].
 */
fun <T> kotlin.Result<T>.cancelledByLincheck() = exceptionOrNull() === cancellationByLincheckException

private val cancellationByLincheckException = Exception("Cancelled by lincheck")

/**
 * Collects the current thread dump and keeps only those
 * threads that are related to the specified [runner].
 */
 fun collectThreadDump(runner: Runner) = Thread.getAllStackTraces().filter { (t, _) ->
    t in (runner as ParallelThreadsRunner).executor.threads
}

 inline fun <R> runInIgnoredSection(block: () -> R): R =
    runInIgnoredSection(Thread.currentThread(), block)

 inline fun <R> runInIgnoredSection(currentThread: Thread, block: () -> R): R =
    if (currentThread is TestThread && !currentThread.inIgnoredSection) {
        currentThread.inIgnoredSection = true
        try {
            block()
        } finally {
            currentThread.inIgnoredSection = false
        }
    } else {
        block()
    }

 fun exceptionCanBeValidExecutionResult(exception: Throwable): Boolean {
    return exception !is ThreadDeath && // used to stop thread in FixedActiveThreadsExecutor by calling thread.stop method
            exception !is InternalLincheckTestUnexpectedException &&
            exception !is ForcibleExecutionFinishException
}

/**
 * Utility exception for test purposes.
 * When this exception is thrown by an operation, it will halt testing with [UnexpectedExceptionInvocationResult].
 */
@Suppress("JavaIoSerializableObjectMustHaveReadResolve")
 object InternalLincheckTestUnexpectedException : Exception()

/**
 * Thrown in case when `cause` exception is unexpected by Lincheck  logic.
 */
 class LincheckInternalBugException(cause: Throwable): Exception(cause)

 const val LINCHECK_PACKAGE_NAME = "org.jetbrains.kotlinx.lincheck."
