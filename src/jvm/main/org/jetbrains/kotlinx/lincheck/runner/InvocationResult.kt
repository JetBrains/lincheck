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
 * Indicates that the invocation has run into deadlock or livelock.
 */
class DeadlockInvocationResult(
    val threadDump: Map<Thread, Array<StackTraceElement>>
) : InvocationResult()

/**
 * The invocation has completed with an unexpected exception.
 */
class UnexpectedExceptionInvocationResult(
    val exception: Throwable
) : InvocationResult()

/**
 * The invocation successfully completed, but the
 * [validation function][org.jetbrains.kotlinx.lincheck.annotations.Validate]
 * check failed.
 */
class ValidationFailureInvocationResult(
    val scenario: ExecutionScenario,
    val functionName: String,
    val exception: Throwable
) : InvocationResult()

/**
 * Obstruction freedom check is requested,
 * but an invocation that hangs has been found.
 */
class ObstructionFreedomViolationInvocationResult(
    val reason: String
) : InvocationResult()