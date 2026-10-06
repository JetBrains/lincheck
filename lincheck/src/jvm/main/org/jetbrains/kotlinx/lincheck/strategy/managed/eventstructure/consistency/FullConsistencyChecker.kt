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

import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.kotlinx.lincheck.util.MutableThreadMap
import org.jetbrains.kotlinx.lincheck.util.VectorClock
import org.jetbrains.kotlinx.lincheck.util.mutableThreadMapOf
import org.jetbrains.kotlinx.lincheck.util.observes
import org.jetbrains.kotlinx.lincheck.util.threadIds
import org.jetbrains.lincheck.util.Enumerator
import org.jetbrains.lincheck.util.MemoryOrdering
import org.jetbrains.lincheck.util.Relation
import org.jetbrains.lincheck.util.collections.SortedArrayList
import org.jetbrains.lincheck.util.collections.SortedMutableList
import org.jetbrains.lincheck.util.collections.cartesianProduct
import org.jetbrains.lincheck.util.unreachable

// TODO: this is a bad name.
//  This class uses a writes before approximation and also checks consistency for either JAM21 or SC
class FullConsistencyChecker(val execution: Execution<AtomicThreadEvent>, val memoryAccessEventIndex: AtomicMemoryAccessEventIndex, val memoryModel: MemoryModel): ExtendedExecutionTracker {

    val hbChildrenTracker = HBChildrenTracker()
    val writesBeforeTracker = WritesBeforeTracker(memoryAccessEventIndex, hbChildrenTracker)
    private val volatileEventEnumerator = MutableEventEnumerator()

    val causalityTracker = SCCausalityTracker(execution, memoryAccessEventIndex)

    var stale: Boolean = true // Used for caching the consistencyResult
    var consistencyResult : Inconsistency? = null

    val allEventEnumerator = MutableEventEnumerator()


    val events = mutableListOf<AtomicThreadEvent>()
    val inconsistency : Inconsistency?
        get() = null

    override fun onAdd(event: AtomicThreadEvent) {
        allEventEnumerator.add(event)
        causalityTracker.add(event)
        val label = event.label as? MemoryAccessLabel ?: return
        // If not write or read response, then we skip
        if (!(label.isWrite || label.isResponse)) return
        hbChildrenTracker.addMemoryAccessEvent(event)

        //  Make sure that the cached result is not stale
        stale = true
        if (label.memoryOrdering == MemoryOrdering.VOLATILE) trackVolaitleEvent(event)

        writesBeforeTracker.addEvent(event)
    }


    private fun trackVolaitleEvent(event: AtomicThreadEvent) {
        volatileEventEnumerator.add(event)
    }


    override fun onReset(execution: MutableExtendedExecution) {
        stale = true
        // TODO: this is a major slow point, ideally we can skip some of the work that we do here
        volatileEventEnumerator.clear()
        hbChildrenTracker.clear()
        allEventEnumerator.clear()
        causalityTracker.clear()
        execution.forEach { event ->
            allEventEnumerator.add(event)
            causalityTracker.add(event)
            if(!eventIsMemoryAccessLabel(event)) return@forEach
            val label = event.label as MemoryAccessLabel
            hbChildrenTracker.addMemoryAccessEvent(event)
            if(label.memoryOrdering == MemoryOrdering.VOLATILE) trackVolaitleEvent(event)
        }

        writesBeforeTracker.onReset(execution)
    }

    fun completeCheck() : Inconsistency? {
        if(!stale) {
            return consistencyResult
        }
        consistencyResult = _completeCheck()
        stale = false
        return consistencyResult
    }

    fun _completeCheck() : Inconsistency? {
        val hasCycle = writesBeforeTracker.hasCycle()
        if (hasCycle) {
            return ReadModifyWriteAtomicityViolation()
        }

        if (memoryModel == MemoryModel.SequentialConsistency) return checkSequentialConsistency()
        if (memoryModel == MemoryModel.JAM21) return doCheckWithTotalOrders()

        return null
    }

