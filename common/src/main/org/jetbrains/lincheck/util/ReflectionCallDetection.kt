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
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaMethod

/**
 * Describes how to extract the target method's owner and parameters from the reflection call's
 * receiver and parameters.
 *
 * Different reflection APIs have different conventions for passing the target method's receiver
 * and arguments. For example:
 * - Method.invoke(obj, args) - owner is first parameter, args is second parameter (vararg array)
 * - MethodHandle.invoke(obj, arg1, arg2, ...) - owner is first parameter, rest are args
 * - MethodHandle.invokeWithArguments(list) - all args packed in a list, owner is first element
 * - KFunction.call(vararg args) - for instance methods, owner is first element of vararg
 *
 * For KFunction.callBy, the Map is converted to an ordered array at detection time,
 * so extraction works the same as KFunction.call.
 */
enum class ReflectionCallKind {
    /** Method.invoke(obj, args) - owner is params[0], target params are unpacked from params[1] */
    METHOD_INVOKE,
    /** Constructor.newInstance(args) - no owner, target params are unpacked from params[0] */
    CONSTRUCTOR_NEW_INSTANCE,
    /** MethodHandle instance method - owner is params[0], target params are params.drop(1) */
    METHOD_HANDLE_INSTANCE,
    /** MethodHandle static method - no owner, target params are all params */
    METHOD_HANDLE_STATIC,
    /** MethodHandle constructor - no owner, target params are all params */
    METHOD_HANDLE_CONSTRUCTOR,
    /** MethodHandle.invokeWithArguments(list) instance - owner is list[0], target params are list.drop(1) */
    METHOD_HANDLE_WITH_ARGS_INSTANCE,
    /** MethodHandle.invokeWithArguments(list) static - no owner, target params are all list elements */
    METHOD_HANDLE_WITH_ARGS_STATIC,
    /** MethodHandle.invokeWithArguments(list) constructor - no owner, target params are all list elements */
    METHOD_HANDLE_WITH_ARGS_CONSTRUCTOR,
    /** KFunction.call/callBy(vararg) for instance method - owner is args[0], target params are args.drop(1) */
    KFUNCTION_CALL_INSTANCE,
    /** KFunction.call/callBy(vararg) for static method - no owner, target params are all args */
    KFUNCTION_CALL_STATIC,
    /** KFunction.call/callBy(vararg) for constructor - no owner, target params are all args */
    KFUNCTION_CALL_CONSTRUCTOR;

    /**
     * Extracts the target method's owner (receiver) from the reflection call's parameters.
     *
     * @param T the type of values (can be actual objects, TRValue, String representations, etc.)
     * @param params the parameters of the reflection call (not the target method), already resolved via resolveEffectiveParams
     * @param unpackVarArgs function to unpack a vararg/array/list parameter into a list
     * @return the owner/receiver of the target method, or null for static methods/constructors
     */
    fun <T> extractOwner(params: List<T>, unpackVarArgs: (T?) -> List<T>): T? = when (this) {
        METHOD_INVOKE -> params.getOrNull(0)
        CONSTRUCTOR_NEW_INSTANCE -> null
        METHOD_HANDLE_INSTANCE -> params.getOrNull(0)
        METHOD_HANDLE_STATIC -> null
        METHOD_HANDLE_CONSTRUCTOR -> null
        METHOD_HANDLE_WITH_ARGS_INSTANCE -> unpackVarArgs(params.getOrNull(0)).getOrNull(0)
        METHOD_HANDLE_WITH_ARGS_STATIC -> null
        METHOD_HANDLE_WITH_ARGS_CONSTRUCTOR -> null
        // For KFUNCTION_CALL_*, params are already unpacked by resolveEffectiveParameters
        KFUNCTION_CALL_INSTANCE -> params.getOrNull(0)
        KFUNCTION_CALL_STATIC -> null
        KFUNCTION_CALL_CONSTRUCTOR -> null
    }

