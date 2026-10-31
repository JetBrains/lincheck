/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*

/**
 * Checks the Relaxed Java Memory Model (JMM) consistency for executions with mixed memory-access orderings.
 * Reuses a coherence order whose volatile PSC order is acyclic while each added read observes the latest write.
 */
class JmmConsistencyChecker(
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

    private fun findInconsistency(): Inconsistency? {
        // First, check atomicity and locks.
        atomicityChecker.check()?.let { return it }
        lockChecker.check(execution)?.let { return it }

        // A coherence order that is still valid after the incremental extensions witnesses consistency.
        val coherenceOrder = execution.coherenceOrder
        if (coherenceOrder.computed && coherenceOrder.consistent) return null

        // Try to construct the writes-before order:
        // if this construction fails, then the execution is not JMM consistent either.
        execution.writesBeforeOrder.compute()
        if (!execution.writesBeforeOrder.consistent) {
            return ReleaseAcquireInconsistency()
        }

        // If the writes-before order is consistent, then try to build coherence order
        // (total order on write events per each memory location).
        // In addition, JMM requires a coherence order to induce an acyclic PSC order of volatile accesses
        // (see `SequentialConsistencyMixedAccessRelation`).
        execution.coherenceOrder.invalidate() // to trigger a new coherence order search
        coherenceChecker.check(execution)?.let { return it }

        return null
    }
}
