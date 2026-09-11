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
import org.jetbrains.lincheck.util.collections.binarySearch
import org.jetbrains.lincheck.util.collections.cartesianProduct
import org.jetbrains.lincheck.util.unreachable

class WritesBeforeChecker(val execution: Execution<AtomicThreadEvent>, val memoryAccessEventIndex: AtomicMemoryAccessEventIndex, val memoryModel: MemoryModel): ExtendedExecutionTracker {

    private val volatileEventEnumerator = MutableEventEnumerator()
    val writesHBTracker = WritesHBTracker()
    val writesBeforeTracker = WritesBeforeTrackerImpl(memoryAccessEventIndex, writesHBTracker)
    val exclusiveWrites = mutableListOf<AtomicThreadEvent>()

    var stale: Boolean = true
    var consistencyResult : Inconsistency? = null

    val allEventEnumerator = MutableEventEnumerator()


    val events = mutableListOf<AtomicThreadEvent>()
    val inconsistency : Inconsistency?
        get() = null

    override fun onAdd(event: AtomicThreadEvent) {
        allEventEnumerator.add(event)
        val label = event.label as? MemoryAccessLabel ?: return
        // If not write or read response, then we skip
        if (!(label.isWrite || label.isResponse)) return
        writesHBTracker.addMemoryAccessEvent(event)

        //  Make sure that the cached result is not stale
        stale = true
        if(label.isExclusive) trackRMWEvent(event)
        if(label.memoryOrdering == MemoryOrdering.VOLATILE) trackVolaitleEvent(event)

        writesBeforeTracker.addEvent(event)
    }


    private fun trackVolaitleEvent(event: AtomicThreadEvent) { volatileEventEnumerator.add(event) }

    private fun trackRMWEvent(event: AtomicThreadEvent) {
        val label = event.label
        // Track the exclusive writes for consistency checking
        if(label is WriteAccessLabel)  {
            exclusiveWrites.add(event)
            return
        }
    }