    private fun checkSequentialConsistency(): Inconsistency? {
        if (volatileEventEnumerator.list.isEmpty()) return null

        writesBeforeTracker.coherenceOrders().forEach { coherenceOrder ->
            // Check sequential Consistency
            causalityTracker.setCoherenceOrder(coherenceOrder)
            val hasCycle = topologicalSorting(causalityTracker) == null
            if(!hasCycle) return null
        }
        return CoherenceViolation()
    }

    private fun doCheckWithTotalOrders() : Inconsistency? {
        // No need to compute the graph if there are no volatile events
        if (volatileEventEnumerator.list.isEmpty()) return null


        writesBeforeTracker.coherenceOrders().forEach { coherenceOrder ->
            // Check sequential Consistency
            val graph = SCBRelation(coherenceOrder).toGraph(volatileEventEnumerator.list, volatileEventEnumerator)
            val sorting = topologicalSorting(graph)
            if(sorting != null) return null
        }
        return CoherenceViolation()
    }
}

// Incrementally tracks all RF-edges
class SCCausalityTracker(val execution: Execution<AtomicThreadEvent>, val memoryAccessEventIndex: AtomicMemoryAccessEventIndex): Graph<AtomicThreadEvent> {

    // Empty set means that the node is there it just has no children
    // TODO: Maps are slow. Use arrays instead.
    val graph: MutableMap<AtomicThreadEvent, MutableSet<AtomicThreadEvent>> = mutableMapOf()
    val coherenceOrderGraph: MutableMap<AtomicThreadEvent, MutableSet<AtomicThreadEvent>> = mutableMapOf()

    override val nodes: Collection<AtomicThreadEvent>
        get() = graph.keys

    // TODO: possible optimization: we just keep the events with or if events that someone depends on
    fun add(event: AtomicThreadEvent) {
        if (event !in graph) graph[event] = mutableSetOf()
        // NOTE: this is copied over from causalityOrder definition.
        //   Ideally for SC we just track outgoing reads from edges
        //   We cannot use memoryAccessEvent index's read responses for write,
        //   as it most notably does not include thread forks and joins, which are important for the causal order
        event.dependencies.forEach {
            graph.getOrPut(it as AtomicThreadEvent) { mutableSetOf() }.add(event)
        }
    }

    fun setCoherenceOrder(coherenceOrder: CoherenceRelation) {
        // We add the co and rb edges from the eco relation, to the graphs before doing the causal checks
        // These are we only need to add these single edges as the rest will be handled transitively thanks to the causal cycles
        coherenceOrderGraph.clear()
        coherenceOrder.locationMap.entries.forEach { (location, events) ->
            events.windowed(2).forEach { (write1,write2) ->
                coherenceOrderGraph.getOrPut(write1) { mutableSetOf() }.add(write2)
                memoryAccessEventIndex.getWriteReadResponses(write1, location).forEach { read1 ->
                    coherenceOrderGraph.getOrPut(read1) { mutableSetOf() }.add(write2)
                }
            }
        }
    }

    fun clear() {
        graph.clear()
        coherenceOrderGraph.clear()
    }

    override fun adjacent(node: AtomicThreadEvent): List<AtomicThreadEvent> {
        val adjacent = (graph[node] ?: emptyList()).toList()
        val adjacent2 = (coherenceOrderGraph[node] ?: emptyList()).toList()
        val succ = execution[node.threadId, node.threadPosition + 1]
        if(succ != null) return listOf(succ) + adjacent + adjacent2
        return adjacent + adjacent2
    }

}

// Represents the SCB relation from the RC11 memory model
// Currently we use the following formula = [Vol] (co \/ rb \/ po \/ hbloc \/ po;hb;po ) [Vol]
class SCBRelation(val coherenceOrder: Relation<AtomicThreadEvent>) : Relation<AtomicThreadEvent> {

