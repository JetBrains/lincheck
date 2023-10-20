/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.utils.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*

// TODO: what information can we store about the reason of violation?
class ReleaseAcquireConsistencyViolation: Inconsistency()

// TODO: what information can we store about the reason of violation?
data class SequentialConsistencyViolation(
    val phase: SequentialConsistencyCheckPhase
) : Inconsistency()

enum class SequentialConsistencyCheckPhase {
    PRELIMINARY, APPROXIMATION, COHERENCE, REPLAYING
}

class SequentialConsistencyChecker(
    val checkReleaseAcquireConsistency: Boolean = true,
    val approximateSequentialConsistency: Boolean = true,
    val computeCoherenceOrdering: Boolean = true,
) : ConsistencyChecker<AtomicThreadEvent> {

    var executionOrder: List<AtomicThreadEvent> = listOf()
        private set

    override fun check(execution: Execution<AtomicThreadEvent>): Inconsistency? {
        executionOrder = listOf()
        // calculate writes-before relation if required
        val wbRelation = if (checkReleaseAcquireConsistency) {
            WritesBeforeRelation(execution).apply {
                saturate()?.let { return it }
            }
        } else null
        // TODO: combine SC approximation phase with coherence phase
        if (computeCoherenceOrdering) {
            check(wbRelation != null)
            return checkByCoherenceOrdering(execution, wbRelation)
        }
        // calculate additional ordering constraints
        val orderingRelation = executionRelation(execution, relation =
            // take happens-before as a base relation
            causalityOrder.lessThan
            // take union with writes-before relation
            union (wbRelation ?: Relation.empty())
        )
        // calculate approximation of sequential consistency order if required
        val scApproximationRelation = if (approximateSequentialConsistency) {
            SequentialConsistencyRelation(execution, orderingRelation).apply {
                saturate()?.let { return it }
            }
        } else orderingRelation
        // get dependency covering to guide the search
        val covering = scApproximationRelation.buildExternalCovering()
        // aggregate atomic events before replaying
        val (aggregated, remapping) = execution.aggregate(ThreadAggregationAlgebra.aggregator())
        // check consistency by trying to replay execution using sequentially consistent abstract machine
        return checkByReplaying(aggregated, covering.aggregate(remapping))
    }

    private fun checkByReplaying(
        execution: Execution<HyperThreadEvent>,
        covering: Covering<HyperThreadEvent>
    ): Inconsistency? {
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
                // TODO: maybe we should return more information than just success
                //  (e.g. path leading to terminal state)?
                if (state.isTerminal) {
                    executionOrder = state.history.flatMap { it.events }
                    return null
                }
                state.transitions().forEach {
                    val unvisited = visited.add(it)
                    if (unvisited) {
                        stack.addLast(it)
                    }
                }
            }
            return SequentialConsistencyViolation(
                phase = SequentialConsistencyCheckPhase.REPLAYING
            )
        }
    }

    private fun checkByCoherenceOrdering(
        execution: Execution<AtomicThreadEvent>,
        wbRelation: WritesBeforeRelation,
    ): Inconsistency? {
        wbRelation.generateCoherenceTotalOrderings().forEach { coherence ->
            val extendedCoherence = ExtendedCoherenceRelation.fromCoherenceOrderings(execution, coherence)
            val scOrder = extendedCoherence.computeSequentialConsistencyOrder()
            if (scOrder != null) {
                executionOrder = topologicalSorting(scOrder.asGraph())
                val replayer = SequentialConsistencyReplayer(1 + execution.maxThreadID)
                check(replayer.replay(executionOrder) != null)
                return null
            }
        }
        return SequentialConsistencyViolation(
            phase = SequentialConsistencyCheckPhase.COHERENCE
        )
    }

}

