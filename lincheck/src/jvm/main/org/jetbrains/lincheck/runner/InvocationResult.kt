/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck.runner

import org.jetbrains.lincheck.util.AnalysisProfile
import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.scenario.*
import org.jetbrains.lincheck.strategy.managed.eventstructure.consistency.Inconsistency

/**
 * Represents results for invocations, see [Runner.runInvocation].
 */
sealed class InvocationResult

/**
 * The invocation completed successfully, the output [results] are provided.
 */
class CompletedInvocationResult(
    val scenario: ExecutionScenario,
    val results: ExecutionResult
) : InvocationResult()

/**
 * The invocation failed with [failure].
 */
class FailedInvocationResult(val failure: LincheckFailure) : InvocationResult()

fun InvocationResult.isAbortedInvocation(): Boolean =
    this is FailedInvocationResult && when (failure) {
        is ManagedDeadlockFailure,
        is TimeoutFailure,
        is UnexpectedExceptionFailure,
        is ObstructionFreedomViolationFailure,
        is InconsistentExecutionFailure
             -> true
        else -> false
    }

internal fun ManagedDeadlockInvocationResult(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    analysisProfile: AnalysisProfile
): FailedInvocationResult = FailedInvocationResult(ManagedDeadlockFailure(scenario, results, analysisProfile = analysisProfile))

internal fun ManagedLivelockInvocationResult(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    analysisProfile: AnalysisProfile
): FailedInvocationResult = FailedInvocationResult(ManagedLivelockFailure(scenario, results, analysisProfile = analysisProfile))

internal fun TimeoutInvocationResult(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    threadDump: Map<Thread, Array<StackTraceElement>>,
    analysisProfile: AnalysisProfile
): FailedInvocationResult = FailedInvocationResult(TimeoutFailure(scenario, results, threadDump, analysisProfile = analysisProfile))

internal fun UnexpectedExceptionInvocationResult(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    exception: Throwable,
    analysisProfile: AnalysisProfile
): FailedInvocationResult = FailedInvocationResult(UnexpectedExceptionFailure(scenario, results, exception, analysisProfile = analysisProfile))

internal fun ValidationFailureInvocationResult(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    exception: Throwable,
    analysisProfile: AnalysisProfile
): FailedInvocationResult = FailedInvocationResult(ValidationFailure(scenario, results, exception, analysisProfile = analysisProfile))

internal fun ObstructionFreedomViolationInvocationResult(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    reason: String,
    analysisProfile: AnalysisProfile
): FailedInvocationResult = FailedInvocationResult(ObstructionFreedomViolationFailure(scenario, results, reason, analysisProfile = analysisProfile))

internal fun InconsistentInvocationResult(
    scenario: ExecutionScenario,
    results: ExecutionResult,
    inconsistency: Inconsistency,
    analysisProfile: AnalysisProfile
): FailedInvocationResult = FailedInvocationResult(InconsistentExecutionFailure(scenario, results, inconsistency, analysisProfile = analysisProfile))
