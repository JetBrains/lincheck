/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

abstract class Inconsistency

class InconsistentExecutionException(val reason: Inconsistency): Exception(reason.toString())

fun interface ConsistencyChecker {
    fun check(execution: Execution): Inconsistency?
}

interface IncrementalConsistencyChecker {
    /**
     * Performs incremental consistency check,
     * verifying whether adding the given [event] to the current execution retains execution's consistency.
     * The implementation is allowed to approximate the check in the following sense.
     * - The check can be incomplete --- it can miss some inconsistencies.
     * - The check should be sound --- if an inconsistency is reported, the execution should indeed be inconsistent.
     * However, if an inconsistency was missed by an incremental check,
     * a subsequent full consistency check via [check] function should detect this inconsistency.
     *
     * @return `null` if execution remains consistent,
     *   otherwise returns non-null [Inconsistency] object
     *   representing the reason of inconsistency.
     */
    fun check(event: Event): Inconsistency?

    /**
     * Performs full consistency check.
     * The check should be sound and complete:
     * an inconsistency should be reported if and only if the execution is indeed inconsistent.
     *
     * @return `null` if execution remains consistent,
     *   otherwise returns non-null [Inconsistency] object
     *   representing the reason of inconsistency.
     */
    fun check(): Inconsistency?

    /**
     * Resets the internal state of consistency checker to [execution].
     */
    fun reset(execution: Execution)
}

private class AggregatedIncrementalConsistencyChecker(
    val incrementalConsistencyCheckers: List<IncrementalConsistencyChecker>,
    val consistencyCheckers: List<ConsistencyChecker>,
) : IncrementalConsistencyChecker {

    private var execution: Execution = executionOf()

    override fun check(event: Event): Inconsistency? {
        var inconsistency: Inconsistency? = null
        for (incrementalChecker in incrementalConsistencyCheckers) {
            incrementalChecker.check(event)?.also {
                if (inconsistency == null)
                    inconsistency = it
            }
        }
        // TODO: implement amortized checks for full-consistency checkers?
        return inconsistency
    }

    override fun check(): Inconsistency? {
        var inconsistency: Inconsistency? = null
        for (incrementalChecker in incrementalConsistencyCheckers) {
            incrementalChecker.check()?.also {
                if (inconsistency == null)
                    inconsistency = it
            }
        }
        for (checker in consistencyCheckers) {
            if (inconsistency != null)
                break
            inconsistency = checker.check(execution)
        }
        return inconsistency
    }

    override fun reset(execution: Execution) {
        this.execution = execution
        for (incrementalChecker in incrementalConsistencyCheckers) {
            incrementalChecker.reset(execution)
        }
    }

}

fun aggregateConsistencyCheckers(
    incrementalConsistencyCheckers: List<IncrementalConsistencyChecker>,
    consistencyCheckers: List<ConsistencyChecker>,
) : IncrementalConsistencyChecker =
    AggregatedIncrementalConsistencyChecker(
        incrementalConsistencyCheckers,
        consistencyCheckers,
    )