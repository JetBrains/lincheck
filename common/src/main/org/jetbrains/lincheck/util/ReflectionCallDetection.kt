/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandleInfo
import java.lang.invoke.MethodHandles
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaMethod

/**
 * Data class representing information about a reflected method call.
 * Used when a method is invoked via reflection APIs like Method.invoke(), MethodHandle.invoke(), or KFunction.call().
 */
data class ReflectedCallData(
    val owner: Any?,
    val className: String,
    val methodName: String,
    val methodParams: List<Any?>,
)

/**
 * Detects if a method call is a reflection-like API call and extracts information about the target method.
 *
 * Supports:
 * - java.lang.reflect.Method.invoke()
 * - java.lang.reflect.Constructor.newInstance()
 * - java.lang.invoke.MethodHandle.invoke/invokeExact/invokeWithArguments()
 * - kotlin.reflect.KFunction.call/callBy()
 *
 * @param owner The receiver object of the reflection call (e.g., the Method, Constructor, or MethodHandle instance)
 * @param className The class name where the reflection method is defined
 * @param methodName The name of the reflection method being called
 * @param methodParams The parameters passed to the reflection method
 * @return ReflectedCallData containing information about the target method, or null if not a reflection call
 */
fun detectReflectedCall(
    owner: Any?,
    className: String,
    methodName: String,
    methodParams: Array<Any?>
): ReflectedCallData? {
    return when {
        className == "java.lang.reflect.Method" && methodName == "invoke" && owner is Method ->
            resolveReflectionMethodInvoke(owner, methodParams)
        className == "java.lang.reflect.Constructor" && methodName == "newInstance" && owner is Constructor<*> ->
            resolveReflectionConstructorInvoke(owner, methodParams)
        className == "java.lang.invoke.MethodHandle" && isMethodHandleInvoke(methodName) && owner is MethodHandle ->
            resolveMethodHandleInvoke(owner, methodName, methodParams)
        className.startsWith("kotlin.reflect.") && methodName == "call" && owner is KFunction<*> ->
            resolveKotlinReflectionInvoke(owner, methodParams) { _, methodParams -> unpackVarArgs(methodParams.firstOrNull()) }
        className.startsWith("kotlin.reflect.") && methodName == "callBy" && owner is KFunction<*> ->
            resolveKotlinReflectionInvoke(owner, methodParams, ::extractOrderedArgsFromKotlinReflectCallBy)
        else -> null
    }
}

private fun resolveReflectionMethodInvoke(
    method: Method,
    methodParams: Array<Any?>
): ReflectedCallData {
    val targetOwner = methodParams.getOrNull(0)
    val targetArgs = unpackVarArgs(methodParams.getOrNull(1))
    return ReflectedCallData(
        owner = targetOwner,
        className = method.declaringClass.name,
        methodName = method.name,
        methodParams = targetArgs,
    )
}

private fun resolveReflectionConstructorInvoke(
    constructor: Constructor<*>,
    methodParams: Array<Any?>
): ReflectedCallData {
    val targetArgs = unpackVarArgs(methodParams.getOrNull(0))
    return ReflectedCallData(
        owner = null,
        className = constructor.declaringClass.name,
        methodName = "<init>",
        methodParams = targetArgs
    )
}

private fun resolveMethodHandleInvoke(
    handle: MethodHandle,
    methodName: String,
    methodParams: Array<Any?>
): ReflectedCallData? {
    val info = try {
        MethodHandles.lookup().revealDirect(handle)
    } catch (_: Throwable) {
        return null
    }
    val actualParams = when (methodName) {
        "invokeWithArguments" -> unpackVarArgs(methodParams.getOrNull(0))
        else -> methodParams.asList()
    }
    return when (info.referenceKind) {
        MethodHandleInfo.REF_invokeVirtual,
        MethodHandleInfo.REF_invokeInterface,
        MethodHandleInfo.REF_invokeSpecial -> ReflectedCallData(
            owner = actualParams.getOrNull(0),
            className = info.declaringClass.name,
            methodName = info.name,
            methodParams = actualParams.drop(1)
        )
        MethodHandleInfo.REF_invokeStatic -> ReflectedCallData(
            owner = null,
            className = info.declaringClass.name,
            methodName = info.name,
            methodParams = actualParams,
        )
        MethodHandleInfo.REF_newInvokeSpecial -> ReflectedCallData(
            owner = null,
            className = info.declaringClass.name,
            methodName = "<init>",
            methodParams = actualParams
        )
        else -> null
    }
}

private fun resolveKotlinReflectionInvoke(
    function: KFunction<*>,
    methodParams: Array<Any?>,
    argumentExtractor: (KFunction<*>, Array<Any?>) -> List<Any?>
): ReflectedCallData? {
    function.javaMethod?.let { javaMethod ->
        val isStatic = Modifier.isStatic(javaMethod.modifiers)
        val orderedArgs = argumentExtractor(function, methodParams)
        val (owner, params) = if (isStatic) {
            null to orderedArgs
        } else {
            val receiver = orderedArgs.getOrNull(0) ?: return null
            receiver to orderedArgs.drop(1)
        }
        return ReflectedCallData(
            owner = owner,
            className = javaMethod.declaringClass.name,
            methodName = javaMethod.name,
            methodParams = params,
        )
    }
    function.javaConstructor?.let { constructor ->
        val orderedArgs = argumentExtractor(function, methodParams)
        return ReflectedCallData(
            owner = null,
            className = constructor.declaringClass.name,
            methodName = "<init>",
            methodParams = orderedArgs
        )
    }
    return null
}

private fun isMethodHandleInvoke(methodName: String): Boolean =
    methodName == "invoke" || methodName == "invokeExact" || methodName == "invokeWithArguments"

private fun extractOrderedArgsFromKotlinReflectCallBy(function: KFunction<*>, methodParams: Array<Any?>): List<Any?> {
    val argsByParam = methodParams.singleOrNull() as? Map<*, *> ?: return methodParams.toList()
    return function.parameters.map { argsByParam[it] }
}

private fun unpackVarArgs(args: Any?): List<Any?> = when (args) {
    is Array<*> -> args.asList()
    is List<*> -> args
    null -> emptyList()
    else -> error("Unexpected argument type: ${args::class.java.name}")
}
