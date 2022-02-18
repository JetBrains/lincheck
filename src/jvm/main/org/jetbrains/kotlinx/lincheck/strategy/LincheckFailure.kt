/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.strategy

import org.jetbrains.kotlinx.lincheck.appendFailure
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.Trace

sealed class LincheckFailure(
    val scenario: ExecutionScenario,
    val trace: Trace?,
    val crashes: Int = 0,
    val partitions: Int = 0,
    val logFilename: String?
) {
    override fun toString() = StringBuilder().appendFailure(this).toString()
}

internal class IncorrectResultsFailure(
    scenario: ExecutionScenario,
    val results: ExecutionResult,
    trace: Trace? = null,
    crashes: Int = 0,
    partitions: Int = 0,
    logFilename: String? = null
) : LincheckFailure(scenario, trace, crashes, partitions, logFilename)

internal class DeadlockWithDumpFailure(
    scenario: ExecutionScenario,
    val threadDump: Map<Thread, Array<StackTraceElement>>,
    trace: Trace? = null,
    crashes: Int = 0,
    partitions: Int = 0,
    logFilename: String? = null
) : LincheckFailure(scenario, trace, crashes, partitions, logFilename)

internal class UnexpectedExceptionFailure(
    scenario: ExecutionScenario,
    val exception: Throwable,
    trace: Trace? = null,
    crashes: Int = 0,
    partitions: Int = 0,
    logFilename: String? = null
) : LincheckFailure(scenario, trace, crashes, partitions, logFilename)

internal class ValidationFailure(
    scenario: ExecutionScenario,
    val functionName: String,
    val exception: Throwable,
    trace: Trace? = null,
    crashes: Int = 0,
    partitions: Int = 0,
    logFilename: String? = null
) : LincheckFailure(scenario, trace, crashes, partitions, logFilename)

internal class ObstructionFreedomViolationFailure(
    scenario: ExecutionScenario,
    val reason: String,
    trace: Trace? = null,
    crashes: Int = 0,
    partitions: Int = 0,
    logFilename: String? = null
) : LincheckFailure(scenario, trace, crashes, partitions, logFilename)

internal class LivelockFailure(
    scenario: ExecutionScenario,
    trace: Trace? = null,
    crashes: Int = 0,
    partitions: Int = 0,
    logFilename: String? = null
) : LincheckFailure(scenario, trace, crashes, partitions, logFilename)

internal class TaskLimitExceededFailure(
    scenario: ExecutionScenario,
    trace: Trace? = null,
    crashes: Int = 0,
    partitions: Int = 0,
    logFilename: String? = null
) : LincheckFailure(scenario, trace, crashes, partitions, logFilename)

internal fun InvocationResult.toLincheckFailure(
    scenario: ExecutionScenario, trace: Trace? = null,
    crashes: Int = 0,
    partitions: Int = 0,
    logFilename: String? = null
) = when (this) {
    is DeadlockInvocationResult -> DeadlockWithDumpFailure(
        scenario,
        threadDump,
        trace,
        crashes,
        partitions,
        logFilename
    )
    is UnexpectedExceptionInvocationResult -> UnexpectedExceptionFailure(
        scenario,
        exception,
        trace,
        crashes,
        partitions,
        logFilename
    )
    is ValidationFailureInvocationResult -> ValidationFailure(
        scenario,
        functionName,
        exception,
        trace,
        crashes,
        partitions,
        logFilename
    )
    is ObstructionFreedomViolationInvocationResult -> ObstructionFreedomViolationFailure(
        scenario,
        reason,
        trace,
        crashes,
        partitions,
        logFilename
    )
    is LivelockInvocationResult -> LivelockFailure(scenario, trace, crashes, partitions, logFilename)
    is TaskLimitExceededResult -> TaskLimitExceededFailure(scenario, trace, crashes, partitions, logFilename)
    else -> error("Unexpected invocation result type: ${this.javaClass.simpleName}")
}