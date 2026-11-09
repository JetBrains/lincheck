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
import org.jetbrains.lincheck.util.*
import org.jetbrains.lincheck.util.collections.*

typealias CoherenceList = List<AtomicThreadEvent>

/**
 * Coherence order: a total ordering on same-location atomic writes.
 *
 * Stores only opaque-or-stronger writes and initialization events.
 * Plain writes belong to [FullCoherenceOrder].
 *
 * The coherence order computation procedure searches for a coherence order
 * compatible with atomic updates and the memory model's causal order.
 * Under release-acquire, any linearization of the [initialCoherenceApproximation] keeping read-modify-write chains contiguous
 * is a valid coherence order, so the first generated candidate is taken.
 *
 * Once computed, the coherence order is extended incrementally with the newly added writes:
 * since a new write is maximal in happens-before, it is put at the end of the coherence order.
 * The write-part of a read-modify-write is thus put right after the write it reads-from,
 * provided that this write is coherence-latest.
 * A read response or RMW write that does not consistently extend the cached order invalidates it,
 * so the consistency checker must recompute it.
 *
 * @param execution the execution whose writes are ordered;
 *   under sequential consistency, each candidate coherence order is checked by building an [ExecutionOrder] over it,
 *   and the found execution order is saved in the execution.
 * @param memoryAccessEventIndex the index supplying the writes to each memory location.
 * @param rmwChainsStorage the read-modify-write chains every candidate coherence order must respect.
 * @param initialCoherenceApproximation the initial approximation of the coherence order:
 *   a partial order on same-location writes, of which every candidate coherence order is a linearization.
 *   Also answers queries for locations without a computed coherence order (e.g. first accessed after computing it).
 *   Its own cached state must be computed by the caller before [compute].
 * @param coherenceCausalOrder the memory model's specific causal order,
 *   combined with a candidate coherence order to derive the extended coherence
 *   and the execution order (sequential-consistency order);
 *   usually either the happens-before or happens-before-per-location order.
 * @param memoryModel the memory model whose consistency the found coherence order witnesses.
 */
