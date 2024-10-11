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
import sun.nio.ch.lincheck.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.transformation.LincheckClassFileTransformer
import org.jetbrains.kotlinx.lincheck.util.UnsafeHolder
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.jetbrains.kotlinx.lincheck.util.*
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.*
import java.lang.reflect.*
import java.lang.reflect.Method
import java.util.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

fun <T> List<T>.isSuffixOf(list: List<T>): Boolean {
    if (size > list.size) return false
    for (i in indices) {
       if (this[size - i - 1] != list[list.size - i - 1]) return false
    }
    return true
}

fun chooseSequentialSpecification(sequentialSpecificationByUser: Class<*>?, testClass: Class<*>): Class<*> =
    if (sequentialSpecificationByUser === DummySequentialSpecification::class.java || sequentialSpecificationByUser == null) testClass
    else sequentialSpecificationByUser

/**
 * Executes the specified actor on the sequential specification instance and returns its result.
 */
internal fun executeActor(
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

private val methodsCache = WeakHashMap<Class<*>, WeakHashMap<Method, WeakReference<Method>>>()

/**
 * Get the same [method] for [instance] solving the different class loaders problem.
 */
@Synchronized
internal fun getMethod(instance: Any, method: Method): Method {
    val methods = methodsCache.computeIfAbsent(instance.javaClass) { WeakHashMap() }
    return methods[method]?.get() ?: run {
        val m = instance.javaClass.getMethod(method.name, method.parameterTypes)
        methods[method] = WeakReference(m)
        m
    }
}

/**
 * Finds a method with the specified [name] and (parameters)[parameterTypes]
 * ignoring the difference in class loaders for these parameter types.
 */
private fun Class<out Any>.getMethod(name: String, parameterTypes: Array<Class<out Any>>): Method =
    methods.find { method ->
        method.name == name && method.parameterTypes.map { it.name } == parameterTypes.map { it.name }
    } ?: throw NoSuchMethodException("${getName()}.$name(${parameterTypes.joinToString(",")})")

/**
 * @return hashcode of the unboxed value if [value] represents a boxed primitive, otherwise returns [System.identityHashCode]
 * of the [value].
 */
internal fun primitiveOrIdentityHashCode(value: Any?): Int {
    return if (value.isPrimitiveWrapper) value.hashCode() else System.identityHashCode(value)
}

internal val Any?.isPrimitiveWrapper get() = when (this) {
    is Boolean, is Int, is Short, is Long, is Double, is Float, is Char, is Byte -> true
    else -> false
}

/**
 * Creates [Result] of corresponding type from any given value.
 *
 * Java [Void] and Kotlin [Unit] classes are represented as [VoidResult].
 *
 * Instances of [Throwable] are represented as [ExceptionResult].
 *
 * The special [COROUTINE_SUSPENDED] value returned when some coroutine suspended its execution
 * is represented as [NoResult].
 *
 * Success values of [kotlin.Result] instances are represented as either [VoidResult] or [ValueResult].
 * Failure values of [kotlin.Result] instances are represented as [ExceptionResult].
 */
internal fun createLincheckResult(res: Any?) = when {
    (res != null && res.javaClass.isAssignableFrom(Void.TYPE)) || res is Unit -> VoidResult
    res != null && res is Throwable -> ExceptionResult.create(res)
    res === COROUTINE_SUSPENDED -> Suspended
    res is kotlin.Result<Any?> -> res.toLinCheckResult()
    else -> ValueResult(res)
}

private fun kotlin.Result<Any?>.toLinCheckResult() =
    if (isSuccess) {
        when (val value = getOrNull()) {
            is Unit -> VoidResult
            else -> ValueResult(value)
        }
    } else ExceptionResult.create(exceptionOrNull()!!)

inline fun <R> Throwable.catch(vararg exceptions: Class<*>, block: () -> R): R {
    if (exceptions.any { this::class.java.isAssignableFrom(it) }) {
        return block()
    } else throw this
}

internal class StoreExceptionHandler :
    AbstractCoroutineContextElement(CoroutineExceptionHandler),
    CoroutineExceptionHandler
{
    var exception: Throwable? = null

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        this.exception = exception
    }
}

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
internal fun <T> CancellableContinuation<T>.cancelByLincheck(promptCancellation: Boolean): CancellationResult {
    val exceptionHandler = context[CoroutineExceptionHandler] as StoreExceptionHandler
    exceptionHandler.exception = null
    val cancelled = LincheckTracker.getEventTracker().runOutsideIgnoredSection {
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
fun <T> kotlin.Result<T>.cancelledByLincheck() = exceptionOrNull() === cancellationByLincheckException

private val cancellationByLincheckException = Exception("Cancelled by lincheck")

/**
 * Collects the current thread dump and keeps only those
 * threads that are related to the specified [runner].
 */
internal fun collectThreadDump(runner: Runner) = Thread.getAllStackTraces().filter { (t, _) ->
    t is TestThread && runner.isCurrentRunnerThread(t)
}

internal val String.canonicalClassName get() = this.replace('/', '.')

@Suppress("DEPRECATION") // ThreadDeath
internal fun exceptionCanBeValidExecutionResult(exception: Throwable): Boolean {
    return exception !is ThreadDeath && // is used to stop thread in `FixedActiveThreadsExecutor` via `thread.stop()`
           exception !is ThreadAbortedError // is used to abort thread in `ManagedStrategy`
}

internal val Throwable.text: String get() {
    val writer = StringWriter()
    printStackTrace(PrintWriter(writer))
    return writer.buffer.toString()
}


@Suppress("DEPRECATION")
internal fun findFieldNameByOffset(targetType: Class<*>, offset: Long): String? {
    // Extract the private offset value and find the matching field.
    for (field in targetType.declaredFields) {
        try {
            if (Modifier.isNative(field.modifiers)) continue
            val fieldOffset = if (Modifier.isStatic(field.modifiers)) UnsafeHolder.UNSAFE.staticFieldOffset(field)
            else UnsafeHolder.UNSAFE.objectFieldOffset(field)
            if (fieldOffset == offset) return field.name
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
    return null // Field not found
}

/**
 * Thrown in case when `cause` exception is unexpected by Lincheck internal logic.
 */
internal class LincheckInternalBugException(cause: Throwable): Exception(cause)

// We use receivers for `runInIgnoredSection` to not use these functions
// accidentally instead of `invokeInIgnoredSection` in the transformation logic.

@Suppress("UnusedReceiverParameter")
internal inline fun<R> FixedActiveThreadsExecutor.runInIgnoredSection(block: () -> R): R =
    LincheckTracker.getEventTracker().runInIgnoredSection(block)

@Suppress("UnusedReceiverParameter")
internal inline fun<R> ParallelThreadsRunner.runInIgnoredSection(block: () -> R): R =
    LincheckTracker.getEventTracker().runInIgnoredSection(block)

@Suppress("UnusedReceiverParameter")
internal inline fun<R> LincheckClassFileTransformer.runInIgnoredSection(block: () -> R): R =
    LincheckTracker.getEventTracker().runInIgnoredSection(block)

@Suppress("UnusedReceiverParameter")
internal inline fun<R> ExecutionClassLoader.runInIgnoredSection(block: () -> R): R =
    LincheckTracker.getEventTracker().runInIgnoredSection(block)

internal inline fun <R> EventTracker?.runInIgnoredSection(block: () -> R): R {
    @Suppress("UNUSED_VARIABLE")
    val strategy: ManagedStrategy = this as? ManagedStrategy
        ?: return block()
    val flags = Injections.threadFlags.get()
    if (flags.inIgnoredSection()) {
        return block()
    }
    flags.enterIgnoredSection().ensureTrue()
    return try {
        block()
    } finally {
        flags.leaveIgnoredSection()
    }
}

@Suppress("UnusedReceiverParameter")
internal inline fun <R> ParallelThreadsRunner.runOutsideIgnoredSection(block: () -> R) =
    LincheckTracker.getEventTracker().runOutsideIgnoredSection(block)

/**
 * Exits the ignored section and invokes the provided [block] outside the ignored section,
 * entering the ignored section back after the [block] is executed.
 * This method **must** be called in an ignored section.
 */
internal inline fun <R> EventTracker?.runOutsideIgnoredSection(block: () -> R): R {
    @Suppress("UNUSED_VARIABLE")
    val strategy: ManagedStrategy = this as? ManagedStrategy
        ?: return block()
    val flags = Injections.threadFlags.get()
    check(flags.inIgnoredSection()) {
        "Current thread must be in ignored section"
    }
    flags.leaveIgnoredSection()
    return try {
        block()
    } finally {
        flags.enterIgnoredSection().ensureTrue()
    }
}

internal const val LINCHECK_PACKAGE_NAME = "org.jetbrains.kotlinx.lincheck."