    /**
     * Extracts the target method's parameters from the reflection call's parameters.
     *
     * @param T the type of values (can be actual objects, TRValue, String representations, etc.)
     * @param params the parameters of the reflection call (not the target method), already resolved via resolveEffectiveParams
     * @param unpackVarArgs function to unpack a vararg/array/list parameter into a list
     * @return the parameters of the target method
     */
    fun <T> extractParameters(params: List<T>, unpackVarArgs: (T?) -> List<T>): List<T> = when (this) {
        METHOD_INVOKE -> unpackVarArgs(params.getOrNull(1))
        CONSTRUCTOR_NEW_INSTANCE -> unpackVarArgs(params.getOrNull(0))
        METHOD_HANDLE_INSTANCE -> params.drop(1)
        METHOD_HANDLE_STATIC -> params
        METHOD_HANDLE_CONSTRUCTOR -> params
        METHOD_HANDLE_WITH_ARGS_INSTANCE -> unpackVarArgs(params.getOrNull(0)).drop(1)
        METHOD_HANDLE_WITH_ARGS_STATIC -> unpackVarArgs(params.getOrNull(0))
        METHOD_HANDLE_WITH_ARGS_CONSTRUCTOR -> unpackVarArgs(params.getOrNull(0))
        // For KFUNCTION_CALL_*, params are already unpacked by resolveEffectiveParameters
        KFUNCTION_CALL_INSTANCE -> params.drop(1)
        KFUNCTION_CALL_STATIC -> params
        KFUNCTION_CALL_CONSTRUCTOR -> params
    }
}

/**
 * Data class representing information about a reflected method call.
 * Used when a method is invoked via reflection APIs like Method.invoke(), MethodHandle.invoke(), or KFunction.call().
 */
data class ReflectedCallData(
    val className: String,
    val methodName: String,
    val callKind: ReflectionCallKind,
) {
    /**
     * Extracts the target method's owner from the reflection call's parameters (already resolved via resolveEffectiveParams).
     */
    fun <T> extractOwner(params: List<T>, unpackVarArgs: (T?) -> List<T>): T? =
        callKind.extractOwner(params, unpackVarArgs)

    /**
     * Extracts the target method's parameters from the reflection call's parameters (already resolved via resolveEffectiveParams).
     */
    fun <T> extractParameters(params: List<T>, unpackVarArgs: (T?) -> List<T>): List<T> =
        callKind.extractParameters(params, unpackVarArgs)
}

/**
 * Result of detecting a reflection call, containing both the reflection data
 * and for callBy, the ability to resolve Map parameters to ordered arrays.
 */
data class ReflectionDetectionResult(
    val data: ReflectedCallData,
    /**
     * For KFunction.callBy, the ordered list of KParameter keys to look up in the Map.
     * For other reflection calls, this is null.
     */
    internal val callByParameterKeys: List<KParameter>?,
)

/**
 * Resolves the effective parameters for extraction.
 * For KFunction.call(), unpacks the vararg array to get the actual parameters.
 * For KFunction.callBy(), converts the Map parameter to an ordered list using the stored keys.
 * Both return an unpacked list ready for owner/params extraction.
 * For null or non-KFunction reflection calls, returns the original params.
 */
fun ReflectionDetectionResult?.resolveEffectiveParameters(params: List<Any?>): List<Any?> {
    if (this == null) return params
    val keys = callByParameterKeys
    return when {
        keys != null -> {
            // callBy: extract map values in key order
            val map = params.firstOrNull() as? Map<*, *> ?: return params
            keys.map(map::get)
        }
        data.callKind.isKFunctionCall() -> {
            // call: unpack the vararg array
            unpackVarArgs(params.firstOrNull())
        }
        else -> {
            // Other reflection calls (Method.invoke, MethodHandle, etc.) - return as-is
            params
        }
    }
}

private fun ReflectionCallKind.isKFunctionCall(): Boolean = when (this) {
    ReflectionCallKind.KFUNCTION_CALL_INSTANCE,
    ReflectionCallKind.KFUNCTION_CALL_STATIC,
    ReflectionCallKind.KFUNCTION_CALL_CONSTRUCTOR -> true
    else -> false
}

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
 * @return ReflectionDetectionResult containing info about the target method, or null if not a reflection call
 */
fun detectReflectedCall(
    owner: Any?,
    className: String,
    methodName: String,
): ReflectionDetectionResult? = when {
    className == "java.lang.reflect.Method" && methodName == "invoke" && owner is Method ->
        ReflectionDetectionResult(resolveReflectionMethodInvoke(owner), null)
    className == "java.lang.reflect.Constructor" && methodName == "newInstance" && owner is Constructor<*> ->
        ReflectionDetectionResult(resolveReflectionConstructorInvoke(owner), null)
    className == "java.lang.invoke.MethodHandle" && isMethodHandleInvoke(methodName) && owner is MethodHandle ->
        resolveMethodHandleInvoke(owner, methodName)?.let { ReflectionDetectionResult(it, null) }
    className.startsWith("kotlin.reflect.") && methodName == "call" && owner is KFunction<*> ->
        resolveKotlinReflectionCall(owner)?.let { ReflectionDetectionResult(it, null) }
    className.startsWith("kotlin.reflect.") && methodName == "callBy" && owner is KFunction<*> ->
        resolveKotlinReflectionCallBy(owner)
    else -> null
}

