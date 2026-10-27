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

interface IncrementalConsistencyChecker<E : ThreadEvent, X : Execution<E>> : ExecutionTracker<E, X> {

    override fun onAdd(event: E) {
        check(event)
    }

    override fun onReset(execution: X) {
        reset(execution)
    }

    /**
     * Represents the current state of the consistency checker.
     *
     * @see ConsistencyState
     */
    val state: ConsistencyState

    /**
     * Performs incremental consistency check,
     * verifying whether adding the given [event] to the current execution retains execution's consistency.
     * The event must already belong to the execution, and relation trackers must have processed its addition.
     * The implementation is allowed to approximate the check in the following sense.
     * - The check can be incomplete --- it can miss some inconsistencies.
     * - The check should be sound --- if an inconsistency is reported, the execution should indeed be inconsistent.
     * If an inconsistency was missed by an incremental check,
     * a later full consistency check via [check] function should detect this inconsistency.
     *
     * @return the updated consistency state.
     * @see ConsistencyState
     */
    fun check(event: E): ConsistencyState

    /**
     * Performs full consistency check.
     * The check should be sound and complete:
     * an inconsistency should be reported if and only if the execution is indeed inconsistent.
     * Updates [state] to [ConsistencyState.Consistent] or [ConsistencyState.Inconsistent].
     *
     * @return `null` if execution remains consistent,
     *   otherwise returns non-null [Inconsistency] object
     *   representing the reason of inconsistency.
     */
    fun check(): Inconsistency?

    /**
     * Resets the internal state of the consistency checker to [execution].
     *
     * @return the consistency state after the reset.
     */
    fun reset(execution: X): ConsistencyState
}

/** The current knowledge of an execution's consistency. */
sealed class ConsistencyState {
    /** No consistency checks have established a result, typically after resetting the execution. */
    object Unknown : ConsistencyState()

    /** Lightweight checks passed, but a full consistency check is still required. */
    object PartlyConsistent : ConsistencyState()

    /** The execution is known to be completely consistent. */
    object Consistent : ConsistencyState()

    /** A check detected the given [inconsistency]. */
    class Inconsistent(val inconsistency: Inconsistency) : ConsistencyState()
}

val ConsistencyState.inconsistency: Inconsistency?
    get() = when (this) {
        is ConsistencyState.Inconsistent -> this.inconsistency
        else -> null
    }