    override fun invoke(
        x: AtomicThreadEvent,
        y: AtomicThreadEvent
    ): Boolean {
        // Coerce the elements the corresponding writes
        val writeX = getWrite(x)
        val writeY = getWrite(y)
        // Check CO and RB both at once
        // For the CO and RB part we actually need to make sure that the location is the same
        val location = getLocationForSameLocationAccesses(x, y)
        if(location != null && !(x != writeX && writeY != y) && coherenceOrder(writeX, writeY)) return true // eco
        if (x.threadId == y.threadId && x.threadPosition < y.threadPosition) return true // po
        if (happensBeforeSameLocationOrder(x, y)) return true // hbloc

        // po; hb ; po
        val yParent = y.parent ?: return false
        return yParent.happensBeforeClock.observes(x.threadId, x.threadPosition+1)
    }
}


// Incrementally keeps track of the writes before relation for all memory locations, adding events one-by-one
// NOTE: Not sure how good of an idea this is
class WritesBeforeTracker(val memoryAccessEventIndex: AtomicMemoryAccessEventIndex, val hbChildrenTracker: HBChildrenTracker) {

    val writesBeforeGraphs: MutableMap<MemoryLocation, WritesBeforeGraph> = mutableMapOf()

    private fun _setWritesBefore(
        write1: AtomicThreadEvent,
        write2: AtomicThreadEvent,
        location: MemoryLocation,
    ) {
        check(write1.label.isWriteAccessTo(location))
        check(write2.label.isWriteAccessTo(location))
        val graph = writesBeforeGraphs.getOrPut(location) { WritesBeforeGraph() }
        graph.setChild(write1, write2)
    }

    fun hasCycle(): Boolean {
        for (location in writesBeforeGraphs.keys) {
            val graph = writesBeforeGraphs[location]!!
            if(graph.hasCycle()) {
                return true
            }
        }
        return false
    }

    fun onReset(execution: Execution<AtomicThreadEvent>) {
        writesBeforeGraphs.clear()
        execution.sorted().forEach { if(eventIsMemoryAccessLabel(it)) addEvent(it) }
    }

    fun coherenceOrders(): Sequence<CoherenceRelation> {
        val sortings = writesBeforeGraphs.values.filter{
            !it.isSingleton()
        }.map {
            topologicalSortings(it)
        }.toList()

        if(sortings.isEmpty()) {
            return sequenceOf(CoherenceRelation(emptyList()))
        }

        return sortings.cartesianProduct().map{
            CoherenceRelation(it)
        }
    }

    fun addEvent(event: AtomicThreadEvent) {
        check(eventIsMemoryAccessLabel(event))
        val label = event.label as MemoryAccessLabel
        val location = label.location
        val writeEvent = getWrite(event) // Get the corresponding write event
        check(eventIsMemoryLocationAccess(writeEvent))

        // Add it to the graph
        val graph = writesBeforeGraphs.getOrPut(location) { WritesBeforeGraph() }
        graph.add(writeEvent)


        for(write in hbChildrenTracker.getWritesBeforeCandidateWrites(event)) {
            _setWritesBefore(write, writeEvent, location)
        }
    }
}

// Handles the writes before relation for a single memory location
// Most crucial is the setChild(e1, e2) method, which updates
class WritesBeforeGraph: Graph<AtomicThreadEvent> {

    val nonExclusiveChildren: MutableMap<AtomicThreadEvent, MutableSet<AtomicThreadEvent>> = mutableMapOf()
    val exclusiveChildren: MutableMap<AtomicThreadEvent, AtomicThreadEvent> = mutableMapOf()
    var rmwCycle: Boolean = false // If a cycle due to rmw events is detected, then this flag is set to true

    var root: AtomicThreadEvent? = null
    private val _nodes = mutableSetOf<AtomicThreadEvent>()
    override val nodes: Set<AtomicThreadEvent>
        get() = _nodes

