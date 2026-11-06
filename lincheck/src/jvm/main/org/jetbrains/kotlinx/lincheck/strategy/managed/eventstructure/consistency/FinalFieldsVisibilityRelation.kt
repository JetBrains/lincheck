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
import org.jetbrains.kotlinx.lincheck.strategy.managed.NULL_OBJECT_NUMBER
import org.jetbrains.kotlinx.lincheck.strategy.managed.STATIC_OBJECT_NUMBER
import org.jetbrains.kotlinx.lincheck.strategy.managed.ValueID
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.lincheck.util.CachedRelation

/**
 * Relates freezes to events whose final-field views observe them, independently of ordinary happens-before.
 *
 * Does not do exact provenance tracking of observed objects, but instead approximates them conservatively:
 * reads observing the same object accumulate earlier views of the same object in their thread.
 * This approximation can exclude executions admitted by exact provenance tracking,
 * but it never admits executions that are not admitted by exact provenance tracking.
 *
 * Tracks clocks and dependency approximations before the final-field consistency checks run.
 */
class FinalFieldsVisibilityRelation(
    private var execution: Execution<AtomicThreadEvent>,
    private val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
) : CachedRelation<AtomicThreadEvent>, ExecutionTracker<AtomicThreadEvent, Execution<AtomicThreadEvent>> {

    var computed = execution.isEmpty()
        private set

    /**
     * Maps each event to its freeze clock.
     */
    val freezeClocks: Map<Event, VectorClock> get() = _freezeClocks
    private val _freezeClocks = mutableMapOf<Event, VectorClock>()

    private val _happensBeforeFreezeClocks = mutableMapOf<Event, VectorClock>()

    /**
     * Latest preceding reference read or allocation supplying a memory access's receiver.
     */
    val addressMap: Map<AtomicThreadEvent, AtomicThreadEvent> get() = _addressMap
    private val _addressMap = mutableMapOf<AtomicThreadEvent, AtomicThreadEvent>()

    /**
     * Latest preceding reference read or allocation supplying a reference write's value.
     */
    val dataMap: Map<AtomicThreadEvent, AtomicThreadEvent> get() = _dataMap
    private val _dataMap = mutableMapOf<AtomicThreadEvent, AtomicThreadEvent>()

    private val _finalReadsOnAddressChain = mutableMapOf<AtomicThreadEvent, List<AtomicThreadEvent>>()

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean =
        x.label is FreezeLabel && freezeClocks[y]?.observes(x.threadId, x.threadPosition) == true

    override fun onAdd(event: AtomicThreadEvent) {
        if (computed) add(event)
    }

    override fun onReset(execution: Execution<AtomicThreadEvent>) {
        this.execution = execution
        invalidate()
    }

    override fun compute() {
        if (computed) return
        // Enumeration order puts parents and dependencies before the events that use their clocks.
        for (event in execution.enumerationOrderSorted()) {
            add(event)
        }
        computed = true
    }

    override fun invalidate() {
        computed = false
        _freezeClocks.clear()
        _addressMap.clear()
        _dataMap.clear()
        _happensBeforeFreezeClocks.clear()
        _finalReadsOnAddressChain.clear()
    }

    /**
     * Returns all the freezes events affecting the given memory location.
     */
    fun freezes(location: MemoryLocation): List<AtomicThreadEvent> =
        memoryAccessEventIndex.getFreezes(location)

    /**
     * Latest write per thread in the freeze's HB prefix.
     */
    fun writesBeforeFreeze(location: MemoryLocation, freeze: AtomicThreadEvent): Sequence<AtomicThreadEvent> =
        memoryAccessEventIndex.getLatestWrites(location, freeze.happensBeforeClock)
            .filter { it.label is WriteAccessLabel }

    /**
     * The latest reference read or allocation that could have supplied this access's receiver.
     */
    fun approximateAddressDependency(event: AtomicThreadEvent): AtomicThreadEvent? {
        val label = event.label as? MemoryAccessLabel ?: return null
        val objectID = label.objectID
        if (objectID == NULL_OBJECT_NUMBER || objectID == STATIC_OBJECT_NUMBER) return null
        return latestReferenceEvent(event, objectID.toLong())
    }

    /**
     * The latest reference read or allocation that could have supplied this write's reference value.
     */
    fun approximateDataDependency(event: AtomicThreadEvent): AtomicThreadEvent? {
        val label = event.label as? WriteAccessLabel ?: return null
        val objectID = referencedObject(label.location.type, label.writeValue) ?: return null
        return latestReferenceEvent(event, objectID)
    }

    /**
     * Final reads among the [event] itself and the earlier reference reads contributing to its cumulative address view.
     *
     * Computed on first use and cached until [invalidate]:
     * the address chain of an event consists of the earlier events of its thread prefix only,
     * so adding new events never changes it.
     */
    fun finalReadsOnAddressChain(event: AtomicThreadEvent): List<AtomicThreadEvent> =
        _finalReadsOnAddressChain.getOrPut(event) {
            addressAncestors(event)
                .filter { (it.label as? ReadAccessLabel)?.location?.isFinal == true }
                .ifEmpty { emptyList() }
        }

    private fun addressAncestors(event: AtomicThreadEvent): List<AtomicThreadEvent> {
        val result = mutableListOf(event)
        val pending = ArrayDeque<AtomicThreadEvent>()
        val visited = mutableSetOf(event)
        addressMap[event]?.let { pending.addLast(it) }
        while (pending.isNotEmpty()) {
            val predecessor = pending.removeLast()
            if (!visited.add(predecessor)) continue
            result.add(predecessor)
            addressMap[predecessor]?.let { pending.addLast(it) }
            predecessor.label.referencedObject?.let { objectID ->
                latestReferenceEvent(predecessor, objectID)?.let { pending.addLast(it) }
            }
        }
        return result
    }

    private fun latestReferenceEvent(
        event: AtomicThreadEvent,
        objectID: ValueID,
    ): AtomicThreadEvent? =
        // strictly before the event: a reference read must not supply its own receiver or value
        memoryAccessEventIndex.getLatestReferencingEvent(objectID, event.threadId, event.threadPosition - 1)

    private fun add(event: AtomicThreadEvent) {
        approximateAddressDependency(event)?.let { _addressMap[event] = it }
        approximateDataDependency(event)?.let { _dataMap[event] = it }
        _happensBeforeFreezeClocks[event] = computeHappensBeforeFreezeClock(event)
        _freezeClocks[event] = computeFreezeClock(event)
    }

    private fun computeFreezeClock(event: AtomicThreadEvent): VectorClock {
        val label = event.label
        if (label is ReadAccessLabel && label.isResponse) {
            return computeReadFreezeClock(event, event.readsFrom)
        }
        val clock = _happensBeforeFreezeClocks.getValue(event).copy()
        if (label is WriteAccessLabel) {
            // merge address and data dependencies of the object this write carries
            addressMap[event]?.let { clock.merge(freezeClocks.getValue(it)) }
            dataMap[event]?.let { clock.merge(freezeClocks.getValue(it)) }
        }
        return clock
    }

    internal fun computeReadFreezeClock(read: AtomicThreadEvent, write: AtomicThreadEvent): VectorClock {
        // start from happens-before freeze clocks of the read event
        val clock = _happensBeforeFreezeClocks.getValue(read).copy()
        // the write also supplies every freeze that its future acquire could import
        clock.merge(freezeClocks.getValue(write))
        // merge address dependencies of the read event
        addressMap[read]?.let { clock.merge(freezeClocks.getValue(it)) }
        // merge address and data dependencies of the object the read is going to observe
        // (i.e., the object the write carries)
        val location = (read.label as ReadAccessLabel).location
        val value = write.label.asWriteAccessLabel(location)!!.writeValue
        referencedObject(location.type, value)?.let { objectID ->
            latestReferenceEvent(read, objectID)?.let {
                clock.merge(freezeClocks.getValue(it))
            }
        }
        return clock
    }

    private fun computeHappensBeforeFreezeClock(event: AtomicThreadEvent): VectorClock =
        projectToFreezes(event.happensBeforeClock)

    private fun projectToFreezes(clock: VectorClock): VectorClock {
        val result = MutableVectorClock()
        for (thread in execution.threadIDs) {
            val position = clock[thread]
            if (position < 0) continue
            val freezePosition = memoryAccessEventIndex.getLatestFreeze(thread, position)?.threadPosition ?: -1
            if (freezePosition >= 0) {
                result[thread] = freezePosition
            }
        }
        return result
    }

}
