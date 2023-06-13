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
    val trace: Trace?,
    val reproduceSettings: ReproduceSettings
) {
    override fun toString() = StringBuilder().appendFailure(this).toString()
}

internal class IncorrectResultsFailure(
    scenario: ExecutionScenario,
    val results: ExecutionResult,
    reproduceSettings: ReproduceSettings,
    trace: Trace? = null,
) : LincheckFailure(scenario, trace, reproduceSettings)

internal class DeadlockWithDumpFailure(
    scenario: ExecutionScenario,
    val threadDump: Map<Thread, Array<StackTraceElement>>,
    reproduceSettings: ReproduceSettings,
    trace: Trace? = null
) : LincheckFailure(scenario, trace, reproduceSettings)

internal class UnexpectedExceptionFailure(
    scenario: ExecutionScenario,
    val exception: Throwable,
    reproduceSettings: ReproduceSettings,
    trace: Trace? = null
) : LincheckFailure(scenario, trace, reproduceSettings)

internal class ValidationFailure(
    scenario: ExecutionScenario,
    val functionName: String,
    val exception: Throwable,
    reproduceSettings: ReproduceSettings,
    trace: Trace? = null
) : LincheckFailure(scenario, trace, reproduceSettings)

internal class ObstructionFreedomViolationFailure(
    scenario: ExecutionScenario,
    val reason: String,
    reproduceSettings: ReproduceSettings,
    trace: Trace? = null
) : LincheckFailure(scenario, trace, reproduceSettings)

internal fun InvocationResult.toLincheckFailure(scenario: ExecutionScenario, reproduceSettings: ReproduceSettings, trace: Trace? = null) = when (this) {
    is DeadlockInvocationResult -> DeadlockWithDumpFailure(scenario, threadDump, reproduceSettings, trace)
    is UnexpectedExceptionInvocationResult -> UnexpectedExceptionFailure(scenario, exception, reproduceSettings, trace)
    is ValidationFailureInvocationResult -> ValidationFailure(scenario, functionName, exception, reproduceSettings, trace)
    is ObstructionFreedomViolationInvocationResult -> ObstructionFreedomViolationFailure(scenario, reason,reproduceSettings,  trace)
    is CompletedInvocationResult -> IncorrectResultsFailure(scenario, results, reproduceSettings, trace)
    else -> error("Unexpected invocation result type: ${this.javaClass.simpleName}")
}
