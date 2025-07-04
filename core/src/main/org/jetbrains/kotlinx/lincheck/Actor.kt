/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.annotations.*
import java.lang.reflect.Method
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