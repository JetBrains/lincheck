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

// TODO: restore atomicity violation information
class ReadModifyWriteAtomicityViolation(/*val write1: Event, val write2: Event*/) : Inconsistency() {
    override fun toString(): String {
        return "Atomicity violation detected"
    }
}

/** Checks that each write in an atomic-update chain reads from its immediate predecessor. */
class ReadModifyWriteAtomicityChecker(
    private var execution: MutableExtendedExecution,
) : IncrementalConsistencyChecker<AtomicThreadEvent, MutableExtendedExecution> {

    override var state: ConsistencyState = ConsistencyState.Unknown
        private set

    override fun check(event: AtomicThreadEvent): ConsistencyState {
        if (state !is ConsistencyState.Inconsistent) state = checkAtomicity()
        return state
    }

    override fun check(): Inconsistency? {
        if (state is ConsistencyState.Unknown) state = checkAtomicity()
        return state.inconsistency
    }

    override fun reset(execution: MutableExtendedExecution): ConsistencyState {
        this.execution = execution
        state = ConsistencyState.Unknown
        return state
    }

    private fun checkAtomicity(): ConsistencyState {
        execution.readModifyWriteOrder.compute()
        return if (execution.readModifyWriteOrder.isConsistent()) ConsistencyState.Consistent
        else ConsistencyState.Inconsistent(ReadModifyWriteAtomicityViolation())
    }
}
