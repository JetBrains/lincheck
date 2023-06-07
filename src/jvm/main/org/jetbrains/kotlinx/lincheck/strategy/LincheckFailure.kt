/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy

import kotlinx.serialization.Serializable
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*

sealed class LincheckFailure(
    val scenario: ExecutionScenario,
    val trace: Trace?,
    val runProperties: RunProperties
) {
    override fun toString() = StringBuilder().appendFailure(this).toString()
}

internal class IncorrectResultsFailure(
    scenario: ExecutionScenario,
    val results: ExecutionResult,
    runProperties: RunProperties,
    trace: Trace? = null,
) : LincheckFailure(scenario, trace, runProperties)

internal class DeadlockWithDumpFailure(
    scenario: ExecutionScenario,
    val threadDump: Map<Thread, Array<StackTraceElement>>,
    runProperties: RunProperties,
    trace: Trace? = null
) : LincheckFailure(scenario, trace, runProperties)

internal class UnexpectedExceptionFailure(
    scenario: ExecutionScenario,
    val exception: Throwable,
    runProperties: RunProperties,
    trace: Trace? = null
) : LincheckFailure(scenario, trace, runProperties)

internal class ValidationFailure(
    scenario: ExecutionScenario,
    val functionName: String,
    val exception: Throwable,
    runProperties: RunProperties,
    trace: Trace? = null
) : LincheckFailure(scenario, trace, runProperties)

internal class ObstructionFreedomViolationFailure(
    scenario: ExecutionScenario,
    val reason: String,
    runProperties: RunProperties,
    trace: Trace? = null
) : LincheckFailure(scenario, trace, runProperties)

internal fun InvocationResult.toLincheckFailure(scenario: ExecutionScenario, runProperties: RunProperties, trace: Trace? = null) = when (this) {
    is DeadlockInvocationResult -> DeadlockWithDumpFailure(scenario, threadDump, runProperties, trace)
    is UnexpectedExceptionInvocationResult -> UnexpectedExceptionFailure(scenario, exception, runProperties, trace)
    is ValidationFailureInvocationResult -> ValidationFailure(scenario, functionName, exception, runProperties, trace)
    is ObstructionFreedomViolationInvocationResult -> ObstructionFreedomViolationFailure(scenario, reason,runProperties,  trace)
    is CompletedInvocationResult -> IncorrectResultsFailure(scenario, results, runProperties, trace)
    else -> error("Unexpected invocation result type: ${this.javaClass.simpleName}")
}

@Serializable
data class RunProperties(
    val iterations: Int,
    val invocationsPerIteration: Int
)
