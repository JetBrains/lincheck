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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*

sealed class LincheckFailure(
    val scenario: ExecutionScenario,
    val results: ExecutionResult,
    val trace: Trace?
) {
    override fun toString() = StringBuilder().appendFailure(this).toString()
}

internal class IncorrectResultsFailure(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    trace: Trace? = null
) : LincheckFailure(scenario, results, trace)

internal class ManagedDeadlockFailure(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    trace: Trace? = null
) : LincheckFailure(scenario,results, trace)

internal class TimeoutFailure(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    val threadDump: Map<Thread, Array<StackTraceElement>>,
) : LincheckFailure(scenario,results, null)

internal class UnexpectedExceptionFailure(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    val exception: Throwable,
    trace: Trace? = null
) : LincheckFailure(scenario,results, trace)

internal class ValidationFailure(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    val exception: Throwable,
    trace: Trace? = null
) : LincheckFailure(scenario,results, trace) {
    val validationFunctionName: String = scenario.validationFunction!!.method.name
}

internal class ObstructionFreedomViolationFailure(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    val reason: String,
    trace: Trace? = null
) : LincheckFailure(scenario, results, trace)

internal fun InvocationResult.toLincheckFailure(scenario: ExecutionScenario, trace: Trace? = null) = when (this) {
    is ManagedDeadlockInvocationResult -> ManagedDeadlockFailure(scenario, results, trace)
    is RunnerTimeoutInvocationResult -> TimeoutFailure(scenario, results, threadDump)
    is UnexpectedExceptionInvocationResult -> UnexpectedExceptionFailure(scenario, results, exception, trace)
    is ValidationFailureInvocationResult -> ValidationFailure(scenario, results, exception, trace)
    is ObstructionFreedomViolationInvocationResult -> ObstructionFreedomViolationFailure(scenario, results, reason, trace)
    is CompletedInvocationResult -> IncorrectResultsFailure(scenario, results, trace)
    else -> error("Unexpected invocation result type: ${this.javaClass.simpleName}")
}