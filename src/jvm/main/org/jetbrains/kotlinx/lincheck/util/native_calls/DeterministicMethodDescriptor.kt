/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util.native_calls

import org.objectweb.asm.commons.Method.*
import java.lang.reflect.Method

internal data class MethodCallInfo(
    val owner: Any?,
    val className: String,
    val methodName: String,
    val codeLocation: Int,
    val methodId: Int,
    val methodDesc: String,
    val params: List<Any?>,
) {
    infix fun seemsToBeTheSameMethodCallWith(other: MethodCallInfo): Boolean =
        className == other.className && methodName == other.methodName && codeLocation == other.codeLocation &&
                methodId == other.methodId && methodDesc == other.methodDesc
}


internal abstract class DeterministicMethodDescriptor<State, T> {
    abstract val methodCallInfo: MethodCallInfo
    abstract fun runFromState(state: State): T
    abstract fun runSavingToState(saver: (State) -> Unit): T
    abstract fun runInLincheckMode(): T
    @Suppress("UNCHECKED_CAST")
    fun runFromStateWithCast(state: Any?): T = runFromState(state as State)
    
    val originalMethod: Method by lazy {
        Class.forName(methodCallInfo.className.replace('/', '.')).run { methods + declaredMethods }
            .first { it.name == methodCallInfo.methodName && getMethod(it).descriptor == methodCallInfo.methodDesc }
            .also { it.isAccessible = true }
    }

    @Suppress("UNCHECKED_CAST")
    fun invokeOriginalCall() = originalMethod.invoke(methodCallInfo.owner, *methodCallInfo.params.toTypedArray()) as T
}

internal fun getDeterministicMethodDescriptorOrNull(methodCallInfo: MethodCallInfo) =
    getDeterministicTimeMethodDescriptorOrNull(methodCallInfo)
        ?: getDeterministicRandomMethodDescriptorOrNull(methodCallInfo)