class CoherenceOrder(
    val execution: MutableExtendedExecution,
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val rmwChainsStorage: ReadModifyWriteOrder,
    val initialCoherenceApproximation: Relation<AtomicThreadEvent>,
    val coherenceCausalOrder: Relation<AtomicThreadEvent>,
    val memoryModel: MemoryModel,
) : CachedRelation<AtomicThreadEvent>, ExecutionTracker<AtomicThreadEvent, Execution<AtomicThreadEvent>> {

    // The empty execution has a valid witness, which fresh writes can extend immediately.
    var computed = execution.isEmpty()
        private set

    var consistent: Boolean = true
        private set

    private class Entry(coherence: CoherenceList) {
        private val _coherence = coherence.toMutableList()
        val coherence: CoherenceList get() = _coherence

        private val positions = coherence.withIndex().associateTo(mutableMapOf()) { (i, write) -> write to i }

        fun position(write: AtomicThreadEvent): Int =
            positions[write]!!

        fun append(write: AtomicThreadEvent) {
            positions[write] = _coherence.size
            _coherence.add(write)
        }
    }

    private constructor(order: CoherenceOrder, coherence: Map<MemoryLocation, Entry>) : this(
        order.execution,
        order.memoryAccessEventIndex,
        order.rmwChainsStorage,
        order.initialCoherenceApproximation,
        order.coherenceCausalOrder,
        order.memoryModel,
    ) {
        map.putAll(coherence)
        computed = true
    }

    private val map = mutableMapOf<MemoryLocation, Entry>()

    val fullCoherenceOrder: FullCoherenceOrder? =
        if (memoryModel == MemoryModel.RelaxedJava) FullCoherenceOrder(this) else null

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean = relates(map, x, y)

    private fun relates(order: Map<MemoryLocation, Entry>, x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        val location = getLocationForSameLocationWriteAccesses(x, y) ?: return false
        if (!x.isAtLeastOpaqueWrite() || !y.isAtLeastOpaqueWrite()) return false

        val entry = order[location] ?: return initialCoherenceApproximation(x, y)
        return entry.position(x) < entry.position(y)
    }

    operator fun get(location: MemoryLocation): CoherenceList =
        map[location]?.coherence ?: emptyList()

    override fun onAdd(event: AtomicThreadEvent) {
        if (!computed || !consistent) return

        val label = event.label
        if (!isConsistentExtension(event)) {
            invalidate()
            return
        }
        if (label is WriteAccessLabel && event.isAtLeastOpaqueWrite()) {
            addWrite(event, label.location)
        }
        fullCoherenceOrder?.onAdd(event)
    }

    override fun onReset(execution: Execution<AtomicThreadEvent>) {
        invalidate()
    }

    override fun invalidate() {
        computed = false
        map.clear()
        consistent = true
        fullCoherenceOrder?.onReset(execution)
    }

    override fun compute() {
        if (computed) return
        rmwChainsStorage.compute()
        for (coherence in generate(memoryAccessEventIndex, rmwChainsStorage, initialCoherenceApproximation)) {
            if (memoryModel == MemoryModel.SequentialConsistency) {
                // under the sequential consistency model, check that generated coherence order
                // satisfies sequential consistency constraints:
                // if so, save it and exit the loop, if not, try the next one
                if (!checkSequentialConsistency(coherence)) continue
            } else if (memoryModel == MemoryModel.RelaxedJava) {
                // Check full coherence, including plain accesses, before the volatile PSC constraint.
                if (!checkVolatileAccessesOrder(coherence)) continue
            }
            this.map += coherence
            computed = true
            return
        }
        // if we reached this point, then none of the generated coherence orderings is consistent
        consistent = false
        computed = true
    }

    /**
     * Checks that the candidate [coherence] order induces an acyclic execution order;
     * if so, saves the induced execution order.
     */
    private fun checkSequentialConsistency(coherence: Map<MemoryLocation, Entry>): Boolean {
        val order = Relation<AtomicThreadEvent> { x, y -> relates(coherence, x, y) }
        val extendedCoherence = ExtendedCoherenceOrder(
            coherenceOrder = order union coherenceCausalOrder
        )

        val executionOrder = ExecutionOrder(execution,
            approximation = extendedCoherence union coherenceCausalOrder,
        )
            .apply { compute() }
        if (!executionOrder.consistent)
            return false
        this.execution.executionOrder.copyFrom(executionOrder)
        return true
    }

    /**
     * Checks the volatile PSC order for a candidate JMM coherence order.
     */
    private fun checkVolatileAccessesOrder(coherence: Map<MemoryLocation, Entry>): Boolean {
        val coherence = CoherenceOrder(this, coherence)
        val fullCoherence = coherence.fullCoherenceOrder?.apply { compute() }
        if (fullCoherence != null && !fullCoherence.consistent) return false

        if (!checkVolatilePscOrder(fullCoherence ?: coherence)) return false

        fullCoherenceOrder?.apply { copyFrom(fullCoherence!!) }
        return true
    }

    /**
     * Checks the volatile PSC order against an already-established [coherenceOrder] witness.
     */
    private fun checkVolatilePscOrder(coherenceOrder: Relation<AtomicThreadEvent>): Boolean {
        val volatileEvents = execution.filter { it.isVolatileEvent() }
        if (volatileEvents.isEmpty()) return true
        val enumerator = buildEnumerator(volatileEvents)
        val scRelation = SequentialConsistencyMixedAccessRelation(
            execution,
            extendedCoherence = ExtendedCoherenceOrder(coherenceOrder, memoryModel),
            memoryAccessEventIndex = memoryAccessEventIndex,
            memoryModel = memoryModel,
        )
        scRelation.compute()
        // The relation is not transitively closed, but this is not required:
        // a topological sorting of the relation also respects its transitive closure,
        // and it does not exist (`topologicalSorting` returns null) iff the relation has a cycle.
        return topologicalSorting(scRelation.toGraph(volatileEvents, enumerator)) != null
    }

    /**
     * Returns an atomic write position in the current witness, with initialization at position zero.
     */
    fun position(write: AtomicThreadEvent): Int {
        check(computed && consistent) { "No coherence witness for the current exploration" }
        if (write.label.isInitializingWriteAccess()) return 0
        val location = (write.label as WriteAccessLabel).location
        return map.getValue(location).position(write)
    }

    /**
     * Sorts candidates by their source writes for exploration.
     */
    fun <T> sort(events: List<T>, location: MemoryLocation, selector: (T) -> AtomicThreadEvent): List<T> {
        if (fullCoherenceOrder != null) {
            return fullCoherenceOrder.sort(events, location, selector)
        }
        return events.sortedBy { position(selector(it)) }
    }

    /**
     * Retrieves the selected latest write, including plain writes under relaxed Java.
     *
     * Requires a computed, consistent order.
     * For a newly accessed location, falls back to its allocation/initialization event.
     *
     * @param location the memory location for which the latest write event is to be retrieved
     * @return the latest write event to the specified location, or null if no such event exists
     */
    fun getLatestWrite(location: MemoryLocation): AtomicThreadEvent? {
        if (fullCoherenceOrder != null) {
            return fullCoherenceOrder.getLatestWrite(location)
        }
        return map[location]?.coherence?.last()
            ?: memoryAccessEventIndex.getWrites(location).firstOrNull()
    }

    /**
     * Checks whether the extension of the coherence order with the newly added [event] is consistent.
     *
     * For a read response, checks that it reads-from the coherence-latest write to its location.
     * For the write-part of a read-modify-write (RMW), checks that its read-part reads-from
     * the coherence-latest write before appending the write-part.
     * The read-part itself might not have been checked against this coherence order,
     * since the order might have been computed after the read-part was added.
     * For other events, returns true.
     *
     * Requires the coherence order to be computed and consistent,
     * and the [event] to be the last added one in the execution, but not yet appended to the coherence order.
     */
    fun isConsistentExtension(event: AtomicThreadEvent): Boolean {
        val label = event.label
        return when {
            label is ReadAccessLabel && label.isResponse ->
                event.readsFrom == getLatestWrite(label.location)
            label is WriteAccessLabel && label.isExclusive -> {
                event.exclusiveReadPart.readsFrom == getLatestWrite(label.location)
            }
            else -> true
        }
    }

    private fun addWrite(write: AtomicThreadEvent, location: MemoryLocation) {
        val entry = map.getOrPut(location) {
            // Plain writes may have accessed this location before its first atomic write.
            Entry(memoryAccessEventIndex.getWrites(location).filter { it != write && it.isAtLeastOpaqueWrite() })
        }
        entry.append(write)
    }

    companion object {

        private fun generate(
            memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
            rmwChainsStorage: ReadModifyWriteOrder,
            writesOrder: Relation<AtomicThreadEvent>,
        ): Sequence<Map<MemoryLocation, Entry>> {
            // Recreated events have different IDs, but the same logical execution must choose the same witness.
            val writesByLocation = memoryAccessEventIndex.locations.map { location ->
                location to memoryAccessEventIndex.getWrites(location)
                    .filter { it.isAtLeastOpaqueWrite() }.sortedWith(eventPositionComparator)
            }.sortedWith(compareBy(eventPositionComparator) { (_, writes) ->
                writes.firstOrNull { !it.label.isInitializingWriteAccess() } ?: writes.first()
            })
            val coherenceOrderings = writesByLocation.map { (location, writes) ->
                val orderings = if (writes.size == 1) sequenceOf(writes) else {
                    val enumerator = buildEnumerator(writes)
                    topologicalSortings(writesOrder.toGraph(writes, enumerator)).filter {
                        rmwChainsStorage.respectful(it)
                    }
                }
                orderings.map { location to it }
            }
            if (coherenceOrderings.isEmpty()) {
                return sequenceOf(emptyMap())
            }
            return coherenceOrderings.cartesianProduct().map { coherenceList ->
                coherenceList.associate { (location, coherence) -> location to Entry(coherence) }
            }
        }

    }
}

