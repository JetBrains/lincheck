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

/**
 * Represents a pure deterministic method descriptor.
 *
 * This class provides the functionality of `DeterministicMethodDescriptor` to handle
 * deterministic method behaviour where the method result can be computed solely based on its inputs,
 * not modifying them or anything else.
 *
 * The [fakeBehaviour] provides a lambda that defines the behaviour to be executed deterministically
 * when running in the fake mode.
 *
 * @param T The return type of the described method's execution.
 * @property methodCallInfo Information about the method call, including its owner, parameters, and identifying details.
 * @property fakeBehaviour The lambda function that specifies the deterministic behaviour of the method.
 */
internal data class PureDeterministicMethodDescriptor<T>(
    override val methodCallInfo: MethodCallInfo,
    val fakeBehaviour: PureDeterministicMethodDescriptor<T>.(receiver: Any?, params: Array<Any?>) -> T
) : DeterministicMethodDescriptor<Result<T>, T>() {
    override fun runFake(receiver: Any?, params: Array<Any?>): T = fakeBehaviour(receiver, params)
    override fun replay(receiver: Any?, params: Array<Any?>, state: Result<T>): T = postProcess(state.getOrThrow())
    override fun saveFirstException(receiver: Any?, params: Array<Any?>, e: Throwable, saveState: (Result<T>) -> Unit) =
        saveState(Result.failure(e))

    override fun saveFirstResult(receiver: Any?, params: Array<Any?>, result: T, saveState: (Result<T>) -> Unit) =
        saveState(Result.success(postProcess(result)))
    
    @Suppress("UNCHECKED_CAST")
    private fun postProcess(x: T): T = when (x) {
        is ByteArray -> x.copyOf() as T
        else -> x
    }
}
