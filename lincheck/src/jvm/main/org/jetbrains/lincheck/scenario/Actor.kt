/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck.scenario

import org.jetbrains.lincheck.util.ExceptionResult
import org.jetbrains.lincheck.util.LincheckResult
import org.jetbrains.lincheck.util.VoidResult
import org.jetbrains.lincheck.util.createLincheckResult
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.jvm.agent.toCanonicalClassName
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.trace.createAndRegisterMethodDescriptor
import org.jetbrains.lincheck.util.isLincheckInternalException
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import org.objectweb.asm.commons.Method.getMethod as getAsmMethod
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.collections.set
import kotlin.coroutines.*
import kotlin.reflect.jvm.*

/**
 * The actor entity describe the operation or validation function with its parameters
 * which is executed during the testing.
 *
 * @see Operation
 */
data class Actor @JvmOverloads constructor(
    val method: Method,
    val arguments: List<Any?>,
    val cancelOnSuspension: Boolean = false,
    val blocking: Boolean = false,
    val causesBlocking: Boolean = false,
    val promptCancellation: Boolean = false,
    // we have to specify `isSuspendable` property explicitly for transformed classes since
    // `isSuspendable` implementation produces a circular dependency and, therefore, fails.
    val isSuspendable: Boolean = method.isSuspendable()
) {
    init {
        if (promptCancellation) require(cancelOnSuspension) {
            "`promptCancellation` cannot be set to `true` if `cancelOnSuspension` is `false`"
        }
    }

    override fun toString() = method.name +
        arguments.joinToString(prefix = "(", postfix = ")", separator = ", ") { it.toString() } +
        (if (cancelOnSuspension) " + " else "") +
        (if (promptCancellation) "prompt_" else "") +
        (if (cancelOnSuspension) "cancel" else "")
}

fun Method.isSuspendable(): Boolean {
    val paramTypes = parameterTypes
    if (paramTypes.isEmpty()) return false
    if (paramTypes.last() != Continuation::class.java) return false
    return kotlinFunction?.isSuspend ?: false
}

fun TraceContext.getActorMethodId(actor: Actor): Int {
    val methodDescriptor = getAsmMethod(actor.method).descriptor
    return this.createAndRegisterMethodDescriptor(
        className = actor.method.declaringClass.name.toCanonicalClassName(),
        methodName = actor.method.name,
        methodType = Types.convertAsmMethodType(methodDescriptor)
    ).id
}

/**
 * Executes the specified actor on the sequential specification instance and returns its result.
 */
internal fun executeActor(
    instance: Any,
    actor: Actor,
    completion: Continuation<Any?>?
): LincheckResult {
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
        return ExceptionResult(
            invE.cause?.takeIf { !isLincheckInternalException(it) }
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

inline fun <R> Throwable.catch(vararg exceptions: Class<*>, block: () -> R): R {
    if (exceptions.any { this::class.java.isAssignableFrom(it) }) {
        return block()
    } else throw this
}

private val methodsCache = WeakHashMap<Class<*>, WeakHashMap<Method, WeakReference<Method>>>()