/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.execution

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.lincheck.datastructures.ParameterGenerator
import org.jetbrains.lincheck.datastructures.THREAD_ID_TOKEN
import java.lang.reflect.*
import java.util.Random

/**
 * Implementations of this class generate [actors][Actor]
 * using [parameter generators][org.jetbrains.lincheck.datastructures.ParameterGenerator].
 */
class ActorGenerator(
    private val method: Method,
    private val parameterGenerators: List<ParameterGenerator<*>>,
    val useOnce: Boolean,
    cancellableOnSuspension: Boolean,
    private val blocking: Boolean,
    private val causesBlocking: Boolean,
    promptCancellation: Boolean
) {
    private val cancellableOnSuspension = cancellableOnSuspension && isSuspendable
    private val promptCancellation = cancellableOnSuspension && promptCancellation

    fun generate(threadId: Int, random: Random): Actor {
        val parameters = parameterGenerators
            .map { it.generate() }
            .map { if (it === THREAD_ID_TOKEN) threadId else it }
        val cancelOnSuspension = this.cancellableOnSuspension and random.nextBoolean()
        val promptCancellation = cancelOnSuspension and this.promptCancellation and random.nextBoolean()
        return Actor(
            method = method,
            arguments = parameters,
            cancelOnSuspension = cancelOnSuspension,
            blocking = blocking,
            causesBlocking = causesBlocking,
            promptCancellation = promptCancellation
        )
    }

    val isSuspendable: Boolean get() = method.isSuspendable()
    override fun toString() = method.toString()
}