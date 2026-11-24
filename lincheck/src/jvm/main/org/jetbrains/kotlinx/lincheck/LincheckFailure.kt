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

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.Inconsistency
import org.jetbrains.kotlinx.lincheck.trace.Trace
import org.jetbrains.lincheck.util.AnalysisProfile

sealed class LincheckFailure(
    val scenario: ExecutionScenario,
    val results: ExecutionResult,
    trace: Trace?,
    internal val analysisProfile: AnalysisProfile
) {
    var trace: Trace? = trace
        internal set

    /** Rendered while the failing execution is still available; survives cleanup and minimization. */
    internal var executionGraph: String? = null

    override fun toString() =
        StringBuilder().appendFailure(this).toString()
}

internal class IncorrectResultsFailure(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    trace: Trace? = null,
    analysisProfile: AnalysisProfile
) : LincheckFailure(scenario, results, trace, analysisProfile = analysisProfile)

internal class ManagedDeadlockFailure(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    trace: Trace? = null,
    analysisProfile: AnalysisProfile
) : LincheckFailure(scenario,results, trace, analysisProfile = analysisProfile)

internal class ManagedLivelockFailure(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    trace: Trace? = null,
    analysisProfile: AnalysisProfile
) : LincheckFailure(scenario,results, trace, analysisProfile = analysisProfile)

internal class TimeoutFailure(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    val threadDump: Map<Thread, Array<StackTraceElement>>,
    analysisProfile: AnalysisProfile
) : LincheckFailure(scenario,results, null, analysisProfile = analysisProfile)

internal class UnexpectedExceptionFailure(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    val exception: Throwable,
    trace: Trace? = null,
    analysisProfile: AnalysisProfile
) : LincheckFailure(scenario,results, trace, analysisProfile = analysisProfile)

internal class ValidationFailure(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    val exception: Throwable,
    trace: Trace? = null,
    analysisProfile: AnalysisProfile
) : LincheckFailure(scenario,results, trace, analysisProfile = analysisProfile) {
    val validationFunctionName: String = scenario.validationFunction!!.method.name
}

internal class ObstructionFreedomViolationFailure(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    val reason: String,
    trace: Trace? = null,
    analysisProfile: AnalysisProfile
) : LincheckFailure(scenario, results, trace, analysisProfile = analysisProfile)

internal class InconsistentExecutionFailure(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    val inconsistency: Inconsistency,
    analysisProfile: AnalysisProfile
) : LincheckFailure(scenario, results, null, analysisProfile = analysisProfile)
