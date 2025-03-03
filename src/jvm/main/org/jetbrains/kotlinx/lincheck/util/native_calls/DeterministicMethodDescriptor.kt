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
    val methodSignature: MethodSignature,
    val codeLocation: Int,
    val methodId: Int,
    val params: List<Any?>,
) {
    infix fun seemsToBeTheSameMethodCallWith(other: MethodCallInfo): Boolean =
        ownerType == other.ownerType && methodSignature == other.methodSignature &&
                codeLocation == other.codeLocation && methodId == other.methodId
}


/**
 * Represents an abstract descriptor for a deterministic method call.
 * This class is used to describe, replay, and simulate the deterministic
 * behavior of a specific method invocation within a given state.
 * 
 * The state is saved on the first invocation with [saveFirstResult] and [saveFirstException],
 * and it is then replayed with [replay].
 * 
 * [runFake] calls fake implementation of the method that is not based on the real calls at all.
 *
 * @param State The state type associated with this descriptor's operations, storing the result and side effects.
 * @param T The return type of the described method call.
 */
internal abstract class DeterministicMethodDescriptor<State, T> {
    abstract val methodCallInfo: MethodCallInfo
    abstract fun replay(state: State): T
    abstract fun runFake(): T
    abstract fun saveFirstResult(result: T, saveState: (State) -> Unit)
    abstract fun saveFirstException(e: Throwable, saveState: (State) -> Unit)
}

@Suppress("UNCHECKED_CAST")
internal fun <State, T> DeterministicMethodDescriptor<State, T>.runFromStateWithCast(state: Any?): T = replay(state as State)

@Suppress("UNCHECKED_CAST")
internal fun <State, T> DeterministicMethodDescriptor<State, T>.onResultOnFirstRunWithCast(result: Any?, saveState: (State) -> Unit) = saveFirstResult(result as T, saveState)

internal fun getDeterministicMethodDescriptorOrNull(methodCallInfo: MethodCallInfo) =
    getDeterministicTimeMethodDescriptorOrNull(methodCallInfo)
        ?: getDeterministicRandomMethodDescriptorOrNull(methodCallInfo)
