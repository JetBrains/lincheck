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

internal data class PureDeterministicMethodDescriptor<T>(
    override val methodCallInfo: MethodCallInfo,
    val lincheckModeBehaviour: PureDeterministicMethodDescriptor<T>.() -> T
) : DeterministicMethodDescriptor<Result<T>, T>() {
    override fun runInLincheckMode(): T = lincheckModeBehaviour()
    override fun runFromState(state: Result<T>): T = state.getOrThrow()
    override fun runSavingToState(saver: (Result<T>) -> Unit): T =
        runCatching { invokeOriginalCall() }.also(saver).getOrThrow()
}
