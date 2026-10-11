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

// TODO: include the cycle in the writes-before relation.
class ReleaseAcquireInconsistency : Inconsistency() {
    override fun toString(): String = "Release/Acquire inconsistency detected"
}

/** Checks executions in which all memory accesses have release/acquire semantics. */
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
        val atomicity = atomicityChecker.check(event)
        state = if (atomicity is ConsistencyState.Inconsistent) atomicity else ConsistencyState.PartlyConsistent
        return state
    }

    override fun check(): Inconsistency? {
        if (state is ConsistencyState.Consistent || state is ConsistencyState.Inconsistent) return state.inconsistency
        val inconsistency = findInconsistency()
        state = if (inconsistency == null) ConsistencyState.Consistent else ConsistencyState.Inconsistent(inconsistency)
        return inconsistency
    }

    override fun reset(execution: MutableExtendedExecution): ConsistencyState {
        this.execution = execution
        atomicityChecker.reset(execution)
        state = ConsistencyState.Unknown
        return state
    }

    private fun findInconsistency(): Inconsistency? {
        atomicityChecker.check()?.let { return it }
        lockChecker.check(execution)?.let { return it }
        val executionOrder = execution.executionOrder
        if (executionOrder.computed && SequentialConsistencyReplayer().replay(executionOrder.ordering) != null) {
            return null
        }
        execution.writesBeforeOrder.compute()
        if (!execution.writesBeforeOrder.isConsistent()) return ReleaseAcquireInconsistency()
        coherenceChecker.check(execution)?.let { return it }
        return null
    }
}
