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

import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.kotlinx.lincheck.utils.*
import kotlin.collections.ArrayDeque
import kotlin.collections.Iterable
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.all
import kotlin.collections.arrayListOf
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.filter
import kotlin.collections.first
import kotlin.collections.flatMap
import kotlin.collections.forEach
import kotlin.collections.get
import kotlin.collections.isNotEmpty
import kotlin.collections.iterator
import kotlin.collections.lastOrNull
import kotlin.collections.listOf
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.collections.plus
import kotlin.collections.set
import kotlin.collections.toMutableMap

typealias SequentialConsistencyVerdict = ConsistencyVerdict<SequentialConsistencyWitness>

class SequentialConsistencyWitness(
    val executionOrder: List<AtomicThreadEvent>
) {
    companion object {
        fun create(executionOrder: List<AtomicThreadEvent>): ConsistencyWitness<SequentialConsistencyWitness> {
            return ConsistencyWitness(SequentialConsistencyWitness(executionOrder))
        }
    }
}

abstract class SequentialConsistencyViolation : Inconsistency()

class SequentialConsistencyChecker(
    val checkReleaseAcquireConsistency: Boolean = true,
    val approximateSequentialConsistency: Boolean = true,
    val computeCoherenceOrdering: Boolean = true,
) : ConsistencyChecker<AtomicThreadEvent, SequentialConsistencyWitness> {

    private val releaseAcquireChecker : ReleaseAcquireConsistencyChecker? =
        if (checkReleaseAcquireConsistency) ReleaseAcquireConsistencyChecker() else null

    override fun check(execution: Execution<AtomicThreadEvent>): SequentialConsistencyVerdict {
        // we will gradually approximate the total sequential execution order of events
        // by a partial order, starting with the partial causality order
        var executionOrderApproximation : Relation<AtomicThreadEvent> = causalityOrder.lessThan
        // first try to check release/acquire consistency (it is cheaper) ---
        // release/acquire inconsistency will also imply violation of sequential consistency,
        if (releaseAcquireChecker != null) {
            when (val verdict = releaseAcquireChecker.check(execution)) {
                is ReleaseAcquireInconsistency -> return verdict
                is ConsistencyWitness -> {
                    // if execution is release/acquire consistent,
                    // the writes-before relation can be used
                    // to refine the execution ordering approximation
                    val writesBefore = verdict.witness.writesBefore
                    executionOrderApproximation = executionOrderApproximation union writesBefore
                    // TODO: combine SC approximation phase with coherence phase
                    if (computeCoherenceOrdering) {
                        return checkByCoherenceOrdering(execution, writesBefore)
                    }
                }
            }
        }
        // TODO: combine SC approximation phase with coherence phase (and remove this check)
        check(!computeCoherenceOrdering)
        if (approximateSequentialConsistency) {
            // TODO: embed the execution order approximation relation into the execution instance,
            //   so that this (and following stages) can be implemented as separate consistency check classes
            val scApprox = SaturatedHappensBeforeRelation(execution, executionOrderApproximation)
            if (scApprox.inconsistent) {
                return SequentialConsistencyApproximationInconsistency()
            }
            executionOrderApproximation = scApprox
        }
        // get dependency covering to guide the search
        val covering = execution.buildExternalCovering(executionOrderApproximation)
        // aggregate atomic events before replaying
        val (aggregated, remapping) = execution.aggregate(ThreadAggregationAlgebra.aggregator())
        // check consistency by trying to replay execution using sequentially consistent abstract machine
        return checkByReplaying(aggregated, covering.aggregate(remapping))
    }

    private fun checkByReplaying(
        execution: Execution<HyperThreadEvent>,
        covering: Covering<HyperThreadEvent>
    ): SequentialConsistencyVerdict {
        // TODO: this is just a DFS search.
        //  In fact, we can generalize this algorithm to
        //  two arbitrary labelled transition systems by taking their product LTS
        //  and trying to find a trace in this LTS leading to terminal state.
        val context = Context(execution, covering)
        val initState = State.initial(execution)
        val stack = ArrayDeque(listOf(initState))
        val visited = mutableSetOf(initState)
        with(context) {
            while (stack.isNotEmpty()) {
                val state = stack.removeLast()
                if (state.isTerminal) {
                    return SequentialConsistencyWitness.create(
                        executionOrder = state.history.flatMap { it.events }
                    )
                }
                state.transitions().forEach {
                    val unvisited = visited.add(it)
                    if (unvisited) {
                        stack.addLast(it)
                    }
                }
            }
            return SequentialConsistencyReplayViolation()
        }
    }

    private fun checkByCoherenceOrdering(
        execution: Execution<AtomicThreadEvent>,
        wbRelation: WritesBeforeRelation,
    ): ConsistencyVerdict<SequentialConsistencyWitness> {
        wbRelation.generateCoherenceTotalOrderings().forEach { coherence ->
            val extendedCoherence = ExtendedCoherenceRelation.fromCoherenceOrderings(execution, coherence)
            val scOrder = extendedCoherence.computeSequentialConsistencyOrder()
            if (scOrder != null) {
                val executionOrder = topologicalSorting(scOrder.asGraph())
                val replayer = SequentialConsistencyReplayer(1 + execution.maxThreadID)
                check(replayer.replay(executionOrder) != null)
                return SequentialConsistencyWitness.create(executionOrder)
            }
        }
        return SequentialConsistencyCoherenceViolation()
    }

}