    override fun onReset(execution: MutableExtendedExecution) {
        stale = true
        // Update the volatiles and exclusive writes
        exclusiveWrites.clear()
        volatileEventEnumerator.clear()
        writesHBTracker.clear()
        allEventEnumerator.clear()
        execution.forEach { event ->
            allEventEnumerator.add(event)
            if(!eventIsMemoryAccessLabel(event)) return@forEach
            val label = event.label as MemoryAccessLabel
            writesHBTracker.addMemoryAccessEvent(event)

            if(label.isExclusive) trackRMWEvent(event)
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
        if(hasCycle) {
            // TODO: actually add a nicer class
            return ReleaseAcquireViolation()
        }

        if(memoryModel == MemoryModel.SequentialConsistency) return checkSequentialConsistency()
        if(memoryModel == MemoryModel.JAM21) return doCheckWithTotalOrders()

        return null
    }

    private fun checkSequentialConsistency(): Inconsistency? {
        if (volatileEventEnumerator.list.isEmpty()) return null


        val scGraph = SCGraph(execution, allEventEnumerator.list, allEventEnumerator, memoryAccessEventIndex);
        scGraph.initializeCausalOrder(happensBeforeOrder)
        forEachCoherenceOrderSc().forEach { coherenceOrder ->
            // Check sequential Consistency
            scGraph.setCoherenceOrder(coherenceOrder)
            val hasCycle = scGraph.hasCycle()
            if(!hasCycle) return null
        }
        return CoherenceViolation()
    }

    private fun forEachCoherenceOrderSc(): Sequence<CoherenceRelation> {
        val sortings = writesBeforeTracker.writesBeforeGraphs.entries.filter{
            !it.value.isSingleton()  && !memoryAccessEventIndex.isWriteWriteRaceFree(it.key)
        }.map {
            topologicalSortings(it.value)
        }.toList()

        if(sortings.isEmpty()) {
            return sequenceOf(CoherenceRelation(emptyList()))
        }

        return sortings.cartesianProduct().map{
            CoherenceRelation(it)
        }
    }

    private fun doCheckWithTotalOrders() : Inconsistency? {
        // No need to compute the graph if there are no volatile events
        if (volatileEventEnumerator.list.isEmpty()) return null
        writesBeforeTracker.forEachCoherenceOrder().forEach { coherenceOrder ->
            // Check sequential Consistency
            val graph = SCBRelation(coherenceOrder).toGraph(volatileEventEnumerator.list, volatileEventEnumerator)
            val sorting = topologicalSorting(graph)
            if(sorting != null) return null
        }
        return JamSequentialConsistencyViolation()
    }
}

class FastGraph(
    val execution: Execution<AtomicThreadEvent>,
    val events: List<AtomicThreadEvent>,
    val eventEnumerator: Enumerator<AtomicThreadEvent>
) {

    val N = events.size
    val eventMapCapacity = execution.maxThreadId + 2
    val eventMapLastIndex = eventMapCapacity - 1
    val SIZE_IDX = eventMapLastIndex
    val graph: Array<IntArray> = Array(N) { IntArray(execution.maxThreadId + 2) }

    fun addChild(event: AtomicThreadEvent, childEvent: AtomicThreadEvent) {
        val eventId = eventEnumerator[event]
        val childId = eventEnumerator[childEvent]
        val freeIndex = graph[eventId][SIZE_IDX]++
        check(freeIndex < eventMapLastIndex) // To prevent overflow
        graph[eventId][freeIndex] += childId
    }


    inline fun forEachChildId(eventId: Int, block: (childId: Int) -> Unit) {
        val childIds = graph[eventId]
        val size = childIds[SIZE_IDX]
        for(i in 0 until size) {
            block(childIds[i])
        }
    }

    inline fun forEachChild(event: AtomicThreadEvent, block: (childId: AtomicThreadEvent) -> Unit) {
        val eventId = eventEnumerator[event]
        val childIds = graph[eventId]
        val size = childIds[SIZE_IDX]
        for(i in 0 until size) {
            val child = eventEnumerator[childIds[i]]
            block(child)
        }
    }

    fun clear() {
        for(i in 0 until N) { graph[i][SIZE_IDX] = 0 }
    }

}

class TIDGraph(
    val execution: Execution<AtomicThreadEvent>,
    val events: List<AtomicThreadEvent>,
    val eventEnumerator: Enumerator<AtomicThreadEvent>
) {

    val N = events.size
    val eventMapCapacity = execution.maxThreadId + 2
    val graph: Array<IntArray> = Array(N) { IntArray(eventMapCapacity) }

    val EMPTY = -1
    fun addChild(event: AtomicThreadEvent, childEvent: AtomicThreadEvent) {
        val eventId = eventEnumerator[event]
        val threadIdx = childEvent.threadId + 1
        val threadPosition = childEvent.threadPosition
        val value = graph[eventId][threadIdx]
        if(value == EMPTY) graph[eventId][threadIdx] = threadPosition
        graph[eventId][threadIdx] = minOf(value, threadPosition)
    }


    inline fun forEachChildId(eventId: Int, block: (childId: Int) -> Unit) {
        val childIds = graph[eventId]
        for(i in 0 until eventMapCapacity) {
            val pos = childIds[i]
            if(pos != EMPTY) {
                val tid = i - 1;
                val childevent = execution[tid, pos]!!
                val childId = eventEnumerator[childevent]
                block(childId)
            }
        }
    }

    inline fun forEachChild(event: AtomicThreadEvent, block: (childId: AtomicThreadEvent) -> Unit) {
        val eventId = eventEnumerator[event]
        val childIds = graph[eventId]
        for(i in 0 until eventMapCapacity) {
            val pos = childIds[i]
            if(pos != EMPTY) {
                val tid = i - 1;
                val childRvent = execution[tid, pos]!!
                block(childRvent)
            }
        }
    }

    fun clear() {
        for(i in 0 until N) {
            graph[i].fill(EMPTY)
        }
    }
}

class SCGraph(
    val execution: Execution<AtomicThreadEvent>,
    val events: List<AtomicThreadEvent>,
    val eventEnumerator: Enumerator<AtomicThreadEvent>,
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
) {

    val N = events.size
    val causalGraph = FastGraph(execution, events, eventEnumerator)
    val ecoGraph = TIDGraph(execution, events, eventEnumerator)

    val cycleState = ByteArray(N)
    val dsfStack = IntStack(N) // At least N elements
    val CYCLE_EMPTY: Byte = 0
    val CYCLE_SEEN: Byte = 1
    val CYCLE_DONE: Byte = 2
    val DONE_MASK: Int = (1 shl 30)

    fun initializeCausalOrder(causalOrder: Relation<AtomicThreadEvent>) {
        for(tid in -1 until execution.maxThreadId) {
            val threadEvents = execution[tid] ?: continue
            outer@ for(tid2 in -1 until execution.maxThreadId) {
                val threadEvents2 = execution[tid2] ?: continue
                var maxID = threadEvents2.size - 1
                for(tPos in (threadEvents.size - 1) downTo  0) {
                    val event = threadEvents[tPos]
                    val eIdx = eventEnumerator[event]

                    var tPos2 = threadEvents2.binarySearch(0, maxID + 1) { causalOrder(event, it) }
                    if (event.label is InitializationLabel && tid2 != tid) tPos2 = 0
                    val otherEvent = threadEvents2.getOrNull(tPos2) ?: continue@outer // We can skip with outer as
                    maxID = tPos2
                    causalGraph.addChild(event, otherEvent)
                }
            }
        }
    }


    private fun resetCycleState() {
        cycleState.fill(CYCLE_EMPTY)
        dsfStack.clear()
    }

    fun setCoherenceOrder(coherenceOrder: CoherenceRelation) {
        ecoGraph.clear()

        for(location in memoryAccessEventIndex.locations) {
            for(write1 in memoryAccessEventIndex.getWrites(location)) {
                // Add WW
                for(write2 in memoryAccessEventIndex.getWrites(location)) {
                    if(coherenceOrder(write1, write2)) ecoGraph.addChild(write1, write2)
                }
                // Add WR
                for(read2 in memoryAccessEventIndex.getReadResponses(location)) {
                    val write2 = read2.readsFrom
                    if(coherenceOrder(write1, write2)) ecoGraph.addChild(write1, write2)
                }
            }
            for(read1 in memoryAccessEventIndex.getReadResponses(location)) {
                val write1 = read1.readsFrom
                // Add RW
                for(write2 in memoryAccessEventIndex.getWrites(location)) {
                    if(coherenceOrder(write1, write2)) ecoGraph.addChild(write1, write2)
                }
                // We do not need to add RR edges!
            }
        }
    }

    fun hasCycle(): Boolean {
        resetCycleState()
        val rootIdx = 0
        dsfStack.push(rootIdx)

        while (!dsfStack.isEmpty) {
            val queueEntry = dsfStack.pop()
            val isDone = (queueEntry and DONE_MASK) != 0
            val eventIdx = queueEntry and (DONE_MASK).inv()

            if (isDone) {
                check(cycleState[eventIdx] == CYCLE_SEEN)
                cycleState[eventIdx] = CYCLE_DONE
                continue
            }
            // Cycle found
            if (cycleState[eventIdx] == CYCLE_SEEN) {
                return true
            }
            cycleState[eventIdx] = CYCLE_SEEN

            val doneEntry = eventIdx or DONE_MASK
            check(doneEntry xor DONE_MASK == eventIdx)
            check(doneEntry and DONE_MASK != 0)
            dsfStack.push(doneEntry)

            // Go over each of the neighbours
            val handleChild  = handleChild@{ childIdx: Int ->
                if (cycleState[childIdx] == CYCLE_DONE) return@handleChild
                check(childIdx and (DONE_MASK).inv() == childIdx)
                check(childIdx and DONE_MASK == 0)
                dsfStack.push(childIdx)
            }
            ecoGraph.forEachChildId(eventIdx, handleChild)
            causalGraph.forEachChildId(eventIdx, handleChild)
        }
        // We are done so no cycle is found
        return false
    }
}

class IntStack constructor(initialCapacity: Int = 16) {

