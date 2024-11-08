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
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.transformation.LincheckClassFileTransformer
import org.jetbrains.kotlinx.lincheck.util.UnsafeHolder
import org.jetbrains.kotlinx.lincheck.util.isAtomicArray
import org.jetbrains.kotlinx.lincheck.util.isAtomicFUArray
import org.jetbrains.kotlinx.lincheck.util.isAtomicArray
import org.jetbrains.kotlinx.lincheck.util.isAtomicFUArray
import org.jetbrains.kotlinx.lincheck.util.isAtomicFieldUpdater
import org.jetbrains.kotlinx.lincheck.util.isUnsafeClass
import org.jetbrains.kotlinx.lincheck.util.readArrayElementViaUnsafe
import org.jetbrains.kotlinx.lincheck.util.readFieldViaUnsafe
import org.jetbrains.kotlinx.lincheck.verifier.*
import sun.nio.ch.lincheck.*
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.*
import java.lang.reflect.*
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*

// TODO: put this back to agent class
fun shouldTransformClass(className: String): Boolean {
    // We do not need to instrument most standard Java classes.
    // It is fine to inject the Lincheck analysis only into the
    // `java.util.*` ones, ignored the known atomic constructs.
    if (className.startsWith("java.")) {
        if (className.startsWith("java.util.concurrent.") && className.contains("Atomic")) return false
        if (className.startsWith("java.util.")) return true
        if (className.startsWith("com.sun.")) return false
        return false
    }
    if (className.startsWith("sun.")) return false
    if (className.startsWith("javax.")) return false
    if (className.startsWith("jdk.")) return false
    // We do not need to instrument most standard Kotlin classes.
    // However, we need to inject the Lincheck analysis into the classes
    // related to collections, iterators, random and coroutines.
    if (className.startsWith("kotlin.")) {
        if (className.startsWith("kotlin.collections.")) return true
        if (className.startsWith("kotlin.jvm.internal.Array") && className.contains("Iterator")) return true
        if (className.startsWith("kotlin.ranges.")) return true
        if (className.startsWith("kotlin.random.")) return true
        if (className.startsWith("kotlin.coroutines.jvm.internal.")) return false
        if (className.startsWith("kotlin.coroutines.")) return true
        return false
    }
    if (className.startsWith("kotlinx.atomicfu.")) return false
    // We need to skip the classes related to the debugger support in Kotlin coroutines.
    if (className.startsWith("kotlinx.coroutines.debug.")) return false
    if (className == "kotlinx.coroutines.DebugKt") return false
    // We should never transform the coverage-related classes.
    if (className.startsWith("com.intellij.rt.coverage.")) return false
    // We can also safely do not instrument some libraries for performance reasons.
    if (className.startsWith("com.esotericsoftware.kryo.")) return false
    if (className.startsWith("net.bytebuddy.")) return false
    if (className.startsWith("net.rubygrapefruit.platform.")) return false
    if (className.startsWith("io.mockk.")) return false
    if (className.startsWith("it.unimi.dsi.fastutil.")) return false
    if (className.startsWith("worker.org.gradle.")) return false
    if (className.startsWith("org.objectweb.asm.")) return false
    if (className.startsWith("org.gradle.")) return false
    if (className.startsWith("org.slf4j.")) return false
    if (className.startsWith("org.apache.commons.lang.")) return false
    if (className.startsWith("org.junit.")) return false
    if (className.startsWith("junit.framework.")) return false
    // Finally, we should never instrument the Lincheck classes.
    if (className.startsWith("org.jetbrains.kotlinx.lincheck.")) return false
    if (className.startsWith("sun.nio.ch.lincheck.")) return false
    // All the classes that were not filtered out are eligible for transformation.
    return true
}

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

    val currentThread = Thread.currentThread() as? TestThread
    val inIgnoredSection = currentThread?.inIgnoredSection ?: false
    // We must exit the ignored section here to analyze the cancellation handler logic.
    // After that, we need to enter the ignored section back.
    currentThread?.inIgnoredSection = false
    val cancelled = try {
        cancel(cancellationByLincheckException)
    } finally {
        currentThread?.inIgnoredSection = inIgnoredSection
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
           exception !is ForcibleExecutionFinishError // is used to abort thread in `ManagedStrategy`
}

internal val Throwable.text: String get() {
    val writer = StringWriter()
    printStackTrace(PrintWriter(writer))
    return writer.buffer.toString()
}

/**
 * Returns all found fields in the hierarchy.
 * Multiple fields with the same name and the same type may be returned
 * if they appear in the subclass and a parent class.
 */
internal val Class<*>.allDeclaredFieldWithSuperclasses get(): List<Field> {
    val fields: MutableList<Field> = ArrayList<Field>()
    var currentClass: Class<*>? = this
    while (currentClass != null) {
        val declaredFields: Array<Field> = currentClass.declaredFields
        fields.addAll(declaredFields)
        currentClass = currentClass.superclass
    }
    return fields
}

