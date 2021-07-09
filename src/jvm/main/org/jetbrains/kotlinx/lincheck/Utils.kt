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

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategyTransformer
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.objectweb.asm.*
import org.objectweb.asm.commons.*
import java.io.*
import java.lang.ref.*
import java.lang.reflect.Method
import java.util.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*
import kotlin.reflect.KClass
import kotlin.reflect.full.*
import kotlin.reflect.jvm.*

actual class TestClass(val clazz: Class<*>) {
    val name = clazz.name

    actual fun createInstance(): Any = clazz.getDeclaredConstructor().newInstance()
}

actual fun loadSequentialSpecification(sequentialSpecification: SequentialSpecification<*>): SequentialSpecification<out Any> =
    TransformationClassLoader { cv -> CancellabilitySupportClassTransformer(cv) }.loadClass(sequentialSpecification.name)!!

actual fun chooseSequentialSpecification(sequentialSpecificationByUser: SequentialSpecification<*>?, testClass: TestClass): SequentialSpecification<*> =
    if (sequentialSpecificationByUser === DummySequentialSpecification::class.java || sequentialSpecificationByUser == null) testClass.clazz
    else sequentialSpecificationByUser

/**
 * Executes the specified actor on the sequential specification instance and returns its result.
 */
internal actual fun executeActor(
    instance: Any,
    actor: Actor,
    completion: Continuation<Any?>?
): Result {
    try {
        val m = getMethod(instance, actor.method)
        val args = (if (actor.isSuspendable) actor.arguments + completion else actor.arguments)
            .map { it.convertForLoader(instance.javaClass.classLoader) }
        val res = m.invoke(instance, *args.toTypedArray())
        return if (m.returnType.isAssignableFrom(Void.TYPE)) VoidResult else createLincheckResult(res)
    } catch (invE: Throwable) {
        val eClass = (invE.cause ?: invE).javaClass.normalize()
        for (ec in actor.handledExceptions) {
            if (ec.java.isAssignableFrom(eClass))
                return createExceptionResult(eClass)
        }
        throw IllegalStateException("Invalid exception as a result of $actor", invE)
    } catch (e: Exception) {
        e.catch(
            NoSuchMethodException::class.java,
            IllegalAccessException::class.java
        ) {
            throw IllegalStateException("Cannot invoke method " + actor.method, e)
        }
    }
}

internal actual fun executeValidationFunction(instance: Any, validationFunction: ValidationFunction): Throwable? {
    val m = getMethod(instance, validationFunction)
    try {
        m.invoke(instance)
    } catch (e: Throwable) {
        return e
    }
    return null
}

internal fun <T> Class<T>.normalize() = LinChecker::class.java.classLoader.loadClass(name) as Class<T>

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
internal actual fun createLincheckResult(res: Any?, wasSuspended: Boolean) = when {
    (res != null && res.javaClass.isAssignableFrom(Void.TYPE)) || res is Unit -> if (wasSuspended) SuspendedVoidResult else VoidResult
    res != null && res is Throwable -> createExceptionResult(res.javaClass, wasSuspended)
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
    } else createExceptionResult(exceptionOrNull()!!.let { it::class.java }, wasSuspended)

inline fun <R> Throwable.catch(vararg exceptions: Class<*>, block: () -> R): R {
    if (exceptions.any { this::class.java.isAssignableFrom(it) }) {
        return block()
    } else throw this
}

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
private val cancelCompletedResultMethod = DispatchedTask::class.declaredFunctions.find { it.name ==  "cancelCompletedResult" }!!.javaMethod!!

actual fun storeCancellableContinuation(cont: CancellableContinuation<*>) {
    val t = Thread.currentThread()
    if (t is FixedActiveThreadsExecutor.TestThread) {
        t.cont = cont
    } else {
        storedLastCancellableCont = cont
    }
}

internal actual fun ExecutionScenario.convertForLoader(loader: Any) = ExecutionScenario(
    initExecution,
    parallelExecution.map { actors ->
        actors.map { a ->
            val args = a.arguments.map { it.convertForLoader(loader as ClassLoader) }
            // the original `isSuspendable` is used here since `KFunction.isSuspend` fails on transformed classes
            Actor(
                method = a.method.convertForLoader(loader as ClassLoader),
                arguments = args,
                handledExceptions = a.handledExceptions,
                cancelOnSuspension = a.cancelOnSuspension,
                allowExtraSuspension = a.allowExtraSuspension,
                blocking = a.blocking,
                causesBlocking = a.causesBlocking,
                promptCancellation = a.promptCancellation,
                isSuspendable = a.isSuspendable
            )
        }
    },
    postExecution
)

/**
 * Finds the same method but loaded by the specified (class loader)[loader],
 * the signature can be changed according to the [TransformationClassLoader]'s remapper.
 */
private fun Method.convertForLoader(loader: ClassLoader): Method {
    if (loader !is TransformationClassLoader) return this
    val clazz = declaringClass.convertForLoader(loader)
    val parameterTypes = parameterTypes.map { it.convertForLoader(loader) }
    return clazz.getDeclaredMethod(name, *parameterTypes.toTypedArray())
}

private fun Class<*>.convertForLoader(loader: TransformationClassLoader): Class<*> = if (isPrimitive) this else loader.loadClass(loader.remapClassName(name))

internal fun Any?.convertForLoader(loader: ClassLoader) = when {
    this == null -> this
    loader is TransformationClassLoader && !loader.shouldBeTransformed(this.javaClass) -> this
    this is Serializable -> serialize().run { deserialize(loader) }
    else -> error("The result class should either be always loaded by the system class loader and not be transformed," +
                  " or implement Serializable interface.")
}

internal fun Any?.serialize(): ByteArray = ByteArrayOutputStream().use {
    val oos = ObjectOutputStream(it)
    oos.writeObject(this)
    it.toByteArray()
}

internal fun ByteArray.deserialize(loader: ClassLoader) = ByteArrayInputStream(this).use {
    CustomObjectInputStream(loader, it).run { readObject() }
}

internal fun getClassFromKClass(clazz: KClass<out Throwable>) = clazz.java
internal fun getKClassFromClass(clazz: Class<out Throwable>) = clazz.kotlin

/**
 * ObjectInputStream that uses custom class loader.
 */
private class CustomObjectInputStream(val loader: ClassLoader, inputStream: InputStream) : ObjectInputStream(inputStream) {
    override fun resolveClass(desc: ObjectStreamClass): Class<*> {
        // add `TRANSFORMED_PACKAGE_NAME` prefix in case of TransformationClassLoader and remove otherwise
        val className = if (loader is TransformationClassLoader) loader.remapClassName(desc.name)
                        else desc.name.removePrefix(TransformationClassLoader.REMAPPED_PACKAGE_CANONICAL_NAME)
        return Class.forName(className, true, loader)
    }
}

/**
 * Collects the current thread dump and keeps only those
 * threads that are related to the specified [runner].
 */
internal actual fun collectThreadDump(runner: Runner) = ThreadDump(Thread.getAllStackTraces().filter { (t, _) ->
    t is FixedActiveThreadsExecutor.TestThread && t.runnerHash == runner.hashCode()
})

/**
 * This method helps to encapsulate remapper logic from strategy interface.
 * The remapper is determined based on the used transformers.
 */
internal fun getRemapperByTransformers(classTransformers: List<ClassVisitor>): Remapper? =
    when {
        classTransformers.any { it is ManagedStrategyTransformer } -> JavaUtilRemapper()
        else -> null
    }

internal actual fun nativeFreeze(any: Any) {}