    private var data: IntArray = IntArray(initialCapacity)
    private var top: Int = -1

    fun push(value: Int) {
        if (top == data.size - 1) resize()
        data[++top] = value
    }

    fun pop(): Int {
        check(!this.isEmpty) { "Stack is empty" }
        return data[top--]
    }

    fun peek(): Int {
        check(!this.isEmpty) { "Stack is empty" }
        return data[top]
    }

    val isEmpty: Boolean
        get() = top == -1

    fun size(): Int {
        return top + 1
    }

    private fun resize() {
        val newData = IntArray(data.size * 2)
        System.arraycopy(data, 0, newData, 0, data.size)
        data = newData
    }

    fun clear() {
        top = -1
    }
}

class SCBRelation(val coherenceOrder: Relation<AtomicThreadEvent>) : Relation<AtomicThreadEvent> {

    override fun invoke(
        x: AtomicThreadEvent,
        y: AtomicThreadEvent
    ): Boolean {
        val writeX = getWrite(x)
        val writeY = getWrite(y)
        // For the CO and Rb part we actually need to make sure that the location is the same
        val location = getLocationForSameLocationAccesses(x, y)
        // CO and RB both at once
        if(location != null && !(x != writeX && writeY != y) && coherenceOrder(writeX, writeY)) return true // eco
        if (x.threadId == y.threadId && x.threadPosition < y.threadPosition) return true // po
        if (happensBeforeSameLocationOrder(x, y)) return true // hbloc

        // po; hb ; po
        val yParent = y.parent ?: return false
        return yParent.happensBeforeClock.observes(x.threadId, x.threadPosition+1)
    }
}

class SCBSimpleRelation(val coherenceOrder: Relation<AtomicThreadEvent>) : Relation<AtomicThreadEvent> {

