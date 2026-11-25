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
import org.jetbrains.kotlinx.lincheck.util.ExceptionResult
import org.jetbrains.lincheck.util.AnalysisProfile
import java.util.Collections
import java.util.IdentityHashMap

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

/**
 * Materializes the stack traces of the exceptions captured in this failure.
 *
 * The JVM materializes [Throwable.getStackTrace] lazily and caches the result in the throwable.
 * Detaching the Lincheck agent retransforms the instrumented classes,
 * which makes the method versions captured in the backtrace obsolete
 * and loses their file and line information.
 * Materializing while the agent is still attached makes the failure report
 * independent of when it is rendered.
 */
internal fun LincheckFailure.materializeExceptionStackTraces() {
    val resultExceptions = with(results) { initResults + parallelResults.flatten() + postResults }
        .filterIsInstance<ExceptionResult>()
        .map { it.throwable }
    val failureException = when (this) {
        is UnexpectedExceptionFailure -> exception
        is ValidationFailure -> exception
        else -> null
    }
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    (resultExceptions + listOfNotNull(failureException)).forEach { it.materializeStackTrace(seen) }
}

private fun Throwable.materializeStackTrace(seen: MutableSet<Throwable>) {
    if (!seen.add(this)) return
    // `getStackTrace` materializes the stack trace elements and caches them in the throwable.
    stackTrace.hashCode()
    cause?.materializeStackTrace(seen)
    suppressed.forEach { it.materializeStackTrace(seen) }
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