class SequentialConsistencyCoherenceViolation : SequentialConsistencyViolation() {
    override fun toString(): String {
        // TODO: what information should we display to help identify the cause of inconsistency?
        return "Sequential consistency coherence violation detected"
    }
}

class IncrementalSequentialConsistencyChecker(
    checkReleaseAcquireConsistency: Boolean = true,
    approximateSequentialConsistency: Boolean = true
) : IncrementalConsistencyChecker<AtomicThreadEvent, SequentialConsistencyWitness> {

    private var execution = executionOf<AtomicThreadEvent>()

    private val _executionOrder = mutableListOf<AtomicThreadEvent>()

    val executionOrder: List<AtomicThreadEvent>
        get() = _executionOrder

    private var executionOrderEnabled = true

    private val lockConsistencyChecker = LockConsistencyChecker()

    private val sequentialConsistencyChecker = SequentialConsistencyChecker(
        checkReleaseAcquireConsistency,
        approximateSequentialConsistency,
    )

    override fun check(): SequentialConsistencyVerdict {
        // TODO: expensive check???
        // check(execution.enumerationOrderSortedList() == executionOrder.sorted())

        // check locks consistency
        when (val verdict = lockConsistencyChecker.check(execution)) {
            is LockConsistencyViolation -> return verdict
        }
        // first try to replay according to execution order
        if (checkByExecutionOrderReplaying()) {
            return SequentialConsistencyWitness.create(executionOrder)
        }
        val verdict = sequentialConsistencyChecker.check(execution)
        if (verdict is Inconsistency)
            return verdict
        check(verdict is ConsistencyWitness)
        // TODO: invent a nicer way to handle blocked dangling requests
        val (events, blockedRequests) = verdict.witness.executionOrder.partition {
            !execution.isBlockedDanglingRequest(it)
        }
        _executionOrder.apply {
            clear()
            addAll(events)
            addAll(blockedRequests)
        }
        executionOrderEnabled = true
        return SequentialConsistencyWitness.create(executionOrder)
    }

    override fun check(event: AtomicThreadEvent): SequentialConsistencyVerdict {
        if (!executionOrderEnabled)
            return ConsistencyUnknown
        if (!event.extendsExecutionOrder()) {
            _executionOrder.clear()
            executionOrderEnabled = false
            return ConsistencyUnknown
        }
        _executionOrder.add(event)
        return SequentialConsistencyWitness.create(executionOrder)
    }

    override fun reset(execution: Execution<AtomicThreadEvent>) {
        this.execution = execution
        _executionOrder.clear()
        executionOrderEnabled = true
        for (event in execution.enumerationOrderSorted()) {
            check(event)
        }
    }

    private fun checkByExecutionOrderReplaying(): Boolean {
        if (!executionOrderEnabled)
            return false
        val replayer = SequentialConsistencyReplayer(1 + execution.maxThreadID)
        return (replayer.replay(executionOrder) != null)
    }

    // TODO: can we get rid of this (by ensuring we always replay atomic list of events atomically)?
    private fun AtomicThreadEvent.extendsExecutionOrder(): Boolean {
        // TODO: this check should be generalized ---
        //   it should be derivable from the aggregation algebra
        if (label is ReadAccessLabel && label.isResponse) {
            val last = executionOrder.lastOrNull()
                ?: return false
            return isValidResponse(last)
        }
        if (label is WriteAccessLabel && (label as WriteAccessLabel).isExclusive) {
            val last = executionOrder.lastOrNull()
                ?: return false
            return isWritePartOfAtomicUpdate(last)
        }
        return true
    }
}

