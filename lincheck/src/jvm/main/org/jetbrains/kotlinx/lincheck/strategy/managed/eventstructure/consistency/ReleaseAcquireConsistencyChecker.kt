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
import org.jetbrains.lincheck.util.Logger

// TODO: include the cycle in the writes-before relation.
class ReleaseAcquireInconsistency : Inconsistency() {
    override fun toString(): String = "Release/Acquire inconsistency detected"
}

/**
 * Release-Acquire consistency checker.
 *
 * Assumes that all memory accesses in [execution] have release/acquire semantics.
 *
 * The check is optimistically incremental: once a full check computes the coherence order,
 * it is extended with the newly added events (see [CoherenceOrder]),
 * so the following full checks reuse it instead of recomputing the writes-before order.
 */
class ReleaseAcquireConsistencyChecker(
    private var execution: MutableExtendedExecution,
) : IncrementalConsistencyChecker<AtomicThreadEvent, MutableExtendedExecution> {

    private val atomicityChecker = ReadModifyWriteAtomicityChecker(execution)
    private val lockChecker = LockConsistencyChecker()
    private val coherenceChecker = CoherenceChecker()

    override var state: ConsistencyState = ConsistencyState.Unknown
        private set

    override fun check(event: AtomicThreadEvent): ConsistencyState {
        if (state is ConsistencyState.Inconsistent) return state

        // check for atomicity violations first
        val atomicityState = atomicityChecker.check(event)
        if (atomicityState is ConsistencyState.Inconsistent) {
            state = atomicityState
            return state
        }

        // check if the new event is a consistent extension of the coherence order;
        // note coherence consistence has already been checked in `CoherenceOrder::onAdd`,
        // so we only need to check the flag
        val coherenceOrder = execution.coherenceOrder
        if (!coherenceOrder.computed || !coherenceOrder.consistent) {
            state = ConsistencyState.Unknown
            return state
        }

        state = ConsistencyState.PartlyConsistent
        return state
    }

    override fun check(): Inconsistency? {
        if (state is ConsistencyState.Consistent) return null
        if (state is ConsistencyState.Inconsistent) return state.inconsistency
        val inconsistency = findInconsistency()
        if (inconsistency == null) {
            state = ConsistencyState.Consistent
        } else {
            state = ConsistencyState.Inconsistent(inconsistency)
        }
        return inconsistency
    }

    override fun reset(execution: MutableExtendedExecution): ConsistencyState {
        this.execution = execution
        atomicityChecker.reset(execution)
        state = ConsistencyState.Unknown
        return state
    }

    override fun filterInconsistent(
        event: AtomicThreadEvent,
        candidates: Sequence<AtomicThreadEvent>
    ): Sequence<AtomicThreadEvent> {
        val label = event.label
        return if (label is ReadAccessLabel && label.isRequest) filterOverwrittenWrites(execution, event, candidates)
        else candidates
    }

    private fun findInconsistency(): Inconsistency? {
        // First, check atomicity and locks.
        atomicityChecker.check()?.let { return it }
        lockChecker.check(execution)?.let { return it }

        // A coherence order that is still valid after the incremental extensions witnesses consistency.
        val coherenceOrder = execution.coherenceOrder
        if (coherenceOrder.computed && coherenceOrder.consistent) {
            return null
        }

        // The execution is release-acquire consistent iff the writes-before relation is acyclic:
        // any linearization of it, keeping read-modify-write chains contiguous, is a valid coherence order.
        // Unlike sequential consistency, there is no total execution order to replay,
        // since release-acquire does not guarantee a single total order with "read-last-write" semantics.
        execution.writesBeforeOrder.compute()
        if (!execution.writesBeforeOrder.consistent) {
            return ReleaseAcquireInconsistency()
        }

        // Compute the coherence order, to be extended incrementally with the newly added events.
        coherenceOrder.invalidate() // to trigger a new coherence order search
        coherenceChecker.check(execution)?.let { inconsistency ->
            // Under release-acquire, the search always succeeds once the writes-before order is consistent,
            // so a failure indicates a bug; withdraw the execution as inconsistent instead of failing the test.
            Logger.error { "Release-acquire coherence order search failed despite consistent writes-before order" }
            return inconsistency
        }

        return null
    }
}
