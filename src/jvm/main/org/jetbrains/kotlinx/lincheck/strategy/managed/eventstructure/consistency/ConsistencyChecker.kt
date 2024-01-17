/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.kotlinx.lincheck.utils.*

/**
 * Represents the verdict of an execution's consistency check.
 */
interface ConsistencyVerdict

/**
 * Represents the verdict of a successful consistency check and carries out witness of the consistency.
 */
interface ConsistencyWitness : ConsistencyVerdict

/**
 * Represents an inconsistency in the consistency check of an execution.
 */
interface Inconsistency : ConsistencyVerdict

// {
//     /**
//      * Indicates whether the consistency check was successful,
//      * that is whether the checked execution is consistent or not.
//      */
//     val isConsistent: Boolean
//
//     /**
//      * Returns a textual explanation for the consistency verdict.
//      * In particular, in case of inconsistency, should return the string describing the reason of inconsistency.
//      *
//      * @return The explanation for the consistency verdict if there is one, or null if no explanation is available.
//      */
//     fun explain(): String?
// }

class InconsistentExecutionException(val inconsistency: Inconsistency) : Exception(inconsistency.toString())

fun interface ConsistencyChecker<E : ThreadEvent> {
    fun check(execution: Execution<E>): ConsistencyVerdict
}

interface IncrementalConsistencyChecker<E : ThreadEvent> {
    /**
     * Performs incremental consistency check,
     * verifying whether adding the given [event] to the current execution retains execution's consistency.
     * The implementation is allowed to approximate the check in the following sense.
     * - The check can be incomplete --- it can miss some inconsistencies.
     * - The check should be sound --- if an inconsistency is reported, the execution should indeed be inconsistent.
     * If an inconsistency was missed by an incremental check,
     * a subsequent full consistency check via [check] function should detect this inconsistency.
     *
     * @return consistency verdict:
     *  - an instance of [ConsistencyWitness] if the execution remains consistent,
     *  - an instance of [Inconsistency] is the execution becomes inconsistent.
     */
    fun check(event: E): ConsistencyVerdict

    /**
     * Performs full consistency check.
     * The check should be sound and complete:
     * an inconsistency should be reported if and only if the execution is indeed inconsistent.
     *
     * @return `null` if execution remains consistent,
     *   otherwise returns non-null [Inconsistency] object
     *   representing the reason of inconsistency.
     */
    fun check(): ConsistencyVerdict

    /**
     * Resets the internal state of consistency checker to [execution].
     */
    fun reset(execution: Execution<E>)
}

private class AggregatedIncrementalConsistencyChecker<E : ThreadEvent>(
    val incrementalConsistencyCheckers: List<IncrementalConsistencyChecker<E>>,
    val consistencyCheckers: List<ConsistencyChecker<E>>,
) : IncrementalConsistencyChecker<E> {

    private var execution: Execution<E> = executionOf()
    private var inconsistency: Inconsistency? = null

    override fun check(event: E): ConsistencyVerdict {
        if (inconsistency != null)
            return inconsistency!!
        val witnesses = mutableListOf<ConsistencyWitness>()
        for (incrementalChecker in incrementalConsistencyCheckers) {
            val verdict = incrementalChecker.check(event)
            if (verdict is Inconsistency) {
                inconsistency = verdict
                return verdict
            }
            check(verdict is ConsistencyWitness)
            witnesses.add(verdict)
        }
        // TODO: implement amortized checks for full-consistency checkers?
        return AggregatedConsistencyWitness(witnesses)
    }

    override fun check(): ConsistencyVerdict {
        if (inconsistency != null)
            return inconsistency!!
        val witnesses = mutableListOf<ConsistencyWitness>()
        for (incrementalChecker in incrementalConsistencyCheckers) {
            val verdict = incrementalChecker.check()
            if (verdict is Inconsistency) {
                inconsistency = verdict
                return verdict
            }
            check(verdict is ConsistencyWitness)
            witnesses.add(verdict)
        }
        for (checker in consistencyCheckers) {
            val verdict = checker.check(execution)
            if (verdict is Inconsistency) {
                inconsistency = verdict
                return verdict
            }
            check(verdict is ConsistencyWitness)
            witnesses.add(verdict)
        }
        return AggregatedConsistencyWitness(witnesses)
    }

    override fun reset(execution: Execution<E>) {
        this.execution = execution
        this.inconsistency = null
        for (incrementalChecker in incrementalConsistencyCheckers) {
            incrementalChecker.reset(execution)
        }
    }

}

fun<E : ThreadEvent> aggregateConsistencyCheckers(
    incrementalConsistencyCheckers: List<IncrementalConsistencyChecker<E>>,
    consistencyCheckers: List<ConsistencyChecker<E>>,
) : IncrementalConsistencyChecker<E> =
    AggregatedIncrementalConsistencyChecker(
        incrementalConsistencyCheckers,
        consistencyCheckers,
    )

class AggregatedConsistencyWitness(val witnesses: List<ConsistencyWitness>) : ConsistencyWitness