class SequentialConsistencyReplayViolation : SequentialConsistencyViolation() {
    override fun toString(): String {
        // TODO: what information should we display to help identify the cause of inconsistency?
        return "Sequential consistency replay violation detected"
    }
}

private data class SequentialConsistencyReplayer(
    val nThreads: Int,
    val memoryView: MutableMap<MemoryLocation, Event> = mutableMapOf(),
    val monitorTracker: MapMonitorTracker = MapMonitorTracker(nThreads),
    val monitorMapping: MutableMap<ObjectID, Any> = mutableMapOf()
) {

    fun replay(event: AtomicThreadEvent): SequentialConsistencyReplayer? {
        val label = event.label
        return when {

            label is ReadAccessLabel && label.isRequest ->
                this

            label is ReadAccessLabel && label.isResponse ->
                this.takeIf {
                    (event as AbstractAtomicThreadEvent)
                    // TODO: do we really need this `if` here?
                    if (event.readsFrom.label is WriteAccessLabel)
                         memoryView[label.location] == event.readsFrom
                    else memoryView[label.location] == null
                }

            label is WriteAccessLabel ->
                this.copy().apply { memoryView[label.location] = event }

            label is LockLabel && label.isRequest ->
                this

            label is LockLabel && label.isResponse && !label.isWaitLock -> {
                val monitor = getMonitor(label.mutex)
                if (this.monitorTracker.canAcquire(event.threadId, monitor)) {
                    this.copy().apply { monitorTracker.acquire(event.threadId, monitor).ensure() }
                } else null
            }

            label is UnlockLabel && !label.isWaitUnlock ->
                this.copy().apply { monitorTracker.release(event.threadId, getMonitor(label.mutex)) }

            label is WaitLabel && label.isRequest ->
                this.copy().apply { monitorTracker.wait(event.threadId, getMonitor(label.mutex)).ensure() }

            label is WaitLabel && label.isResponse -> {
                val monitor = getMonitor(label.mutex)
                if (this.monitorTracker.canAcquire(event.threadId, monitor)) {
                    this.copy().takeIf { !it.monitorTracker.wait(event.threadId, monitor) }
                } else null
            }

            label is NotifyLabel ->
                this.copy().apply { monitorTracker.notify(event.threadId, getMonitor(label.mutex), label.isBroadcast) }

            // auxiliary unlock/lock events inserted before/after wait events
            label is LockLabel && label.isWaitLock ->
                this
            label is UnlockLabel && label.isWaitUnlock ->
                this

            label is InitializationLabel -> this
            label is ObjectAllocationLabel -> this
            label is ThreadEventLabel -> this
            // TODO: do we need to care about parking?
            label is ParkingEventLabel -> this
            label is CoroutineLabel -> this
            label is ActorLabel -> this
            label is RandomLabel -> this

            else -> unreachable()

        }
    }

    fun replay(events: Iterable<AtomicThreadEvent>): SequentialConsistencyReplayer? {
        var replayer = this
        for (event in events) {
            replayer = replayer.replay(event) ?: return null
        }
        return replayer
    }

    fun replay(event: HyperThreadEvent): SequentialConsistencyReplayer? {
        return replay(event.events)
    }

    fun copy(): SequentialConsistencyReplayer =
        SequentialConsistencyReplayer(
            nThreads,
            memoryView.toMutableMap(),
            monitorTracker.copy(),
            monitorMapping.toMutableMap(),
        )

    private fun getMonitor(objID: ObjectID): OpaqueValue {
        check(objID != NULL_OBJECT_ID)
        return monitorMapping.computeIfAbsent(objID) { Any() }.opaque()
    }

}

