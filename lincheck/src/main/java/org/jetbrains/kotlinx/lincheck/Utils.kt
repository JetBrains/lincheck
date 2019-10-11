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

import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.verifier.DummySequentialSpecification
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED


@Volatile
private var consumedCPU = System.currentTimeMillis().toInt()

fun consumeCPU(tokens: Int) {
    var t = consumedCPU // volatile read
    for (i in tokens downTo 1)
        t += (t * 0x5DEECE66DL + 0xBL + i.toLong() and 0xFFFFFFFFFFFFL).toInt()
    if (t == 42)
        consumedCPU += t
}

fun chooseSequentialSpecification(sequentialSpecificationByUser: Class<*>?, testClass: Class<*>): Class<*> =
    if (sequentialSpecificationByUser === DummySequentialSpecification::class.java || sequentialSpecificationByUser == null) testClass
    else sequentialSpecificationByUser

/**
 * Creates instance of cost counter class using the (int relaxationFactor) constructor.
 */
fun createCostCounterInstance(costCounter: Class<*>, relaxationFactor: Int): Any {
    try {
        return costCounter.getDeclaredConstructor(Int::class.javaPrimitiveType).newInstance(relaxationFactor)
    } catch (e: Exception) {
        e.catch(
            IllegalStateException::class.java,
            IllegalAccessException::class.java,
            NoSuchMethodException::class.java,
            InvocationTargetException::class.java
        ) {
            throw IllegalStateException("Cost counter class should have '(relaxationFactor: Int)' constructor", e)
        }
    }
}

internal fun executeActor(testInstance: Any, actor: Actor) = executeActor(testInstance, actor, null)

/**
 * Executes the specified actor on the sequential specification instance and returns its result.
 */
internal fun executeActor(
    specification: Any,
    actor: Actor,
    completion: Continuation<Any?>?
): Result {
    try {
        val m = getMethod(specification, actor)
        val args = if (actor.isSuspendable) actor.arguments + completion else actor.arguments
        val res = m.invoke(specification, *args.toTypedArray())
        return if (m.returnType.isAssignableFrom(Void.TYPE)) VoidResult else createLinCheckResult(res)
    } catch (invE: Throwable) {
        val eClass = (invE.cause ?: invE)::class.java
        for (ec in actor.handledExceptions) {
            if (ec.isAssignableFrom(eClass))
                return ExceptionResult(eClass)
        }
        throw IllegalStateException("Invalid exception as a result", invE)
    } catch (e: Exception) {
        e.catch(
            NoSuchMethodException::class.java,
            IllegalAccessException::class.java
        ) {
            throw IllegalStateException("Cannot invoke method " + actor.method, e)
        }
    }
}

private val methodsCache = WeakHashMap<Class<*>, HashMap<Method, Method>>()

private fun getMethod(instance: Any, actor: Actor): Method = methodsCache
    .computeIfAbsent(instance.javaClass) { hashMapOf() }
    .computeIfAbsent(actor.method) {
        instance.javaClass.getMethod(actor.method.name, *actor.method.parameterTypes)
    }

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
internal fun createLinCheckResult(res: Any?, wasSuspended: Boolean = false) = when {
    (res != null && res.javaClass.isAssignableFrom(Void.TYPE)) || res is Unit -> if (wasSuspended) SuspendedVoidResult else VoidResult
    res != null && res is Throwable -> ExceptionResult(res.javaClass, wasSuspended)
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
    } else ExceptionResult(exceptionOrNull()?.let { it::class.java }, wasSuspended)


fun <R> Throwable.catch(vararg exceptions: Class<*>, block: () -> R): R {
    if (exceptions.any { this::class.java.isAssignableFrom(it) }) {
        return block()
    } else throw this
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
    parallelResults.size + 1 -> postResults
    else -> parallelResults[threadId - 1]
}

internal operator fun ExecutionResult.set(threadId: Int, actorId: Int, value: Result) = when (threadId) {
    0 -> initResults[actorId] = value
    parallelResults.size + 1 -> postResults[actorId] = value
    else -> parallelResults[threadId - 1][actorId] = value
}