/**
 * Finds a public/protected/private/internal field in the class and its superclasses/interfaces by name.
 *
 * @param fieldName the name of the field to find.
 * @return the [java.lang.reflect.Field] object if found, or `null` if not found.
 */
fun Class<*>.findField(fieldName: String): Field {
    // Search in the class hierarchy
    var clazz: Class<*>? = this
    while (clazz != null) {
        // Check class itself
        try {
            return clazz.getDeclaredField(fieldName)
        }
        catch (_: NoSuchFieldException) {}

        // Check interfaces
        for (interfaceClass in clazz.interfaces) {
            try {
                return interfaceClass.getDeclaredField(fieldName)
            }
            catch (_: NoSuchFieldException) {}
        }

        // Move up the hierarchy
        clazz = clazz.superclass
    }

    throw NoSuchFieldException("Class '${this.name}' does not have field '$fieldName'")
}

@Suppress("DEPRECATION")
fun getFieldOffset(field: Field): Long {
    return if (Modifier.isStatic(field.modifiers)) {
        UnsafeHolder.UNSAFE.staticFieldOffset(field)
    }
    else {
        UnsafeHolder.UNSAFE.objectFieldOffset(field)
    }
}

internal fun getArrayElementOffset(arr: Any, index: Int): Long {
    val clazz = arr::class.java
    val baseOffset = UnsafeHolder.UNSAFE.arrayBaseOffset(clazz).toLong()
    val indexScale = UnsafeHolder.UNSAFE.arrayIndexScale(clazz).toLong()

    return baseOffset + index * indexScale
}

internal fun getArrayLength(arr: Any): Int {
    return when {
        arr is Array<*>     -> arr.size
        arr is IntArray     -> arr.size
        arr is DoubleArray  -> arr.size
        arr is FloatArray   -> arr.size
        arr is LongArray    -> arr.size
        arr is ShortArray   -> arr.size
        arr is ByteArray    -> arr.size
        arr is BooleanArray -> arr.size
        arr is CharArray    -> arr.size
        isAtomicArray(arr)  -> getAtomicArrayLength(arr)
        else -> error("Argument is not an array")
    }
}

internal fun getAtomicArrayLength(arr: Any): Int {
    return when {
        arr is AtomicReferenceArray<*> -> arr.length()
        arr is AtomicIntegerArray -> arr.length()
        arr is AtomicLongArray -> arr.length()
        isAtomicFUArray(arr) -> arr.javaClass.getMethod("getSize").invoke(arr) as Int
        else -> error("Argument is not atomic array")
    }
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

// We use receivers here in order not to use this function instead of `invokeInIgnoredSection` in the transformation logic.
@Suppress("UnusedReceiverParameter")
internal inline fun<R> EventTracker.runInIgnoredSection(block: () -> R): R =  runInIgnoredSection(Thread.currentThread(), block)
@Suppress("UnusedReceiverParameter")
internal inline fun<R> FixedActiveThreadsExecutor.runInIgnoredSection(block: () -> R): R =  runInIgnoredSection(Thread.currentThread(), block)
@Suppress("UnusedReceiverParameter")
internal inline fun<R> ParallelThreadsRunner.runInIgnoredSection(block: () -> R): R =  runInIgnoredSection(Thread.currentThread(), block)
@Suppress("UnusedReceiverParameter")
internal inline fun<R> LincheckClassFileTransformer.runInIgnoredSection(block: () -> R): R =  runInIgnoredSection(Thread.currentThread(), block)

@Suppress("UnusedReceiverParameter")
internal inline fun<R> ExecutionClassLoader.runInIgnoredSection(block: () -> R): R =  runInIgnoredSection(Thread.currentThread(), block)

private inline fun <R> runInIgnoredSection(currentThread: Thread, block: () -> R): R =
    if (currentThread is TestThread && currentThread.inTestingCode && !currentThread.inIgnoredSection) {
        currentThread.inIgnoredSection = true
        try {
            block()
        } finally {
            currentThread.inIgnoredSection = false
        }
    } else {
        block()
    }

/**
 * Exits the ignored section and invokes the provided [block] in the ignored section, setting
 * the [TestThread.inIgnoredSection] flag to `false` in the beginning and setting it back
 * in the end to `true`.
 * This method **must** be called in an ignored section.
 */
@Suppress("UnusedReceiverParameter")
internal inline fun <R> ParallelThreadsRunner.runOutsideIgnoredSection(currentThread: TestThread, block: () -> R): R {
    if (!currentThread.inTestingCode) {
        return block()
    }
    require(currentThread.inIgnoredSection) {
        "Current thread must be in ignored section"
    }
    currentThread.inIgnoredSection = false
    return try {
        block()
    } finally {
        currentThread.inIgnoredSection = true
    }
}

internal const val LINCHECK_PACKAGE_NAME = "org.jetbrains.kotlinx.lincheck."