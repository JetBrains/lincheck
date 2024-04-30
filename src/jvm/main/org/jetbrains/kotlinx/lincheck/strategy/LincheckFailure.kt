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
    val trace: Trace?
) {
    override fun toString() = StringBuilder().appendFailure(this).toString()
}

internal class IncorrectResultsFailure(
    scenario: ExecutionScenario,
    val results: ExecutionResult,
    trace: Trace? = null
) : LincheckFailure(scenario, trace)

internal class DeadlockOrLivelockFailure(
    scenario: ExecutionScenario,
    // Thread dump is not present in case of model checking
    val threadDump: Map<Thread, Array<StackTraceElement>>?,
    trace: Trace? = null
) : LincheckFailure(scenario, trace)

internal class UnexpectedExceptionFailure(
    scenario: ExecutionScenario,
    val exception: Throwable,
    trace: Trace? = null
) : LincheckFailure(scenario, trace)

internal class ValidationFailure(
    scenario: ExecutionScenario,
    val exception: Throwable,
    trace: Trace? = null
) : LincheckFailure(scenario, trace) {
    val validationFunctionName: String = scenario.validationFunction!!.method.name
}

internal class ObstructionFreedomViolationFailure(
    scenario: ExecutionScenario,
    val reason: String,
    trace: Trace? = null
) : LincheckFailure(scenario, trace)

internal fun InvocationResult.toLincheckFailure(scenario: ExecutionScenario, trace: Trace? = null) = when (this) {
    is ManagedDeadlockInvocationResult -> DeadlockOrLivelockFailure(scenario, threadDump = null, trace)
    is RunnerTimeoutInvocationResult -> DeadlockOrLivelockFailure(scenario, threadDump, trace = null)
    is UnexpectedExceptionInvocationResult -> UnexpectedExceptionFailure(scenario, exception, trace)
    is ValidationFailureInvocationResult -> ValidationFailure(scenario, exception, trace)
    is ObstructionFreedomViolationInvocationResult -> ObstructionFreedomViolationFailure(scenario, reason, trace)
    is CompletedInvocationResult -> IncorrectResultsFailure(scenario, results, trace)
    else -> error("Unexpected invocation result type: ${this.javaClass.simpleName}")
}