    fun isSingleton() : Boolean {
        val isSingleton = nodes.size == 1
        if(isSingleton) check(root in _nodes) // Make sure that the root is the only event
        return isSingleton
    }

    // Because of static memory locations, we need to initialize the root events statically
    private fun initializeRoot(event: AtomicThreadEvent) {
        if(root != null) {
            // make sure that the root is already added
            check(root in nodes)
            return
        }

        val label = event.label
        // If the event is a regular write event, then we read the allocation event
        if (label is WriteAccessLabel) {
            root = event.allocation!!
        } else {
            // Otherwise the event has to be an allocation or initialization event
            check(label is ObjectAllocationLabel || label is InitializationLabel)
            root = event
        }
        // Final check, just in case
        check(root!!.label is ObjectAllocationLabel || root!!.label is InitializationLabel)

        // Finally add the event internally
        _add(root!!)
    }

    private fun _add(event: AtomicThreadEvent) {
        // Internal add function, which assumes that root handling is already done.
        // Function should be idempotent

        // Skip if already added
        if(event in _nodes) {
            // Double-check that the map has already been initialized
            check(event in nonExclusiveChildren)
            return
        }
        // Add it to structures
        _nodes.add(event)
        // TODO: Why not just use get or default and skip this silly initialization?
        //   or just remove _nodes, as it seems that when we add to _nodes we also add to the map?
        nonExclusiveChildren[event] = mutableSetOf()
    }

    fun setChild(write1: AtomicThreadEvent, write2: AtomicThreadEvent) {
        check(isWriteEvent(write1))
        check(isWriteEvent(write2))
        check(write1 in nodes) { "Write event $write1 - $root is not in the graph" }
        check(write2 in nodes) { "Write event $write2 - $root is not in the graph" }

        if(write1 == write2) return

        // We need to follow the rmw chain, if write1 has exclusive children.
        // In that case we add the children to the end of the chain
        var parent = write1
        while(exclusiveChildren[parent] != null) {
            check(nonExclusiveChildren[parent]!!.isEmpty()) // Double-check that if we have an exclusive child, then we have no other children
            parent = exclusiveChildren[parent]!!
            if(parent == write2) return // If we encounter write2 along the way then we need to skip, as it has already been added
        }

        nonExclusiveChildren[parent]!!.add(write2) // Add the child to the actual parent
    }

    fun clear() {
        root = null
        rmwCycle = false
        nonExclusiveChildren.clear()
        exclusiveChildren.clear()
        _nodes.clear()
    }

    fun hasCycle(): Boolean {
        if (rmwCycle) {
            return true
        }
        topologicalSorting(this) ?: return true
        return false
    }

    override fun adjacent(node: AtomicThreadEvent): List<AtomicThreadEvent> {
        val exlusiveChild = exclusiveChildren[node]
        if(exlusiveChild != null) {
            check(nonExclusiveChildren[node]!!.size == 0) // Make sure that there are no non-excluive children
            return listOf(exlusiveChild)
        }
        return nonExclusiveChildren[node]!!.toList()
    }

    fun add(write: AtomicThreadEvent) {
        check(isWriteEvent(write))
        // If this is the first write event to be added event, then we also need to set the initial allocation/init event
        initializeRoot(write)
        // If the write is the root, then we are done
        if (write == root) return
        _add(write)
        // Make the new event a child of the root, if it different
        setChild(root!!, write)

        val label = write.label
        // In the case of an exclusive write, we need to handle it
        if(label is WriteAccessLabel && label.isExclusive) {
            val readsFrom = write.exclusiveReadPart.readsFrom
            setExclusiveChild(readsFrom, write)
        }
    }

