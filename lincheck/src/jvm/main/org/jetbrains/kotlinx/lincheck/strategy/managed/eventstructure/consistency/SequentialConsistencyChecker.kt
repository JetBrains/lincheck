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

import org.jetbrains.kotlinx.lincheck.strategy.managed.MemoryLocation
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.lincheck.util.*

abstract class SequentialConsistencyViolation : Inconsistency()

/**
 * Sequential consistency checker.
 *
 * Assumes that all memory accesses in [execution] have sequentially consistent semantics.
 */
class SequentialConsistencyChecker(
    private var execution: MutableExtendedExecution,
) : IncrementalConsistencyChecker<AtomicThreadEvent, MutableExtendedExecution> {

    companion object {
        private val replaySanityChecksEnabled =
            System.getProperty("lincheck.sc.checkReplay", "true").toBoolean()
    }

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

        // check if the new event is a consistent extension of the existing execution;
        // this check is performed via the execution order
        val executionOrder = execution.executionOrder
        if (!executionOrder.computed) {
            state = ConsistencyState.Unknown
            return state
        }
        if (!executionOrder.consistent) {
            executionOrder.invalidate()
            // the coherence order is extended along the execution order,
            // so it is invalidated along with it
            execution.coherenceOrder.invalidate()
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
    ): Sequence<AtomicThreadEvent> = when (val label = event.label) {
        is ReadAccessLabel if label.isRequest ->
            filterReadSynchronizationCandidates(event, candidates)

        is WriteAccessLabel if execution.memoryAccessEventIndex.isReadWriteRaceFree(label.location) ->
            sequenceOf()

        else -> candidates
    }

    private fun filterReadSynchronizationCandidates(
        event: AtomicThreadEvent,
        candidates: Sequence<AtomicThreadEvent>
    ): Sequence<AtomicThreadEvent> {
        val label = event.label as ReadAccessLabel
        if (execution.memoryAccessEventIndex.isRaceFree(label.location)) {
            val lastWrite = execution.memoryAccessEventIndex.getLastWrite(label.location)!!
            return sequenceOf(lastWrite)
        }
        val threadReads = execution[event.threadId]!!.filter {
            it.label.isResponse && (it.label as? ReadAccessLabel)?.location == label.location
        }
        val lastSeenWrite = threadReads.lastOrNull()?.readsFrom
        val staleWrites = threadReads
            .map { it.readsFrom }
            .filter { it != lastSeenWrite }
            .distinct()
        val eventFrontier = execution.calculateFrontier(event.causalityClock)
        val racyWrites = calculateRacyWrites(label.location, eventFrontier)
        return candidates.filter {
            !racyWrites.any { write -> causalityOrder(it, write) } &&
            !staleWrites.any { write -> causalityOrder.orEqual(it, write) }
        }
    }

    private fun calculateRacyWrites(
        location: MemoryLocation,
        observation: ExecutionFrontier<AtomicThreadEvent>
    ): List<ThreadEvent> {
        val writes = observation.events.mapNotNull { event ->
            event.ensure { it in execution }.pred(inclusive = true) {
                it.label.asMemoryAccessLabel(location)?.takeIf { label -> label.isWrite } != null
            } as? AtomicThreadEvent
        }
        return writes.filter { write ->
            !writes.any { other -> happensBeforeOrder(write, other) }
        }
    }

    private fun findInconsistency(): Inconsistency? {
        // First, check atomicity and locks.
        atomicityChecker.check()?.let { return it }
        lockChecker.check(execution)?.let { return it }

        // The cached order preserves happens-before and passes the read, RMW and wait checks.
        // Lock uniqueness above makes each mutex's critical sections a single happens-before chain,
        // so replay is only a diagnostic here, as it is after coherence search.
        val executionOrder = execution.executionOrder
        if (execution.coherenceOrder.computed && execution.coherenceOrder.consistent &&
            executionOrder.computed && executionOrder.consistent) {
            checkReplay()
            return null
        }

        // Otherwise, first try to construct the writes-before order:
        // if this construction fails, then the execution is not Release-Acquire consistent,
        // which implies it is also not sequentially consistent
        execution.writesBeforeOrder.compute()
        if (!execution.writesBeforeOrder.consistent) {
            return ReleaseAcquireInconsistency()
        }

        // TODO: use `sequentialConsistencyApproximationOrder` here:
        //   a cycle in it detects the violation before the costly coherence order search,
        //   otherwise it can serve as the initial approximation of the execution order.

        // If the writes-before order is consistent, then try to build coherence order
        // (total order on write events per each memory location).
        // Having a coherence order, sequential consistency order can be computed as
        // the union of the program-order, reads-from, coherence-order and reads-before relations:
        //   `(po + rf + mo + rb)`;
        // If this relation is acyclic, then the execution is sequentially consistent.
        // Note that `coherenceChecker` internally updates `executionOrder`
        // with the found sequential consistency ordering.
        execution.coherenceOrder.invalidate() // to trigger a new coherence order search
        coherenceChecker.check(execution)?.let { return it }
        checkReplay()

        return null
    }

    // The witness and lock-source checks establish consistency; replay only checks the implementation.
    private fun checkReplay() {
        if (replaySanityChecksEnabled) {
            check(SequentialConsistencyReplayer().replayInPlace(execution.executionOrder.ordering) != null) {
                "Computed sequential consistency order cannot be replayed"
            }
        }
    }
}
