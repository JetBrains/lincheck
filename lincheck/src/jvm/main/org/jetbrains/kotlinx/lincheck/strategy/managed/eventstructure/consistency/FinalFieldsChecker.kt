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

import org.jetbrains.kotlinx.lincheck.strategy.managed.MemoryLocation
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.kotlinx.lincheck.util.*

/** A stale reads-from candidate excluded by a freeze observed along an approximate dereference chain. */
class FinalFieldsViolation(
    val read: AtomicThreadEvent,
    val source: AtomicThreadEvent,
    val finalRead: AtomicThreadEvent,
    val freeze: AtomicThreadEvent,
    val interveningWrite: AtomicThreadEvent,
) : Inconsistency() {
    override fun toString(): String =
        "Final-field visibility violation: $read reads from $source, " +
        "before $interveningWrite protected by $freeze through $finalRead"
}

/** Checks final-field visibility using the execution's cached final-field viewfront. */
class FinalFieldsChecker(
    private var execution: MutableExtendedExecution,
) : IncrementalConsistencyChecker<AtomicThreadEvent, MutableExtendedExecution> {

    private val viewfront: FinalFieldsVisibilityRelation get() =
        execution.finalFieldsViewfront

    override var state: ConsistencyState = ConsistencyState.Unknown
        private set

    override fun check(event: AtomicThreadEvent): ConsistencyState {
        if (state is ConsistencyState.Inconsistent) return state

        if (!viewfront.computed) {
            state = ConsistencyState.Unknown
            return state
        }

        val inconsistency = doCheck(event)
        if (inconsistency != null) {
            state = ConsistencyState.Inconsistent(inconsistency)
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
        state = ConsistencyState.Unknown
        return state
    }

    override fun filterInconsistent(
        event: AtomicThreadEvent,
        candidates: Sequence<AtomicThreadEvent>
    ): Sequence<AtomicThreadEvent> {
        if (!viewfront.computed) return candidates
        // A revisit can repair the inconsistency.
        if (state is ConsistencyState.Inconsistent) return candidates

        return when (val label = event.label) {
            is ReadAccessLabel if (label.isRequest) ->
                filterWrites(event, candidates)
            is WriteAccessLabel ->
                filterReadRequests(event, candidates)
            else ->
                candidates
        }
    }

    private fun filterWrites(
        read: AtomicThreadEvent,
        candidates: Sequence<AtomicThreadEvent>
    ): Sequence<AtomicThreadEvent> {
        val frozenFields = freezesOnAddressChain(read)
        if (frozenFields.isEmpty()) return candidates

        val freezes = frozenFields.map { it.second }.distinct()
        val staleWritesByFreeze = mutableMapOf<AtomicThreadEvent, MutableVectorClock>()
        // The candidates contain all writes to this location. Summarize them once, instead of
        // searching for an intervening write separately for each possible reads-from source.
        val writes = candidates.onEach { write ->
            if (write.label is WriteAccessLabel) {
                val predecessors = write.happensBeforeClock.copy().apply {
                    // A protected write excludes its strict HB predecessors, but never itself.
                    set(write.threadId, write.threadPosition - 1)
                }
                for (freeze in freezes) {
                    if (happensBeforeOrder(write, freeze)) {
                        staleWritesByFreeze
                            .getOrPut(freeze) { MutableVectorClock() }
                            .merge(predecessors)
                    }
                }
            }
        }.toList()

        return writes.asSequence().filter { write ->
            val clock = viewfront.computeReadFreezeClock(read, write)
            frozenFields.none { (finalRead, freeze) ->
                val staleWrites = staleWritesByFreeze[freeze] ?: return@none false
                observesFreeze(read, clock, finalRead, freeze) && (
                    write.label.isInitializingWriteAccess() ||
                    staleWrites.observes(write.threadId, write.threadPosition)
                )
            }
        }
    }

    private fun filterReadRequests(
        write: AtomicThreadEvent,
        candidates: Sequence<AtomicThreadEvent>
    ): Sequence<AtomicThreadEvent> {
        val location = (write.label as WriteAccessLabel).location
        // Here the candidates are read requests.
        // The reads-from and location are shared,
        // so each freeze needs at most one lookup in the write index for the entire candidate batch.
        val isStaleByFreeze = mutableMapOf<AtomicThreadEvent, Boolean>()
        return candidates.filter { request ->
            val clock = viewfront.computeReadFreezeClock(request, write)
            for ((finalRead, freeze) in freezesOnAddressChain(request)) {
                // if read does not observe the freeze, final-field visibility does not apply
                if (!observesFreeze(request, clock, finalRead, freeze)) continue
                val isStale = isStaleByFreeze.getOrPut(freeze) {
                    findInterveningWrite(write, location, freeze) != null
                }
                if (isStale) return@filter false
            }
            return@filter true
        }
    }

    private fun findInconsistency(): FinalFieldsViolation? {
        viewfront.compute()
        for (event in execution.enumerationOrderSorted()) {
            doCheck(event)?.let { return it }
        }
        return null
    }

    private fun doCheck(read: AtomicThreadEvent): FinalFieldsViolation? {
        val label = read.label as? ReadAccessLabel ?: return null
        if (!label.isResponse) return null
        return doCheck(read, read.readsFrom, viewfront.freezeClocks.getValue(read))
    }

    private fun doCheck(
        read: AtomicThreadEvent,
        readsFrom: AtomicThreadEvent,
        freezeClock: VectorClock
    ): FinalFieldsViolation? {
        val location = (read.label as ReadAccessLabel).location
        for ((finalRead, freeze) in freezesOnAddressChain(read)) {
            if (!observesFreeze(read, freezeClock, finalRead, freeze)) continue
            val write = findInterveningWrite(readsFrom, location, freeze) ?: continue
            return FinalFieldsViolation(read, readsFrom, finalRead, freeze, write)
        }
        return null
    }

    private fun freezesOnAddressChain(read: AtomicThreadEvent): List<Pair<AtomicThreadEvent, AtomicThreadEvent>> =
        viewfront.finalReadsOnAddressChain(read).flatMap { finalRead ->
            val location = (finalRead.label as ReadAccessLabel).location
            viewfront.freezes(location).map { freeze -> finalRead to freeze }
        }

    private fun observesFreeze(
        read: AtomicThreadEvent,
        freezeClock: VectorClock,
        finalRead: AtomicThreadEvent,
        freeze: AtomicThreadEvent
    ): Boolean =
        (if (finalRead == read) freezeClock else viewfront.freezeClocks.getValue(finalRead))
            .observes(freeze.threadId, freeze.threadPosition)

    private fun findInterveningWrite(
        write: AtomicThreadEvent,
        location: MemoryLocation,
        freeze: AtomicThreadEvent
    ): AtomicThreadEvent? {
        // Earlier writes in each thread happen-before its latest write in the freeze's HB prefix,
        // so checking that latest write preserves every possible stale-read witness.
        for (otherWrite in viewfront.writesBeforeFreeze(location, freeze)) {
            // Allocation labels stand for default writes even without an ordinary cross-thread HB edge.
            if (write != otherWrite && (write.label.isInitializingWriteAccess() || happensBeforeOrder(write, otherWrite))) {
                return otherWrite
            }
        }
        return null
    }
}
