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

interface IncrementalConsistencyChecker<E : ThreadEvent, X : Execution<E>> {

    /**
     * Represents the current state of the consistency checker.
     *
     * @see ConsistencyVerdict
     */
    val state: ConsistencyVerdict

    /**
     * Performs incremental consistency check,
     * verifying whether adding the given [event] to the current execution retains execution's consistency.
     * The implementation is allowed to approximate the check in the following sense.
     * - The check can be incomplete --- it can miss some inconsistencies.
     * - The check should be sound --- if an inconsistency is reported, the execution should indeed be inconsistent.
     * If an inconsistency was missed by an incremental check,
     * a later full consistency check via [check] function should detect this inconsistency.
     *
     * @return consistency verdict.
     * @see ConsistencyVerdict
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
    fun check(): Inconsistency?

    /**
     * Resets the internal state of the consistency checker to [execution].
     *
     * @return consistency verdict on the execution after the reset.
     */
    fun reset(execution: X): ConsistencyVerdict
}

/**
 * Represents the verdict of an incremental consistency check.
 * The verdict can be either: consistent, inconsistent, or unknown.
 */
sealed class ConsistencyVerdict {
    object Unknown : ConsistencyVerdict()
    object Consistent : ConsistencyVerdict()
    class  Inconsistent(val inconsistency: Inconsistency): ConsistencyVerdict()
}

val ConsistencyVerdict.inconsistency: Inconsistency?
    get() = when (this) {
        is ConsistencyVerdict.Inconsistent -> this.inconsistency
        else -> null
    }

fun ConsistencyVerdict.join(doCheck: () -> ConsistencyVerdict): ConsistencyVerdict {
    // if the inconsistency is already detected -- do not evaluate the second argument
    // and return inconsistency immediately
    if (this is ConsistencyVerdict.Inconsistent)
        return this
    // otherwise, evaluate the second argument to determine the result
    val other = doCheck()
    // if inconsistency is detected, return it
    if (other is ConsistencyVerdict.Inconsistent)
        return other
    // otherwise, return "consistent" verdict only if both arguments are "consistent",
    // if one of them is "unknown" -- then return "unknown"
    return when {
        this is ConsistencyVerdict.Consistent && other is ConsistencyVerdict.Consistent ->
            ConsistencyVerdict.Consistent
        else ->
            ConsistencyVerdict.Unknown
    }
}

abstract class AbstractIncrementalConsistencyChecker<E : ThreadEvent, X : Execution<E>>(
    execution: X
) : IncrementalConsistencyChecker<E, X> {

    protected var execution: X = execution
        private set

    final override var state: ConsistencyVerdict = ConsistencyVerdict.Unknown
        private set

    // `true` means a full consistency check was already performed and its result was cached;
    // `false` means the check was not performed or a new event was added since the last check
    private var fullCheckCached: Boolean = false

    final override fun check(event: E): ConsistencyVerdict {
        // reset check cache
        fullCheckCached = false
        // do case analysis
        when (state) {
            // skip the check if the checker is in unknown state
            is ConsistencyVerdict.Unknown -> return state
            // return inconsistency if it was detected earlier
            is ConsistencyVerdict.Inconsistent -> return state
            // otherwise, actually perform the incremental check
            else -> {
                state = doIncrementalCheck(event)
                return state
            }
        }
    }

    protected abstract fun doIncrementalCheck(event: E): ConsistencyVerdict

    final override fun check(): Inconsistency? {
        // if the full consistency check was already performed,
        // and there were no new events added, return the cached result
        if (fullCheckCached) {
            check(state !is ConsistencyVerdict.Unknown)
            return state.inconsistency
        }
        // return inconsistency if it was detected before by the incremental check
        if (state is ConsistencyVerdict.Inconsistent) {
            fullCheckCached = true
            return state.inconsistency!!
        }
        // otherwise do the full check
        state = when (val inconsistency = doCheck()) {
            is Inconsistency    -> ConsistencyVerdict.Inconsistent(inconsistency)
            else                -> ConsistencyVerdict.Consistent
        }
        // cache the result and return
        fullCheckCached = true
        return state.inconsistency
    }

    protected abstract fun doCheck(): Inconsistency?

    final override fun reset(execution: X): ConsistencyVerdict {
        this.execution = execution
        fullCheckCached = false
        state = ConsistencyVerdict.Consistent
        state = doReset()
        if (state !is ConsistencyVerdict.Unknown) {
            fullCheckCached = true
        }
        return state
    }

    protected abstract fun doReset(): ConsistencyVerdict

}

abstract class AbstractPartialIncrementalConsistencyChecker<E : ThreadEvent, X : Execution<E>>(
    execution: X,
    val checker: ConsistencyChecker<E, X>,
) : AbstractIncrementalConsistencyChecker<E, X>(execution) {

    override fun doCheck(): Inconsistency? {
        // the parent class should guarantee that at this point the state is
        // either "consistent" or "unknown".
        check(state !is ConsistencyVerdict.Inconsistent)
        // do a lightweight check before falling back to full consistency check
        return when (val verdict = doLightweightCheck()) {
            // if lightweight check returns verdict "consistent",
            // then the whole execution is consistent --- return null
            is ConsistencyVerdict.Consistent -> null
            // if inconsistency is detected, return it
            is ConsistencyVerdict.Inconsistent -> verdict.inconsistency
            // otherwise, do the full consistency check
            is ConsistencyVerdict.Unknown -> doFullCheck()
        }
    }

    protected abstract fun doLightweightCheck(): ConsistencyVerdict

    private fun doFullCheck(): Inconsistency? {
        return checker.check(execution)
    }

}

abstract class AbstractFullyIncrementalConsistencyChecker<E : ThreadEvent, X : Execution<E>>(
    execution: X
) : AbstractIncrementalConsistencyChecker<E, X>(execution) {

    override fun doCheck(): Inconsistency? {
        // if a checker is fully incremental,
        // it can detect inconsistencies precisely upon each event addition;
        // thus we should not reach this point while being in the unknown state
        check(state != ConsistencyVerdict.Unknown)
        return state.inconsistency
    }

}

class AggregatedIncrementalConsistencyChecker<E : ThreadEvent, X : Execution<E>>(
    execution: X,
    val incrementalConsistencyCheckers: List<IncrementalConsistencyChecker<E, X>>,
    val consistencyCheckers: List<ConsistencyChecker<E, X>>,
) : AbstractIncrementalConsistencyChecker<E, X>(execution) {

    override fun doIncrementalCheck(event: E): ConsistencyVerdict {
        var verdict: ConsistencyVerdict = ConsistencyVerdict.Consistent
        for (incrementalChecker in incrementalConsistencyCheckers) {
            verdict = verdict.join { incrementalChecker.check(event) }
        }
        return verdict
    }

    override fun doCheck(): Inconsistency? {
        for (incrementalChecker in incrementalConsistencyCheckers) {
            incrementalChecker.check()?.let { return it }
        }
        for (checker in consistencyCheckers) {
            checker.check(execution)?.let { return it }
        }
        return null
    }

    override fun doReset(): ConsistencyVerdict {
        var result: ConsistencyVerdict = ConsistencyVerdict.Consistent
        for (incrementalChecker in incrementalConsistencyCheckers) {
            val verdict = incrementalChecker.reset(execution)
            result = result.join { verdict }
        }
        return result
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