class IncrementalSequentialConsistencyChecker(
    checkReleaseAcquireConsistency: Boolean = true,
    approximateSequentialConsistency: Boolean = true
) : IncrementalConsistencyChecker<AtomicThreadEvent> {

    private var execution = executionOf<AtomicThreadEvent>()

    private val _executionOrder = mutableListOf<AtomicThreadEvent>()

    val executionOrder: List<AtomicThreadEvent>
        get() = _executionOrder

    private var executionOrderEnabled = true

    private val sequentialConsistencyChecker = SequentialConsistencyChecker(
        checkReleaseAcquireConsistency,
        approximateSequentialConsistency,
    )

    override fun check(): Inconsistency? {
        // TODO: expensive check???
        // check(execution.enumerationOrderSortedList() == executionOrder.sorted())

        // do basic preliminary checks
        checkLocks(execution)?.let { return it }
        // first try to replay according to execution order
        if (checkByExecutionOrderReplaying()) {
            return null
        }
        val inconsistency = sequentialConsistencyChecker.check(execution)
        if (inconsistency == null) {
            check(sequentialConsistencyChecker.executionOrder.isNotEmpty())
            // TODO: invent a nicer way to handle blocked dangling requests
            val (events, blockedRequests) = sequentialConsistencyChecker.executionOrder.partition {
                !execution.isBlockedDanglingRequest(it)
            }
            _executionOrder.apply {
                clear()
                addAll(events)
                addAll(blockedRequests)
            }
            executionOrderEnabled = true
        }
        return inconsistency
    }

    override fun check(event: AtomicThreadEvent): Inconsistency? {
        if (!executionOrderEnabled)
            return null
        if (event.extendsExecutionOrder()) {
            _executionOrder.add(event)
        } else {
            _executionOrder.clear()
            executionOrderEnabled = false
        }
        return null
    }

    override fun reset(execution: Execution<AtomicThreadEvent>) {
        this.execution = execution
        _executionOrder.clear()
        executionOrderEnabled = true
        for (event in execution.enumerationOrderSortedList()) {
            check(event)
        }
    }

    private fun checkByExecutionOrderReplaying(): Boolean {
        if (!executionOrderEnabled)
            return false
        val replayer = SequentialConsistencyReplayer(1 + execution.maxThreadID)
        return (replayer.replay(executionOrder) != null)
    }

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

    // TODO: move to a separate consistency checker!
    private fun checkLocks(execution: Execution<AtomicThreadEvent>): Inconsistency? {
        // maps unlock (or notify) event to its single matching lock (or wait) event;
        // if lock synchronizes-from initialization event,
        // then instead maps lock object itself to its first lock event
        // TODO: generalize and refactor!
        val mapping = mutableMapOf<Any, Event>()
        for (event in execution) {
            (event as AbstractAtomicThreadEvent)
            if (event.label !is MutexLabel || !event.label.isResponse)
                continue
            if (!(event.label is LockLabel || event.label is WaitLabel))
                continue
            if (event.label is WaitLabel && (event.notifiedBy.label as NotifyLabel).isBroadcast)
                continue
            val key: Any = when (event.syncFrom.label) {
                is UnlockLabel, is NotifyLabel -> event.syncFrom
                else -> (event.label as MutexLabel).mutex
            }
            if (mapping.put(key, event) != null) {
                return SequentialConsistencyViolation(
                    phase = SequentialConsistencyCheckPhase.PRELIMINARY
                )
            }
        }
        return null
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

private class SequentialConsistencyRelation(
    execution: Execution<AtomicThreadEvent>,
    initialApproximation: Relation<AtomicThreadEvent>
): ExecutionRelation<AtomicThreadEvent>(execution) {

    val relation = RelationMatrix(execution, indexer, initialApproximation)

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean =
        relation(x, y)

    fun saturate(): SequentialConsistencyViolation? {
        do {
            val changed = coherenceClosure() && relation.transitiveClosure()
            if (!relation.isIrreflexive())
                return SequentialConsistencyViolation(
                    phase = SequentialConsistencyCheckPhase.APPROXIMATION
                )
        } while (changed)
        return null
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

private class WritesBeforeRelation(
    execution: Execution<AtomicThreadEvent>
): ExecutionRelation<AtomicThreadEvent>(execution) {

    private val readsMap: MutableMap<MemoryLocation, ArrayList<AtomicThreadEvent>> = mutableMapOf()

    private val writesMap: MutableMap<MemoryLocation, ArrayList<AtomicThreadEvent>> = mutableMapOf()

    private val relations: MutableMap<MemoryLocation, RelationMatrix<AtomicThreadEvent>> = mutableMapOf()

    private val rmwChains:  ReadModifyWriteChainsMutableMap = mutableMapOf()

    private var inconsistent = false

    init {
        initializeWritesBeforeOrder()
        initializeReadModifyWriteChains()
    }

    private fun initializeWritesBeforeOrder() {
        var initEvent: AtomicThreadEvent? = null
        val allocEvents = mutableListOf<AtomicThreadEvent>()
        // TODO: refactor once per-kind indexing of events will be implemented
        for (event in execution) {
            val label = event.label
            if (label is InitializationLabel)
                initEvent = event
            if (label is ObjectAllocationLabel)
                allocEvents.add(event)
            if (label !is MemoryAccessLabel)
                continue
            if (label.isRead && label.isResponse) {
                readsMap.computeIfAbsent(label.location) { arrayListOf() }.apply {
                    add(event)
                }
            }
            if (label.isWrite) {
                writesMap.computeIfAbsent(label.location) { arrayListOf() }.apply {
                    add(event)
                }
            }
        }
        for ((memId, writes) in writesMap) {
            if (initEvent!!.label.isWriteAccess(memId))
                writes.add(initEvent)
            writes.addAll(allocEvents.filter { it.label.isWriteAccess(memId) })
            relations[memId] = RelationMatrix(writes, buildIndexer(writes)) { x, y ->
                causalityOrder.lessThan(x, y)
            }
        }
    }

    private fun initializeReadModifyWriteChains() {
        val chainsMap : ReadModifyWriteChainsMutableMap = mutableMapOf()
        for (event in execution.enumerationOrderSortedList()) {
            val label = event.label
            if (label !is WriteAccessLabel || !label.isExclusive)
                continue
            val readFrom = event.exclusiveReadPart.readsFrom
            val chain = chainsMap.computeIfAbsent(label.location to readFrom) {
                mutableListOf(readFrom)
            }
            // TODO: this should be detected earlier
            // check(readFrom == chain.last())
            if (readFrom != chain.last()) {
                inconsistent = true
                return
            }
            chain.add(event)
            chainsMap.put((label.location to event), chain).ensureNull()
        }
        for (chain in chainsMap.values) {
            check(chain.size >= 2)
            val location = (chain.last().label as WriteAccessLabel).location
            val relation = relations[location]!!
            for (i in 0 until chain.size - 1) {
                relation[chain[i], chain[i + 1]] = true
            }
            relation.transitiveClosure()
        }
        rmwChains.putAll(chainsMap)
    }

    private fun<T> RelationMatrix<T>.updateIrrefl(x: T, y: T): Boolean {
        return if ((x != y) && !this[x, y]) {
            this[x, y] = true
            true
        } else false
    }

    fun saturate(): ReleaseAcquireConsistencyViolation? {
        if (inconsistent || !isIrreflexive()) {
            return ReleaseAcquireConsistencyViolation()
        }
        for ((memId, relation) in relations) {
            val reads = readsMap[memId] ?: continue
            val writes = writesMap[memId] ?: continue
            var changed = false
            readLoop@ for (read in reads) {
                val readFrom = read.readsFrom
                val readFromChain = rmwChains[readFrom]
                writeLoop@ for (write in writes) {
                    val writeChain = rmwChains[write]
                    if (causalityOrder.lessThan(write, read)) {
                        relation.updateIrrefl(write, readFrom).also {
                            changed = changed || it
                        }
                        if ((writeChain != null || readFromChain != null) &&
                            (writeChain !== readFromChain)) {
                            relation.updateIrrefl(writeChain?.last() ?: write, readFromChain?.first() ?: readFrom).also {
                                changed = changed || it
                            }
                        }
                    }
                }
            }
            if (changed) {
                relation.transitiveClosure()
                if (!relation.isIrreflexive())
                    return ReleaseAcquireConsistencyViolation()
            }
        }
        return null
    }

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        // TODO: make this code pattern look nicer (it appears several times in codebase)
        var xloc = (x.label as? WriteAccessLabel)?.location
        var yloc = (y.label as? WriteAccessLabel)?.location
        if (xloc == null && yloc != null && x.label.isWriteAccess(yloc))
            xloc = yloc
        if (xloc != null && yloc == null && y.label.isWriteAccess(xloc))
            yloc = xloc
        return if (xloc != null && xloc == yloc) {
            relations[xloc]?.get(x, y) ?: false
        } else false
    }

    fun isIrreflexive(): Boolean =
        relations.all { (_, relation) -> relation.isIrreflexive() }

    fun generateCoherenceTotalOrderings(): Sequence<List<List<AtomicThreadEvent>>> {
        val coherenceOrderings = relations.mapNotNull { (_, relation) ->
            // TODO: remove this check!
            if (relation.nodes.size <= 1)
                return@mapNotNull null
            topologicalSortings(relation.asGraph()).filter {
                // filter-out coherence orderings violating atomicity
                respectsAtomicity(it)
            }
        }
        return coherenceOrderings.cartesianProduct()
    }

    fun getReadModifyWriteChains(location: MemoryLocation): List<List<AtomicThreadEvent>> {
        return rmwChains.entries.asSequence()
            .mapNotNull { (key, chain) ->
                if (key.first == location) chain else null
            }
            .distinct()
            .toMutableList()
            .apply {
                sortBy { chain ->
                    chain.first()
                }
            }
    }

    private fun respectsAtomicity(coherence: List<AtomicThreadEvent>): Boolean {
        check(coherence.isNotEmpty())
        val location = coherence
            .first { it.label is WriteAccessLabel }
            .let { (it.label as WriteAccessLabel).location }
        // atomicity violation occurs when a write event is put in the middle of some rmw chain
        val chains = getReadModifyWriteChains(location)
        if (chains.isEmpty())
            return true
        var i = 0
        var pos = 0
        while (pos + chains[i].size <= coherence.size) {
            if (coherence[pos] == chains[i].first() &&
                coherence.subList(pos, pos + chains[i].size) == chains[i]) {
                pos += chains[i].size
                if (++i == chains.size)
                    return true
            } else {
                pos++
            }
        }
        return false
    }
}

// We use a map from pairs (location, event), because some events
// (e.g. ObjectAllocation events) can encompass several memory locations simultaneously.
private typealias ReadModifyWriteChainsMutableMap =
    MutableMap<Pair<MemoryLocation, AtomicThreadEvent>, MutableList<AtomicThreadEvent>>

private class ExtendedCoherenceRelation(
    execution: Execution<AtomicThreadEvent>,
): ExecutionRelation<AtomicThreadEvent>(execution) {

    private val relations: MutableMap<MemoryLocation, RelationMatrix<AtomicThreadEvent>> = mutableMapOf()

    private var inconsistent = false

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
                if (!write.label.isWriteAccess(rloc))
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
        if (xloc == null && yloc != null && x.label.isWriteAccess(yloc))
            xloc = yloc
        if (xloc != null && yloc == null && y.label.isWriteAccess(xloc))
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
                if (initEvent!!.label.isWriteAccess(location))
                    events.add(initEvent)
                events.addAll(allocEvents.filter { it.label.isWriteAccess(location) })
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

private fun buildIndexer(events: MutableList<AtomicThreadEvent>) = object : Indexer<AtomicThreadEvent> {

    // TODO: perhaps we can maintain event numbers directly in events themself
    //   and update them during replay?
    val index: Map<AtomicThreadEvent, Int> =
        mutableMapOf<AtomicThreadEvent, Int>().apply {
            events.forEachIndexed { i, event ->
                put(event, i).ensureNull()
            }
        }

    override fun get(i: Int): AtomicThreadEvent = events[i]

    override fun index(x: AtomicThreadEvent): Int = index[x]!!

}