/**
 * Full modification order, including ordering between plain write accesses.
 *
 * Derived from atomic [coherenceOrder] using the following formula:
 *   `H + H? ; (A + U ; A?) ; H?`
 * where:
 * - `H := [W] ; hb_loc ; [W]` is write/write happens-before;
 * - `U := rf ; rmw` connects each RMW source to its write part;
 * - `A` is the atomic accesses coherence order.
 *
 * Plain writes share clusters when their latest atomic predecessor and the earliest atomic successor agree.
 * These bounds need not be adjacent: a plain write can be incomparable with several atomic writes.
 * A separate latest-write map selects a maximal write for extension without ordering incomparable writes.
 * Requires [coherenceOrder] to be computed before [compute]; queries use the incrementally maintained cache.
 */
class FullCoherenceOrder(
    private val coherenceOrder: CoherenceOrder,
) : CachedRelation<AtomicThreadEvent>, ExecutionTracker<AtomicThreadEvent, Execution<AtomicThreadEvent>> {

    internal var computed = coherenceOrder.execution.isEmpty()
        private set

    internal var consistent = true
        private set

    private data class AtomicWritesRange(
        // Initialization events can represent writes to several locations.
        val location: MemoryLocation,
        val prevAtomicWrite: AtomicThreadEvent?,
        val nextAtomicWrite: AtomicThreadEvent?,
    )

    private class PlainWritesCluster(
        val range: AtomicWritesRange,
    ) {
        private val _writes = mutableListOf<AtomicThreadEvent>()
        val writes: List<AtomicThreadEvent> get() = _writes

        fun append(write: AtomicThreadEvent) {
            _writes.add(write)
        }
    }

    private val memoryAccessEventIndex = coherenceOrder.memoryAccessEventIndex

    // a map from range to its unique cluster
    private val clusterMap = mutableMapOf<AtomicWritesRange, PlainWritesCluster>()

    // a map from plain write events to the cluster they belong to
    private val eventCluster = mutableMapOf<AtomicThreadEvent, PlainWritesCluster>()

    // a map from a location to the latest write event to this location
    private val latestWrites = mutableMapOf<MemoryLocation, AtomicThreadEvent>()

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        if (x == y || getLocationForSameLocationWriteAccesses(x, y) == null) return false

        if ((x.isPlainOrInitialWrite()) && y.isPlainWrite() && writeHappensBefore(x, y)) return true
        if (x.isAtLeastOpaqueWrite() && y.isAtLeastOpaqueWrite()) return coherenceOrder(x, y)

        val firstCluster = eventCluster[x]
        val lastCluster = eventCluster[y]
        val next = if (firstCluster != null) (firstCluster.range.nextAtomicWrite ?: return false) else x
        val prev = if ( lastCluster != null) ( lastCluster.range.prevAtomicWrite ?: return false) else y
        return next == prev || coherenceOrder(next, prev)
    }

    override fun compute() {
        if (computed) return

        for (location in memoryAccessEventIndex.locations) {
            val writes = memoryAccessEventIndex.getWrites(location)
            val atomics = coherenceOrder[location]
            for (write in writes) {
                if (!write.isPlainWrite()) continue
                val prev = atomics.lastOrNull { writeHappensBefore(it, write) }
                val next = atomics.firstOrNull { precedesAtomic(write, it) }
                addToCluster(write, AtomicWritesRange(location, prev, next))
            }
        }

        computed = true
        consistent = checkConsistency()
        if (consistent) {
            computeLatestWrites()
        }
    }

    private fun computeLatestWrites() {
        for (location in memoryAccessEventIndex.locations) {
            val atomic = coherenceOrder[location].last()
            // Only this cluster can contain successors of the last atomic write.
            val writes = clusterMap[AtomicWritesRange(location, atomic, null)]?.writes
            latestWrites[location] = writes?.asSequence()
                ?.filter { write -> writes.none { other -> writeHappensBefore(write, other) } }
                ?.maxWith(eventPositionComparator)
                ?: atomic
        }
    }

    internal fun copyFrom(other: FullCoherenceOrder) {
        invalidate()
        for ((range, cluster) in other.clusterMap) {
            for (write in cluster.writes) {
                addToCluster(write, range)
            }
        }
        latestWrites.putAll(other.latestWrites)
        computed = other.computed
        consistent = other.consistent
    }

    override fun invalidate() {
        computed = false
        consistent = true
        eventCluster.clear()
        clusterMap.clear()
        latestWrites.clear()
    }

    override fun onAdd(event: AtomicThreadEvent) {
        if (!coherenceOrder.computed || !coherenceOrder.consistent) {
            invalidate()
            return
        }
        if (!computed || !consistent) return

        val label = event.label as? WriteAccessLabel ?: return
        if (event.isPlainWrite()) {
            val previous = coherenceOrder[label.location].lastOrNull { writeHappensBefore(it, event) }
            addToCluster(event, AtomicWritesRange(label.location, previous, null))
        } else {
            // A maximal atomic extension can only tighten previously unbounded successors.
            for (previous in sequenceOf(null) + coherenceOrder[label.location].asSequence()) {
                val range = AtomicWritesRange(label.location, previous, null)
                val cluster = clusterMap.remove(range) ?: continue
                for (write in cluster.writes) {
                    val next = event.takeIf { precedesAtomic(write, it) }
                    addToCluster(write, range.copy(nextAtomicWrite = next))
                }
            }
        }
        latestWrites[label.location] = event
    }

    override fun onReset(execution: Execution<AtomicThreadEvent>) = invalidate()

    /**
     * Returns the selected maximal write, without ordering incomparable plain writes.
     */
    fun getLatestWrite(location: MemoryLocation): AtomicThreadEvent? =
        latestWrites[location] ?: memoryAccessEventIndex.getWrites(location).firstOrNull()

    internal fun <T> sort(events: List<T>, location: MemoryLocation, selector: (T) -> AtomicThreadEvent): List<T> {
        val latest = getLatestWrite(location)
        // A newly appended plain write can be latest even when its predecessor belongs to an earlier bucket.
        // This orders alternative candidates, not hb-related writes in the memory model.
        val writeOrder =
            // put `latest` at the end, so it is picked up first for the response event,
            // as `latest` is guaranteed to produce a consistent execution extension
            // which is important for algorithm correctness.
            compareBy<AtomicThreadEvent> { it == latest }
            // then order by bucket position, so the atomic writes are ordered by coherence order,
            // and plain events are put after the last atomic write they happen-before.
            .thenBy(::bucketPosition)
            .thenBy { it.isPlainWrite() } // this puts atomic writes before plain ones, when they have the same bucket number
            // finally, order the rest (plain writes only) by their position in the execution
            .then(eventPositionComparator)
        return events.sortedWith(compareBy(writeOrder, selector))
    }

    private fun bucketPosition(write: AtomicThreadEvent): Int {
        val atomic = if (write.isPlainWrite()) eventCluster.getValue(write).range.prevAtomicWrite else write
        return atomic?.let(coherenceOrder::position) ?: 0
    }

    private fun checkConsistency(): Boolean {
        /* Checks `hb ; eco` for a candidate atomic order, including its derived plain-write edges. */

        for (range in clusterMap.keys) {
            val prev = range.prevAtomicWrite ?: continue
            val next = range.nextAtomicWrite ?: continue
            if (prev == next || coherenceOrder(next, prev)) return false
        }

        val eco = ExtendedCoherenceOrder(this, MemoryModel.RelaxedJava)
        for (location in memoryAccessEventIndex.locations) {
            val events =
                memoryAccessEventIndex.getWrites(location) +
                memoryAccessEventIndex.getReadResponses(location)
            for (first in events) {
                for (last in events) {
                    if (eco(first, last) && happensBeforeOrder(last, first)) return false
                }
            }
        }

        return true
    }

    private fun addToCluster(
        write: AtomicThreadEvent,
        range: AtomicWritesRange,
    ) {
        val cluster = clusterMap.getOrPut(range) { PlainWritesCluster(range) }
        cluster.append(write)
        eventCluster[write] = cluster
    }

    private fun precedesAtomic(plain: AtomicThreadEvent, atomic: AtomicThreadEvent): Boolean =
        writeHappensBefore(plain, atomic) || (
            atomic.label.isExclusiveWriteAccess() &&
            atomic.exclusiveReadPart.readsFrom.let { readsFrom ->
                plain == readsFrom || writeHappensBefore(plain, readsFrom)
            }
        )
}

