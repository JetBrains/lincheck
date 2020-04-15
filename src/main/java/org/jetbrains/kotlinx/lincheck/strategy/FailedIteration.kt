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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*

sealed class FailedIteration(
    val scenario: ExecutionScenario
) {
    override fun toString() = StringBuilder().appendFailedIteration(this).toString()
}

internal class IncorrectResultsFailedIteration(
    scenario: ExecutionScenario,
    val results: ExecutionResult
) : FailedIteration(scenario)

internal class DeadlockedWithDumpFailedIteration(
    scenario: ExecutionScenario,
    val threadDump: Map<Thread, Array<StackTraceElement>>
) : FailedIteration(scenario)

internal class UnexpectedExceptionFailedIteration(
    scenario: ExecutionScenario,
    val exception: Throwable
) : FailedIteration(scenario)

internal fun InvocationResult.toFailedIteration(scenario: ExecutionScenario) = when (this) {
    is DeadlockInvocationResult -> DeadlockedWithDumpFailedIteration(scenario, threadDump)
    is UnexpectedExceptionInvocationResult -> UnexpectedExceptionFailedIteration(scenario, exception)
    else -> error("Unexpected invocation result type: ${this.javaClass.simpleName}")
}