    private fun setExclusiveChild(write1: AtomicThreadEvent, write2: AtomicThreadEvent) {
        // This should get called only immediately after the write2 event is added!
        check(isWriteEvent(write1))
        check(isWriteEvent(write2))
        check(write1 in nodes) { "Write event $write1 - $root is not in the graph" }
        check(write2 in nodes) { "Write event $write2 - $root is not in the graph" }
        check(write1 != write2) { "Exclusive write reads from itself!"}

        val existingExclusiveChild = exclusiveChildren[write1]
        // If we have already added this, then we skip
        if(existingExclusiveChild == write2) {
            check(nonExclusiveChildren[write1]!!.size == 0)
            return
        };

        // If we have another event already, then we have an rmw cycle and give up on life
        if (existingExclusiveChild != null) {
            // We just give up with proper tracking and declare that a cycle has been found
            rmwCycle = true
            return
        }

        exclusiveChildren[write1] = write2
        // Extend the set of children of write2 to include the children of write 1
        nonExclusiveChildren[write2]!! += nonExclusiveChildren[write1]!!.filter { it != write2 }
        // Clear all children of write1, as they must appear after write2
        nonExclusiveChildren[write1]!!.clear()
    }


    override fun toString(): String {
        return "Graph:\n${nodes.map {
            " ${it} -> ${adjacent(it).joinToString(",")}"
        }.joinToString("\n")}"
    }

}

// An instance of co ordering. It is constructed by having a list of lists,
// with one list for memory location with more than one write event.
class CoherenceRelation : Relation<AtomicThreadEvent> {

    val posMap: Map<AtomicThreadEvent, Int>
    val locationMap: MutableMap<MemoryLocation, List<AtomicThreadEvent>> = mutableMapOf()

    constructor(coherenceLists: List<List<AtomicThreadEvent>>) {
        posMap = mutableMapOf()
        for (coherenceList in coherenceLists) {
            if(coherenceList.isEmpty()) continue
            val loc = coherenceList.getLocationForSameLocationWriteAccesses()
            check(loc != null)
            locationMap[loc] = coherenceList
            for((i,write) in coherenceList.withIndex()) {
                posMap[write] = i
            }
        }
    }

    override fun invoke(
        x: AtomicThreadEvent,
        y: AtomicThreadEvent
    ): Boolean {
        val location = getLocationForSameLocationAccesses(x, y) ?: return false
        val orderX = posMap[x] ?: return false
        val orderY = posMap[y] ?: return false
        return orderX < orderY
    }
}

// Stupid implementation of enumerator with incrementally added events
class MutableEventEnumerator: Enumerator<AtomicThreadEvent> {

    private val map = mutableMapOf<AtomicThreadEvent, Int>()
    private val _list = mutableListOf<AtomicThreadEvent>()
    val list: List<AtomicThreadEvent>
        get() = _list

    fun add(event: AtomicThreadEvent) {
        map[event] = _list.size
        _list.add(event)
    }

    fun clear() {
        map.clear()
        _list.clear()
    }

    override fun get(x: AtomicThreadEvent): Int {
        return map[x]!!
    }

    override fun get(i: Int): AtomicThreadEvent {
        return _list[i]
    }
}


// The goal of this class is to provide a faster way to get potential writes before candidates for a given event,
// by giving the [getWritesBeforeCandidateWrites] method
// The problem that we want to solve with this function is that whenever an event is added to the execution,
// we need to find all of its hb-parents
// To do that we take the event's happensBefore clock and
// by keeping track in each thread of the thread positions of events for a given memory location,
// we can use binary search to quickly find out the most recent event that is observed by the clock.
class HBChildrenTracker {

    class WritesHBTrackerLocation {
        val positions: MutableThreadMap<SortedMutableList<Int>> = mutableThreadMapOf()
        val events: MutableThreadMap<MutableList<AtomicThreadEvent>> = mutableThreadMapOf()

