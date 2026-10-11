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
import org.jetbrains.lincheck.util.ensure


abstract class SequentialConsistencyViolation : Inconsistency()

class MemoryModelConsistencyChecker(
    val memoryModel: MemoryModel,
    checkReleaseAcquireConsistency: Boolean = true,
    checkCoherence: Boolean = true,
) : ConsistencyChecker<AtomicThreadEvent, MutableExtendedExecution> {

    private val releaseAcquireChecker : ReleaseAcquireConsistencyChecker? =
        if (checkReleaseAcquireConsistency) ReleaseAcquireConsistencyChecker() else null

    private val coherenceChecker : CoherenceChecker? =
        if (checkCoherence) CoherenceChecker() else null

    override fun check(execution: MutableExtendedExecution): Inconsistency? {
        check(!execution.executionOrder.computed)
        releaseAcquireChecker?.check(execution)
            ?.let { return it }
        coherenceChecker?.check(execution)
            ?.let { return it }
        check(execution.executionOrder.computed)
        val executionOrder = execution.executionOrder
            .ensure { it.isConsistent() }

        // TODO: The current coherence checker is just for RA, we need to seperate the SC coherence from RA coherence
        if (memoryModel == MemoryModel.SequentialConsistency) {
            SequentialConsistencyReplayer().ensure {
                it.replay(executionOrder.ordering) != null
            }
        }
        return null
    }
}

class CoherenceViolation : SequentialConsistencyViolation() {
    override fun toString(): String {
        // TODO: what information should we display to help identify the cause of inconsistency?
        return "Sequential consistency coherence violation detected"
    }
}

class IncrementalMemoryModelConsistencyChecker(
    execution: MutableExtendedExecution,
    memoryModel: MemoryModel,
    checkReleaseAcquireConsistency: Boolean = true,
) : AbstractPartialIncrementalConsistencyChecker<AtomicThreadEvent, MutableExtendedExecution>(
    execution = execution,
    checker = MemoryModelConsistencyChecker(
        memoryModel,
        checkReleaseAcquireConsistency,
    )
) {

    private val lockConsistencyChecker = LockConsistencyChecker()

    override fun doIncrementalCheck(event: AtomicThreadEvent): ConsistencyState =
        if (execution.executionOrder.computed) ConsistencyState.PartlyConsistent else ConsistencyState.Unknown

    override fun doLightweightCheck(): ConsistencyState {
        // TODO: extract into separate checker
        lockConsistencyChecker.check(execution)?.let { inconsistency ->
            return ConsistencyState.Inconsistent(inconsistency)
        }
        // check by trying to replay execution order
        if (state is ConsistencyState.Consistent || state is ConsistencyState.PartlyConsistent) {
            check(execution.executionOrder.computed)
            val replayer = SequentialConsistencyReplayer()
            val executionOrder = execution.executionOrder
            if (replayer.replay(executionOrder.ordering) != null) {
                // if replay is successful, return "consistent" verdict
                return ConsistencyState.Consistent
            }
        }
        // if we end up in an unknown state, reset the execution order,
        // so it can be re-computed by the full consistency check
        execution.executionOrder.invalidate()
        return ConsistencyState.Unknown
    }

    override fun doReset(): ConsistencyState =
        if (execution.executionOrder.computed) ConsistencyState.PartlyConsistent else ConsistencyState.Unknown

}


class SequentialConsistencyApproximationInconsistency : SequentialConsistencyViolation() {
    override fun toString(): String {
        // TODO: what information should we display to help identify the cause of inconsistency?
        return "Approximate sequential inconsistency detected"
    }
}