private fun resolveReflectionMethodInvoke(method: Method): ReflectedCallData = ReflectedCallData(
    className = method.declaringClass.name,
    methodName = method.name,
    callKind = ReflectionCallKind.METHOD_INVOKE,
)

private fun resolveReflectionConstructorInvoke(constructor: Constructor<*>): ReflectedCallData = ReflectedCallData(
    className = constructor.declaringClass.name,
    methodName = "<init>",
    callKind = ReflectionCallKind.CONSTRUCTOR_NEW_INSTANCE
)

private fun resolveMethodHandleInvoke(
    handle: MethodHandle,
    methodName: String
): ReflectedCallData? {
    val info = try {
        MethodHandles.lookup().revealDirect(handle)
    } catch (_: Throwable) {
        return null
    }
    val isWithArgs = methodName == "invokeWithArguments"
    return when (info.referenceKind) {
        MethodHandleInfo.REF_invokeVirtual,
        MethodHandleInfo.REF_invokeInterface,
        MethodHandleInfo.REF_invokeSpecial -> ReflectedCallData(
            className = info.declaringClass.name,
            methodName = info.name,
            callKind = if (isWithArgs) ReflectionCallKind.METHOD_HANDLE_WITH_ARGS_INSTANCE
                       else ReflectionCallKind.METHOD_HANDLE_INSTANCE
        )
        MethodHandleInfo.REF_invokeStatic -> ReflectedCallData(
            className = info.declaringClass.name,
            methodName = info.name,
            callKind = if (isWithArgs) ReflectionCallKind.METHOD_HANDLE_WITH_ARGS_STATIC
                       else ReflectionCallKind.METHOD_HANDLE_STATIC,
        )
        MethodHandleInfo.REF_newInvokeSpecial -> ReflectedCallData(
            className = info.declaringClass.name,
            methodName = "<init>",
            callKind = if (isWithArgs) ReflectionCallKind.METHOD_HANDLE_WITH_ARGS_CONSTRUCTOR
                       else ReflectionCallKind.METHOD_HANDLE_CONSTRUCTOR
        )
        else -> null
    }
}

private fun resolveKotlinReflectionCall(function: KFunction<*>): ReflectedCallData? {
    function.javaMethod?.let { javaMethod ->
        val isStatic = Modifier.isStatic(javaMethod.modifiers)
        return ReflectedCallData(
            className = javaMethod.declaringClass.name,
            methodName = javaMethod.name,
            callKind = if (isStatic) ReflectionCallKind.KFUNCTION_CALL_STATIC
                       else ReflectionCallKind.KFUNCTION_CALL_INSTANCE,
        )
    }
    function.javaConstructor?.let { constructor ->
        return ReflectedCallData(
            className = constructor.declaringClass.name,
            methodName = "<init>",
            callKind = ReflectionCallKind.KFUNCTION_CALL_CONSTRUCTOR
        )
    }
    return null
}

private fun resolveKotlinReflectionCallBy(function: KFunction<*>): ReflectionDetectionResult? {
    // Get the ordered parameter keys for looking up values in the Map
    val parameterKeys = function.parameters

    function.javaMethod?.let { javaMethod ->
        val isStatic = Modifier.isStatic(javaMethod.modifiers)
        return ReflectionDetectionResult(
            data = ReflectedCallData(
                className = javaMethod.declaringClass.name,
                methodName = javaMethod.name,
                callKind = if (isStatic) ReflectionCallKind.KFUNCTION_CALL_STATIC
                           else ReflectionCallKind.KFUNCTION_CALL_INSTANCE,
            ),
            callByParameterKeys = parameterKeys,
        )
    }
    function.javaConstructor?.let { constructor ->
        return ReflectionDetectionResult(
            data = ReflectedCallData(
                className = constructor.declaringClass.name,
                methodName = "<init>",
                callKind = ReflectionCallKind.KFUNCTION_CALL_CONSTRUCTOR,
            ),
            callByParameterKeys = parameterKeys,
        )
    }
    return null
}

private fun isMethodHandleInvoke(methodName: String): Boolean =
    methodName == "invoke" || methodName == "invokeExact" || methodName == "invokeWithArguments"

/**
 * Default implementation for unpacking vararg/array/list parameters.
 * Can be used as the `unpackVarArgs` function when calling extraction methods with actual objects.
 */
fun unpackVarArgs(args: Any?): List<Any?> = when (args) {
    is Array<*> -> args.asList()
    is List<*> -> args
    null -> emptyList()
    else -> error("Unexpected argument type: ${args::class.java.name}")
}