    override fun invoke(
        x: AtomicThreadEvent,
        y: AtomicThreadEvent
    ): Boolean {
        val writeX = getWrite(x)
        val writeY = getWrite(y)
        // For the CO and Rb part we actually need to make sure that the location is the same
        val location = getLocationForSameLocationAccesses(x, y)
        // CO and RB both at once
        if(location != null && !(x != writeX && writeY != y) && coherenceOrder(writeX, writeY)) return true // eco
        if (happensBeforeOrder(x, y)) return true // hb
        return false
    }
}

interface WritesBeforeTracker {
    fun hasCycle(): Boolean
    fun addEvent(event: AtomicThreadEvent)
    fun onReset(execution: Execution<AtomicThreadEvent>)
}

fun WritesBeforeTracker(
    memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    writesHBTracker: WritesHBTracker
): WritesBeforeTracker
    = WritesBeforeTrackerImpl(memoryAccessEventIndex, writesHBTracker)

class WritesBeforeTrackerImpl(val memoryAccessEventIndex: AtomicMemoryAccessEventIndex, val writesHBTracker: WritesHBTracker) : WritesBeforeTracker {

    val writesBeforeGraphs: MutableMap<MemoryLocation, WritesBeforeGraph> = mutableMapOf()

    fun _setWritesBefore(
        write1: AtomicThreadEvent,
        write2: AtomicThreadEvent,
        location: MemoryLocation,
    ) {
        check(write1.label.isWriteAccessTo(location))
        check(write2.label.isWriteAccessTo(location))
        val graph = writesBeforeGraphs.getOrPut(location) { WritesBeforeGraph() }
        graph.setChild(write1, write2)
    }

    override fun hasCycle(): Boolean {
        for (location in writesBeforeGraphs.keys) {
            val graph = writesBeforeGraphs[location]!!
            if(graph.hasCycle()) {
                return true
            }
        }
        return false
    }

    override fun onReset(execution: Execution<AtomicThreadEvent>) {
        writesBeforeGraphs.clear()
        execution.sorted().forEach { if(eventIsMemoryAccessLabel(it)) addEvent(it) }
    }

    fun forEachCoherenceOrder(): Sequence<CoherenceRelation> {
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

    override fun addEvent(event: AtomicThreadEvent) {
        check(eventIsMemoryAccessLabel(event))
        val label = event.label as MemoryAccessLabel
        val location = label.location
        val writeEvent = getWrite(event) // Get the corresponding write event
        check(eventIsMemoryLocationAccess(writeEvent))

        // Add it to the graph
        val graph = writesBeforeGraphs.getOrPut(location) { WritesBeforeGraph() }
        graph.add(writeEvent)


        for(write in writesHBTracker.getWritesBeforeCandidateWrites(event)) {
            _setWritesBefore(write, writeEvent, location)
        }

    }
}

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
        // Internal add funtions assumes that root handling is already done.
        // Function should be idempotent

        // Skip if already added
        if(event in _nodes) {
            // Double-check that the map has already been initialized
            check(event in nonExclusiveChildren)
            return
        }
        // Add it to structures
        _nodes.add(event)
        nonExclusiveChildren[event] = mutableSetOf()
    }

