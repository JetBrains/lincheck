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

internal data class MethodCallInfo(
    val owner: Any?,
    val ownerType: ArgumentType.Object,
    val methodDescriptor: MethodDescriptor,
    val codeLocation: Int,
    val methodId: Int,
    val params: List<Any?>,
) {
    infix fun seemsToBeTheSameMethodCallWith(other: MethodCallInfo): Boolean =
        ownerType == other.ownerType && methodDescriptor == other.methodDescriptor &&
                codeLocation == other.codeLocation && methodId == other.methodId
}


internal abstract class DeterministicMethodDescriptor<State, T> {
    abstract val methodCallInfo: MethodCallInfo
    abstract fun runFromState(state: State): T
    abstract fun runInLincheckMode(): T
    abstract fun onResultOnFirstRun(result: T, saveState: (State) -> Unit)
    abstract fun onExceptionOnFirstRun(e: Throwable, saveState: (State) -> Unit)
}

@Suppress("UNCHECKED_CAST")
internal fun <State, T> DeterministicMethodDescriptor<State, T>.runFromStateWithCast(state: Any?): T = runFromState(state as State)

@Suppress("UNCHECKED_CAST")
internal fun <State, T> DeterministicMethodDescriptor<State, T>.onResultOnFirstRunWithCast(result: Any?, saveState: (State) -> Unit) = onResultOnFirstRun(result as T, saveState)

internal fun getDeterministicMethodDescriptorOrNull(methodCallInfo: MethodCallInfo) =
    getDeterministicTimeMethodDescriptorOrNull(methodCallInfo)
        ?: getDeterministicRandomMethodDescriptorOrNull(methodCallInfo)