        fun add(threadId: Int, threadPosition: Int, write: AtomicThreadEvent) {
            val positionList = positions.getOrPut(threadId, { SortedArrayList() })
            val eventList = events.getOrPut(threadId, { mutableListOf() })
            check(positionList.size == eventList.size)

            val lastPosition = positionList.lastOrNull() ?: -1
            check(lastPosition < threadPosition)
            positionList.add(threadPosition)
            eventList.add(write)
        }

        fun frontierObservedByClock(eventThreadId: Int, clock: VectorClock): Iterable<AtomicThreadEvent> {
            // For each thread, use binary search on the positionList to find the most recent event that is observed
            // by the given clock
            return clock.threadIds().mapNotNull { tid ->
                val positionList = positions[tid]
                if (positionList == null) return@mapNotNull null
                check(positionList.size != 0)

                var observedThreadPosition = clock[tid]
                if (observedThreadPosition == -1) return@mapNotNull null
                // Note: weird and hacky decrement so we do not see the write that was added right now,
                // since the clock includes the event itself
                if (eventThreadId == tid) observedThreadPosition--

                var eventIdx = positionList.binarySearch(observedThreadPosition)
                if (eventIdx < 0)  eventIdx = -eventIdx - 2

                if (eventIdx == -1) return@mapNotNull null
                check(positionList[eventIdx] <= observedThreadPosition)
                val event = events[tid]!![eventIdx]

                event
            }
        }

    }

    val locationMap: MutableMap<MemoryLocation, WritesHBTrackerLocation> = mutableMapOf()
    // TODO: This allocationAdded can be removed, if we just used the keys of the location map
    //  The invariant should be that if there is something in the map then the allocation is also there
    var allocationAdded : MutableSet<MemoryLocation>  = mutableSetOf()

    fun addMemoryAccessEvent(event: AtomicThreadEvent) {
        val label = event.label
        check(eventIsMemoryAccessLabel(event))

        val location = (label as MemoryAccessLabel).location
        val map = locationMap.getOrPut(location) { WritesHBTrackerLocation() }

        // Don't forget to add the allocation write as well!
        val allocation = event.allocation!!
        if(location !in allocationAdded) {
            map.add(allocation.threadId, allocation.threadPosition, allocation)
            allocationAdded.add(location)
        }

        val write = when (label) {
            is WriteAccessLabel -> event
            is ReadAccessLabel -> event.readsFrom
            else -> unreachable()
        }

        map.add(event.threadId, event.threadPosition, write)
    }

    fun getWritesBeforeCandidateWrites(event: AtomicThreadEvent): Iterable<AtomicThreadEvent> {
        val label = event.label
        check(eventIsMemoryAccessLabel(event))

        val location = (label as MemoryAccessLabel).location
        // NOTE: assumes the event in question is a memory access event that has already been added
        val map = locationMap[location]!!

        return map.frontierObservedByClock(event.threadId, event.happensBeforeClock)
    }

    fun clear() {
        locationMap.clear()
        allocationAdded.clear()
    }
}

// Some utility functions
private fun eventIsMemoryLocationAccess(event: AtomicThreadEvent) : Boolean {
    when(event.label) {
        is WriteAccessLabel, is ObjectAllocationLabel, is InitializationLabel -> return true
        is ReadAccessLabel -> return event.label.isResponse
        else -> return false
    }
}

private fun eventIsMemoryAccessLabel(event: AtomicThreadEvent) : Boolean {
    val label = event.label
    return (label is MemoryAccessLabel && (label.isWrite || label.isResponse))
}

private fun getWrite(event: AtomicThreadEvent): AtomicThreadEvent {
    check(eventIsMemoryLocationAccess(event)) { event }
    val label = event.label
    return when(label) {
        is WriteAccessLabel, is ObjectAllocationLabel, is InitializationLabel -> event
        is ReadAccessLabel -> event.readsFrom
        else -> unreachable()
    }
}

private fun isWriteEvent(event: AtomicThreadEvent ) : Boolean {
    return event.label is WriteAccessLabel || event.label is ObjectAllocationLabel || event.label is InitializationLabel
}

