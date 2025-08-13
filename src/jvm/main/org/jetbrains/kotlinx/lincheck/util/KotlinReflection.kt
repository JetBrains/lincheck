/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util

import org.jetbrains.kotlinx.lincheck.isSuspendable
import org.jetbrains.lincheck.descriptors.MethodSignature
import org.jetbrains.lincheck.descriptors.toMethodSignature
import org.jetbrains.lincheck.util.*
import java.lang.reflect.Executable
import kotlin.coroutines.Continuation
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.jvm.jvmName


/**
 * Determines whether a given method is a suspending function.
 *
 * @param className The name of the class containing the method.
 * @param methodName The name of the method to check.
 * @param params An array of parameters passed to the method used to infer the method signature.
 * @return `true` if the method is a suspending function; `false` otherwise.
 */
internal fun isSuspendFunction(className: String, methodName: String, params: List<Any?>): Boolean {
    // fast-path: if the last parameter is not continuation - then this is not suspending function
    if (params.lastOrNull() !is Continuation<*>) return false
    val result = runCatching {
        // While this code is inefficient, it is called only on the slow path.
        val method = getMethod(className, methodName, params)
        method?.isSuspendable() == true
    }
    return result.getOrElse {
        // Something went wrong. Ignore it, as the error might lead only
        // to an extra "<cont>" in the method call line in the trace.
        false
    }
}

// TODO: move Java reflection utils (not depending on kotlin-reflection)
//   to `common/org/jetbrains/lincheck/util/Utils.kt`

/**
 * Retrieves a `Method` object representing a method of the specified name and parameter types.
 *
 * @param className The name of the class containing the method.
 * @param methodName The name of the method to retrieve.
 * @param params An array of parameters to match against the method's parameter types.
 *   The method is selected if its parameter types are compatible
 *   with the runtime classes of the elements in this array.
 * @return The matching [Method] object if found, or `null` if no method matches.
 */
internal fun getMethod(className: String, methodName: String, params: List<Any?>): Method? {
    val possibleMethods = getCachedFilteredDeclaredMethods(className, methodName)
    // search through all possible methods, matching the arguments' types
    for (method in possibleMethods) {
        val parameterTypes = method.parameterTypes
        if (parameterTypes.size != params.size) continue
        var match = true
        for (i in parameterTypes.indices) {
            val paramType = params[i]?.javaClass
            if (paramType != null && !parameterTypes[i].isAssignableFrom(paramType)) {
                match = false
                break
            }
        }
        if (match) return method
    }
    return null // or throw an exception if a match is mandatory
}

// While caching is not important for Lincheck itself,
// it is critical for Trace Debugger, as it always collects the trace.
private val methodsCache = hashMapOf<String, Array<Method>>()
private val filteredMethodsCache = hashMapOf<Pair<String, String>, List<Method>>()

private fun getCachedDeclaredMethods(className: String) =
    methodsCache.getOrPut(className) {
        val clazz = Class.forName(className)
        (clazz.methods + clazz.declaredMethods).distinct().toTypedArray()
    }

private fun getCachedFilteredDeclaredMethods(className: String, methodName: String) =
    filteredMethodsCache.getOrPut(className to methodName) {
        val declaredMethods = getCachedDeclaredMethods(className)
        declaredMethods.filter { it.name == methodName }
    }

/**
 * Extension property to determine if the given object is a [kotlinx.coroutines] symbol.
 */
internal val Any?.isCoroutinesSymbol get() =
    this != null && this::class.jvmName == "kotlinx.coroutines.internal.Symbol"

internal val isPublic: (Executable) -> Boolean = { it.modifiers and Modifier.PUBLIC != 0 }
internal val isProtected: (Executable) -> Boolean = { it.modifiers and Modifier.PROTECTED != 0 }
internal val isPrivate: (Executable) -> Boolean = { it.modifiers and Modifier.PRIVATE != 0 }
internal val isPackagePrivate: (Executable) -> Boolean = not(isPublic or isProtected or isPrivate)
internal val isStatic: (Method) -> Boolean = { it.modifiers and Modifier.STATIC != 0 }
internal val isInstance: (Method) -> Boolean = { it.modifiers and Modifier.STATIC == 0 }

internal fun Class<*>.getMethods(condition: (Method) -> Boolean): Set<MethodSignature> =
    declaredMethods.filter(condition).mapTo(mutableSetOf()) { it.toMethodSignature() }

internal inline fun <reified T> getMethods(noinline condition: (Method) -> Boolean = { true }): Set<MethodSignature> =
    T::class.java.getMethods(condition)