private data class State(
    val executionClock: MutableVectorClock,
    val replayer: SequentialConsistencyReplayer,
) {

    // TODO: move to Context
    var history: List<HyperThreadEvent> = listOf()
        private set

    constructor(
        executionClock: MutableVectorClock,
        replayer: SequentialConsistencyReplayer,
        history: List<HyperThreadEvent>,
    ) : this(executionClock, replayer) {
        this.history = history
    }

    companion object {
        fun initial(execution: Execution<HyperThreadEvent>) = State(
            executionClock = MutableVectorClock(1 + execution.maxThreadID),
            replayer = SequentialConsistencyReplayer(1 + execution.maxThreadID),
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        return (other is State)
                && executionClock == other.executionClock
                && replayer == other.replayer
    }

    override fun hashCode(): Int {
        var result = executionClock.hashCode()
        result = 31 * result + replayer.hashCode()
        return result
    }

}

private class Context(val execution: Execution<HyperThreadEvent>, val covering: Covering<HyperThreadEvent>) {

    fun State.covered(event: HyperThreadEvent): Boolean =
        executionClock.observes(event)

    fun State.coverable(event: HyperThreadEvent): Boolean =
        covering.coverable(event, executionClock)

    val State.isTerminal: Boolean
        get() = executionClock.observes(execution)

    fun State.transition(threadId: Int): State? {
        val position = 1 + executionClock[threadId]
        val event = execution[threadId, position]
            ?.takeIf { coverable(it) }
            ?: return null
        val view = replayer.replay(event)
            ?: return null
        return State(
            replayer = view,
            history = this.history + event,
            executionClock = this.executionClock.copy().apply {
                increment(event.threadId)
            },
        )
    }

    fun State.transitions() : List<State> {
        val states = arrayListOf<State>()
        for (threadId in execution.threadIDs) {
            transition(threadId)?.let { states.add(it) }
        }
        return states
    }

}

class SequentialConsistencyApproximationInconsistency : SequentialConsistencyViolation() {
    override fun toString(): String {
        // TODO: what information should we display to help identify the cause of inconsistency?
        return "Approximate sequential inconsistency detected"
    }
}


class SaturatedHappensBeforeRelation(
    val execution: Execution<AtomicThreadEvent>,
    initialApproximation: Relation<AtomicThreadEvent>
) : Relation<AtomicThreadEvent> {

    private val indexer = execution.buildIndexer()

    val relation = RelationMatrix(execution, indexer, initialApproximation)

    var inconsistent = false
        private set

    init {
        saturate()
    }

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean =
        relation(x, y)

    private fun saturate() {
        do {
            val changed = coherenceClosure() && relation.transitiveClosure()
            if (!relation.isIrreflexive()) {
                inconsistent = true
            }
        } while (changed && !inconsistent)
    }

    private fun coherenceClosure(): Boolean {
        var changed = false
        readLoop@for (read in execution) {
            if (!(read.label is ReadAccessLabel && read.label.isResponse))
                continue
            val readFrom = read.readsFrom
            writeLoop@for (write in execution) {
                val rloc = (read.label as? ReadAccessLabel)?.location
                val wloc = (write.label as? WriteAccessLabel)?.location
                if (wloc == null || wloc != rloc)
                    continue
                if (write != readFrom && relation(write, read) && !relation(write, readFrom)) {
                    relation[write, readFrom] = true
                    changed = true
                }
                if (read != write && relation(readFrom, write) && !relation(read, write)) {
                    relation[read, write] = true
                    changed = true
                }
            }
        }
        return changed
    }

}

private class ExtendedCoherenceRelation(
    val execution: Execution<AtomicThreadEvent>,
): Relation<AtomicThreadEvent> {

    private val indexer = execution.buildIndexer()

    private val relations: MutableMap<MemoryLocation, RelationMatrix<AtomicThreadEvent>> = mutableMapOf()

    init {
        // TODO: check that reads-from is contained inside the initial approx.
    }

    // TODO: unify the rules below with `coherenceClosure` rules in the `SequentialConsistencyRelation`

    private fun addReadsFromEdges() {
        readLoop@for (read in execution) {
            if (!(read.label is ReadAccessLabel && read.label.isResponse))
                continue
            val readFrom = read.readsFrom
            val rloc = (read.label as? ReadAccessLabel)?.location
            val relation = relations[rloc]!!
            relation[readFrom, read] = true
        }
    }

    private fun addReadsBeforeEdges() {
        readLoop@for (read in execution) {
            if (!(read.label is ReadAccessLabel && read.label.isResponse))
                continue
            val readFrom = read.readsFrom
            writeLoop@for (write in execution) {
                val rloc = (read.label as? ReadAccessLabel)?.location
                val wloc = (write.label as? WriteAccessLabel)?.location
                if (wloc == null || wloc != rloc || write == readFrom)
                    continue
                val relation = relations[rloc]!!
                if (relation(readFrom, write)) {
                    relation[read, write] = true
                }
            }
        }
    }

    private fun addCoherenceReadFromEdges() {
        readLoop@for (read in execution) {
            if (!(read.label is ReadAccessLabel && read.label.isResponse))
                continue
            val readFrom = read.readsFrom
            writeLoop@for (write in execution) {
                val rloc = (read.label as ReadAccessLabel).location
                if (!write.label.isWriteAccessTo(rloc))
                    continue
                if (write == readFrom)
                    continue
                val relation = relations[rloc]!!
                if (relation(write, readFrom)) {
                    relation[write, read] = true
                }
            }
        }
    }

    private fun addReadsBeforeReadsFromEdges() {
        read1Loop@for (read1 in execution) {
            if (!(read1.label is ReadAccessLabel && read1.label.isResponse))
                continue
            read2Loop@for (read2 in execution) {
                val rloc1 = (read1.label as? ReadAccessLabel)?.location
                val rloc2 = (read2.label as? ReadAccessLabel)?.location
                if (rloc2 == null || rloc2 != rloc1 || !read2.label.isResponse)
                    continue
                val readFrom = read2.readsFrom
                val relation = relations[rloc1]!!
                if (relation[read1, readFrom]) {
                    relation[read1, read2] = true
                }
            }
        }
    }

    private fun addExtendendCoherenceEdges() {
        addReadsFromEdges()
        addReadsBeforeEdges()
        // addCoherenceReadFromEdges()
        // addReadsBeforeReadsFromEdges()
    }

    // TODO: move to `SequentialConsistencyRelation`
    fun computeSequentialConsistencyOrder(): RelationMatrix<AtomicThreadEvent>? {
        val scOrder = RelationMatrix(execution, indexer, causalityOrder.lessThan union this)
        // TODO: remove this ad-hoc
        scOrder.addRequestResponseEdges()
        scOrder.transitiveClosure()
        return scOrder.takeIf { it.isIrreflexive() }
    }

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        // TODO: make this code pattern look nicer (it appears several times in codebase)
        var xloc = (x.label as? MemoryAccessLabel)?.location
        var yloc = (y.label as? MemoryAccessLabel)?.location
        if (xloc == null && yloc != null && x.label.isWriteAccessTo(yloc))
            xloc = yloc
        if (xloc != null && yloc == null && y.label.isWriteAccessTo(xloc))
            yloc = xloc
        return if (xloc != null && xloc == yloc) {
            relations[xloc]?.get(x, y) ?: false
        } else false
    }

    fun isIrreflexive(): Boolean =
        relations.all { (_, relation) -> relation.isIrreflexive() }

    companion object {
        fun fromCoherenceOrderings(
            execution: Execution<AtomicThreadEvent>,
            coherence: List<List<AtomicThreadEvent>>,
        ): ExtendedCoherenceRelation {
            val extendedCoherence = ExtendedCoherenceRelation(execution)
            var initEvent: AtomicThreadEvent? = null
            val allocEvents = mutableListOf<AtomicThreadEvent>()
            val eventsMap = mutableMapOf<MemoryLocation, MutableList<AtomicThreadEvent>>()
            // TODO: refactor once per-kind indexing of events will be implemented
            for (event in execution) {
                val label = event.label
                if (label is InitializationLabel)
                    initEvent = event
                if (label is ObjectAllocationLabel)
                    allocEvents.add(event)
                if (label !is MemoryAccessLabel)
                    continue
                eventsMap.computeIfAbsent(label.location) { arrayListOf() }.apply {
                    add(event)
                }
            }
            for ((location, events) in eventsMap) {
                if (initEvent!!.label.isWriteAccessTo(location))
                    events.add(initEvent)
                events.addAll(allocEvents.filter { it.label.isWriteAccessTo(location) })
                extendedCoherence.relations[location] = RelationMatrix(events, buildIndexer(events)) { x, y ->
                    false
                }
            }
            for (ordering in coherence) {
                check(ordering.isNotEmpty())
                val write = ordering.first { it.label is WriteAccessLabel }
                val location = (write.label as WriteAccessLabel).location
                extendedCoherence.relations[location]!!.addTotalOrdering(ordering)
            }
            extendedCoherence.addExtendendCoherenceEdges()
            return extendedCoherence
        }
    }

    private fun RelationMatrix<AtomicThreadEvent>.addRequestResponseEdges() {
        for (response in execution) {
            if (!response.label.isResponse)
                continue
            // put notify event after wait-request
            if (response.label is WaitLabel) {
                this[response.request!!, response.notifiedBy] = true
                continue
            }
            // otherwise, put request events after dependencies
            for (dependency in response.dependencies) {
                check(dependency is AtomicThreadEvent)
                this[dependency, response.request!!] = true
            }
        }
    }
}