/**
 * Extended coherence relation:
 *   `rf + mo + rb + mo;rf + rb;rf`
 * where `rb = rf^-1;mo`.
 *
 * Queries compare the writes supplying the two accesses using the current state of [coherenceOrder],
 * with a separate case for the `rf` edge from a write to its reader.
 * Under relaxed Java, `rb;rf` is included only when at least one read is opaque or stronger:
 *     `rf + mo + rb + mo;rf + [R>=opaque] ; rb ; rf + rb ; rf ; [R>=opaque]`
 *
 * In particular, this relation must not be transitively closed across plain reads.
 *
 * @param coherenceOrder an assumed coherence order relation,
 *   that is a partial order on write-write accesses to the same memory location.
 *   [Relation] rather than a [CoherenceOrder], to also accept
 *   candidate orders and happens-before/writes-before approximations.
 */
class ExtendedCoherenceOrder(
    val coherenceOrder: Relation<AtomicThreadEvent>,
    val memoryModel: MemoryModel = MemoryModel.SequentialConsistency,
) : Relation<AtomicThreadEvent> {

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        if (x == y || getLocationForSameLocationAccesses(x, y) == null) return false
        if (!(isWriteOrReadResponse(x) && isWriteOrReadResponse(y))) return false

        // plain read pairs are not ordered in JMM
        if (memoryModel == MemoryModel.RelaxedJava &&
            (x.label as? ReadAccessLabel)?.memoryOrdering == MemoryOrdering.PLAIN &&
            (y.label as? ReadAccessLabel)?.memoryOrdering == MemoryOrdering.PLAIN
        ) return false

        val xWrite = if (x.label is ReadAccessLabel) x.readsFrom else x
        val yWrite = if (y.label is ReadAccessLabel) y.readsFrom else y

        // equal writes yield only write/read read-from edge, never a read/read or read/write edge
        if (xWrite == yWrite) {
            return (y.label is ReadAccessLabel) && (x == yWrite /* == y.readsFrom */)
        }

        return coherenceOrder(xWrite, yWrite)
    }

    private fun isWriteOrReadResponse(x: AtomicThreadEvent): Boolean {
        return (x.label.isWriteAccess() || x.label is ReadAccessLabel && x.label.isResponse)
    }

}

private fun AtomicThreadEvent.isPlainWrite(): Boolean =
        (this.label as? WriteAccessLabel)?.memoryOrdering == MemoryOrdering.PLAIN

private fun AtomicThreadEvent.isPlainOrInitialWrite(): Boolean =
    this.isPlainWrite() || this.label.isInitializingWriteAccess()

private fun writeHappensBefore(first: AtomicThreadEvent, last: AtomicThreadEvent): Boolean =
    first != last && (first.label.isInitializingWriteAccess() || happensBeforeOrder(first, last))

private fun AtomicThreadEvent.isAtLeastOpaqueWrite(): Boolean {
    val label = this.label
    return when {
        label is WriteAccessLabel           -> label.memoryOrdering.isAtLeast(MemoryOrdering.OPAQUE)
        label.isInitializingWriteAccess()   -> true
        else                                -> false
    }
}
