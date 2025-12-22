/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.nativecalls

import org.jetbrains.kotlinx.lincheck.strategy.nativecalls.io.getDeterministicFileMethodDescriptorOrNull
import org.jetbrains.kotlinx.lincheck.util.intercept
import org.jetbrains.lincheck.descriptors.MethodSignature
import org.jetbrains.lincheck.descriptors.Types
import sun.nio.ch.lincheck.ResultInterceptor

internal data class MethodCallInfo(
    val ownerType: Types.ObjectType,
    val methodSignature: MethodSignature,
    val codeLocation: Int,
    val methodId: Int,
)


/**
 * Represents an abstract descriptor for a deterministic method call.
 * This class is used to describe, replay, and simulate the deterministic
 * behavior of a specific method invocation within a given state.
 * 
 * The state is saved on the first invocation with [saveFirstResult], and it is then replayed with [replay].
 * 
 * [runFake] calls fake implementation of the method that is not based on the real calls at all.
 *
 * @param State The state type associated with this descriptor's operations, storing the result and side effects.
 * @param T The return type of the described method call.
 */
internal abstract class DeterministicMethodDescriptor<State, T> {
    abstract val methodCallInfo: MethodCallInfo
    abstract fun replay(receiver: Any?, params: Array<Any?>, state: State): Result<T>
    abstract fun runFake(receiver: Any?, params: Array<Any?>): Result<T>
    abstract fun saveFirstResult(receiver: Any?, params: Array<Any?>, result: Result<T>, saveState: (State) -> Unit): Result<T>
}

@Suppress("UNCHECKED_CAST")
internal fun <State, T> DeterministicMethodDescriptor<State, T>.runFromStateWithCast(
    receiver: Any?, params: Array<Any?>, state: Any?
): Result<T> = replay(receiver, params, state as State)

@Suppress("UNCHECKED_CAST")
internal fun <State, T> DeterministicMethodDescriptor<State, T>.saveFirstResultWithCast(
    receiver: Any?, params: Array<Any?>, result: Result<Any?>, saveState: (State) -> Unit
) = saveFirstResult(receiver, params, result.map { it as T }, saveState)

internal fun getDeterministicMethodDescriptorOrNull(receiver: Any?, params: Array<Any?>, methodCallInfo: MethodCallInfo): DeterministicMethodDescriptor<*, *>? {
    getDeterministicTimeMethodDescriptorOrNull(methodCallInfo)?.let { return it }
    getDeterministicRandomMethodDescriptorOrNull(methodCallInfo)?.let { return it }
    getDeterministicFileMethodDescriptorOrNull(receiver, params, methodCallInfo)?.let { return it }
    return null
}

internal data class DeterministicMethodCallInterceptorData(
    val deterministicCallId: Long,
    val deterministicMethodDescriptor: DeterministicMethodDescriptor<*, *>,
)