    fun setChild(write1: AtomicThreadEvent, write2: AtomicThreadEvent) {
        check(isWriteEvent(write1))
        check(isWriteEvent(write2))
        check(write1 in nodes) { "Write event $write1 - $root is not in the graph" }
        check(write2 in nodes) { "Write event $write2 - $root is not in the graph" }

        if(write1 == write2) return

        // We need
        var parent = write1
        while(exclusiveChildren[parent] != null) {
            check(nonExclusiveChildren[parent]!!.size == 0) // Double-check that if we have an excludive child, then we have no other children
            parent = exclusiveChildren[parent]!!
            if(parent == write2) return // IF we encounter write2 along the way then we need to skip
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
        // In the case of an exclusive write, we need to handle the
        if(label is WriteAccessLabel && label.isExclusive) {
            val readsFrom = write.exclusiveReadPart.readsFrom
            _setExclusiveChild(readsFrom, write)
        }
    }

    fun _setExclusiveChild(write1: AtomicThreadEvent, write2: AtomicThreadEvent) {
        // This should get called only immediately after the write2 event is added!
        check(isWriteEvent(write1))
        check(isWriteEvent(write2))
        check(write1 in nodes) { "Write event $write1 - $root is not in the graph" }
        check(write2 in nodes) { "Write event $write2 - $root is not in the graph" }
        check(write1 != write2) { "Exclusive write reads from itself!"}

        val chain = exclusiveChildren[write1]
        // If we have already added this, then we skip
        if(chain == write2) {
            check(nonExclusiveChildren[write1]!!.size == 0)
            return
        };

        // If we have another event already, then we for sure have an rmw cycle and give up on life
        if (chain != null) {
            // We just give up with proper tracking and declare that a cycle has been found
            rmwCycle = true
            return
        }

        exclusiveChildren[write1] = write2
        // Extend the set of children of write2 to include the children of write 2
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

class CoherenceRelation : Relation<AtomicThreadEvent> {

    val map: Map<AtomicThreadEvent, Int>

    constructor(coherenceLists: List<List<AtomicThreadEvent>>) {
        map = mutableMapOf()
        for (coherenceList in coherenceLists) {
            if(coherenceList.isEmpty()) continue
            check(coherenceList.getLocationForSameLocationWriteAccesses() != null)
            for((i,write) in coherenceList.withIndex()) {
                map[write] = i
            }
        }
    }

    override fun invoke(
        x: AtomicThreadEvent,
        y: AtomicThreadEvent
    ): Boolean {
        val location = getLocationForSameLocationAccesses(x, y) ?: return false
        val orderX = map[x] ?: return false
        val orderY = map[y] ?: return false
        return orderX < orderY
    }

    fun isDirectlyAfter(x: AtomicThreadEvent, y: AtomicThreadEvent) : Boolean {
        val location = getLocationForSameLocationAccesses(x, y) ?: return false
        val orderX = map[x]!!
        val orderY = map[y]!!
        return orderX + 1 == orderY
    }
}

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

private fun isWriteEvent(event: AtomicThreadEvent ) : Boolean {
    return event.label is WriteAccessLabel || event.label is ObjectAllocationLabel || event.label is InitializationLabel
}

class ReleaseAcquireViolation : Inconsistency() {
    override fun toString(): String {
        // TODO: Add information about the cycle if we care about that
        return "Release acquire violation!"
    }
}

class JamSequentialConsistencyViolation : Inconsistency() {
    override fun toString(): String {
        // TODO: Include the SCB cycle as well
        return "JAM SC violation"
    }
}


class WritesHBTracker {

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
            return clock.threadIds().mapNotNull { tid ->
                val positionList = positions[tid]
                if (positionList == null) return@mapNotNull null
                check(positionList.size != 0)

                var observedThreadPosition = clock[tid]
                if (observedThreadPosition == -1) return@mapNotNull null
                if (eventThreadId == tid) observedThreadPosition-- // Note: weird decrement so we do not see the write that was added right now

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
    var allocationAdded : MutableSet<MemoryLocation>  = mutableSetOf()

    fun addMemoryAccessEvent(event: AtomicThreadEvent) {
        val label = event.label
        check(eventIsMemoryAccessLabel(event))

        val location = (label as MemoryAccessLabel).location
        val map = locationMap.getOrPut(location) { WritesHBTrackerLocation() }


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