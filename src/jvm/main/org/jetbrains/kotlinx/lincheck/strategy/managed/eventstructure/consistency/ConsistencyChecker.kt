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

/**
 * Represents an inconsistency in the consistency check of an execution.
 */
abstract class Inconsistency

/**
 * Represents an exception that is thrown when an inconsistency is detected during execution.
 *
 * @param inconsistency The inconsistency that caused the exception.
 */
class InconsistentExecutionException(val inconsistency: Inconsistency) : Exception(inconsistency.toString())

fun interface ConsistencyChecker<E : ThreadEvent, X : Execution<E>> {
    fun check(execution: X): Inconsistency?
}

/**
 * Represents the state of an incremental consistency checker.
 * The consistency checker can be in one of the three states:
 * consistent, inconsistent, or unknown.
 */
enum class ConsistencyCheckerState { Consistent, Inconsistent, Unknown }

interface IncrementalConsistencyChecker<E : ThreadEvent, X : Execution<E>> {

    /**
     * Represents the current state of the consistency checker.
     */
    val state: ConsistencyCheckerState

    /**
     * Performs incremental consistency check,
     * verifying whether adding the given [event] to the current execution retains execution's consistency.
     * The implementation is allowed to approximate the check in the following sense.
     * - The check can be incomplete --- it can miss some inconsistencies.
     * - The check should be sound --- if an inconsistency is reported, the execution should indeed be inconsistent.
     * If an inconsistency was missed by an incremental check,
     * a subsequent full consistency check via [check] function should detect this inconsistency.
     *
     * @return `null` if execution remains consistent or if the consistency verdict is unknown,
     *   otherwise returns non-null [Inconsistency] object
     *   representing the reason of inconsistency.
     */
    fun check(event: E): Inconsistency?

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
    fun reset(execution: X)
}

abstract class AbstractIncrementalConsistencyChecker<E : ThreadEvent, X : Execution<E>>(execution: X)
    : IncrementalConsistencyChecker<E, X> {

    protected var execution: X = execution
        private set

    var inconsistency: Inconsistency? = null
        protected set

    private var consistent = true

    final override val state: ConsistencyCheckerState get() = when {
        consistent -> ConsistencyCheckerState.Consistent
        inconsistency != null -> ConsistencyCheckerState.Inconsistent
        else -> ConsistencyCheckerState.Unknown
    }

    protected fun setUnknownState() {
        check(inconsistency == null)
        consistent = false
    }

    final override fun check(event: E): Inconsistency? {
        // skip the check if the checker is in unknown state
        if (state == ConsistencyCheckerState.Unknown)
            return null
        // return inconsistency if it was detected before
        if (state == ConsistencyCheckerState.Inconsistent)
            return inconsistency!!
        doIncrementalCheck(event)
        return inconsistency
    }

    protected abstract fun doIncrementalCheck(event: E)

    final override fun check(): Inconsistency? {
        // if the checker is in a consistent state,
        // do lightweight check before doing full consistency check
        if (state == ConsistencyCheckerState.Consistent) {
            doLightweightCheck()
            // return null if the state remains consistent after lightweight check
            if (state == ConsistencyCheckerState.Consistent)
                return null
        }
        // return inconsistency if it was detected before
        if (state == ConsistencyCheckerState.Inconsistent)
            return inconsistency!!
        doFullCheck()
        consistent = (inconsistency == null)
        check(state != ConsistencyCheckerState.Unknown)
        return inconsistency
    }

    protected open fun doLightweightCheck() {}

    protected abstract fun doFullCheck()

    final override fun reset(execution: X) {
        this.execution = execution
        inconsistency = null
        consistent = true
        doReset()
    }

    protected abstract fun doReset()

}

class AggregatedIncrementalConsistencyChecker<E : ThreadEvent, X : Execution<E>>(
    execution: X,
    val incrementalConsistencyCheckers: List<IncrementalConsistencyChecker<E, X>>,
    val consistencyCheckers: List<ConsistencyChecker<E, X>>,
) : AbstractIncrementalConsistencyChecker<E, X>(execution) {

    override fun doIncrementalCheck(event: E) {
        for (incrementalChecker in incrementalConsistencyCheckers) {
            inconsistency = incrementalChecker.check(event)
            if (inconsistency != null)
                return
            if (incrementalChecker.state == ConsistencyCheckerState.Unknown)
                setUnknownState()
        }
        if (consistencyCheckers.isNotEmpty())
            setUnknownState()
    }

    override fun doFullCheck() {
        for (incrementalChecker in incrementalConsistencyCheckers) {
            inconsistency = incrementalChecker.check()
            if (inconsistency != null)
                return
        }
        for (checker in consistencyCheckers) {
            inconsistency = checker.check(execution)
            if (inconsistency != null)
                return
        }
    }

    override fun doReset() {
        for (incrementalChecker in incrementalConsistencyCheckers) {
            incrementalChecker.reset(execution)
        }
    }

}

fun<E : ThreadEvent, X : Execution<E>> aggregateConsistencyCheckers(
    execution: X,
    incrementalConsistencyCheckers: List<IncrementalConsistencyChecker<E, X>>,
    consistencyCheckers: List<ConsistencyChecker<E, X>>,
) = AggregatedIncrementalConsistencyChecker(
        execution,
        incrementalConsistencyCheckers,
        consistencyCheckers,
    )