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

    private var consistent = false
    private var checked = false

    final override val state: ConsistencyCheckerState get() = when {
        consistent -> ConsistencyCheckerState.Consistent
        inconsistency != null -> ConsistencyCheckerState.Inconsistent
        else -> ConsistencyCheckerState.Unknown
    }

    init {
        check(state == ConsistencyCheckerState.Unknown)
    }

    protected fun setUnknownState() {
        check(inconsistency == null)
        consistent = false
        checked = false
    }

    final override fun check(event: E): Inconsistency? {
        // reset check cache
        checked = false
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
        // return inconsistency if it was detected before
        if (state == ConsistencyCheckerState.Inconsistent)
            return inconsistency!!
        // return if consistency was already checked
        if (checked)
            return null
        doCheck()
        checked = true
        consistent = (inconsistency == null)
        return inconsistency
    }

    protected abstract fun doCheck()

    final override fun reset(execution: X) {
        this.execution = execution
        inconsistency = null
        consistent = true
        checked = false
        doReset()
    }

    protected abstract fun doReset()

}

abstract class AbstractPartialIncrementalConsistencyChecker<E : ThreadEvent, X : Execution<E>>(
    execution: X,
    val checker: ConsistencyChecker<E, X>,
) : AbstractIncrementalConsistencyChecker<E, X>(execution) {

    override fun doCheck() {
        // if the checker is in a consistent state,
        // do a lightweight check before falling back to full consistency check
        doLightweightCheck()
        if (state == ConsistencyCheckerState.Unknown) {
            doFullCheck()
        }
    }

    protected abstract fun doLightweightCheck()

    private fun doFullCheck() {
        inconsistency = checker.check(execution)
    }

}

abstract class AbstractFullyIncrementalConsistencyChecker<E : ThreadEvent, X : Execution<E>>(execution: X)
    : AbstractIncrementalConsistencyChecker<E, X>(execution) {

    override fun doCheck() {
        // if a checker is fully incremental,
        // it can detect inconsistencies precisely upon each event addition;
        // thus we should not reach this point while being in the unknown state
        check(state != ConsistencyCheckerState.Unknown)
    }

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
    }

    override fun doCheck() {
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