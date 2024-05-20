/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.runner

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.Inconsistency

/**
 * Represents results for invocations, see [Runner.run].
 */
sealed class InvocationResult

/**
 * The invocation completed successfully, the output [results] are provided.
 */
class CompletedInvocationResult(
    val results: ExecutionResult
) : InvocationResult()

/**
 * Indicates that the invocation has run into deadlock or livelock found by [ManagedStrategy].
 */
class ManagedDeadlockInvocationResult(val results: ExecutionResult) : InvocationResult()

/**
 * The invocation was not completed after timeout and runner halted the execution.
 */
class RunnerTimeoutInvocationResult(
    val threadDump: Map<Thread, Array<StackTraceElement>>,
    val results: ExecutionResult
): InvocationResult()

/**
 * The invocation has completed with an unexpected exception.
 */
class UnexpectedExceptionInvocationResult(
    val exception: Throwable,
    val results: ExecutionResult
) : InvocationResult()

/**
 * The invocation successfully completed, but the
 * [validation function][org.jetbrains.kotlinx.lincheck.annotations.Validate]
 * check failed.
 */
class ValidationFailureInvocationResult(
    val scenario: ExecutionScenario,
    val exception: Throwable,
    val results: ExecutionResult
) : InvocationResult()

/**
 * Obstruction freedom check is requested,
 * but an invocation that hangs has been found.
 */
class ObstructionFreedomViolationInvocationResult(
    val reason: String,
    val results: ExecutionResult
) : InvocationResult()

class InconsistentInvocationResult(
    val inconsistency: Inconsistency
) : InvocationResult()

/**
 * Invocation is aborted due to one of the threads reaching
 * the bound on the number of spin-loop iterations.
 */
class SpinLoopBoundInvocationResult : InvocationResult()

/**
 * Indicates that spin-cycle has been found for the first time and replay of current interleaving is required.
 */
data object SpinCycleFoundAndReplayRequired: InvocationResult()


fun InvocationResult.isAbortedInvocation(): Boolean =
    when (this) {
        is ManagedDeadlockInvocationResult,
        is RunnerTimeoutInvocationResult,
        is SpinLoopBoundInvocationResult,
        is UnexpectedExceptionInvocationResult,
        is ObstructionFreedomViolationInvocationResult,
        is InconsistentInvocationResult
             -> true
        else -> false
    }