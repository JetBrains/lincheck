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
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.utils.*

// TODO: what information should we display to help identify the cause of inconsistency:
//   a cycle in writes-before relation?
class ReleaseAcquireInconsistency() : Inconsistency() {
    override fun toString(): String {
        // TODO: what information should we display to help identify the cause of inconsistency?
        return "Release/Acquire inconsistency detected"
    }
}

class ReleaseAcquireConsistencyWitness(
    val writesBefore: WritesBeforeRelation
)

class ReleaseAcquireConsistencyChecker : ConsistencyChecker<AtomicThreadEvent, ReleaseAcquireConsistencyWitness> {

    override fun check(execution: Execution<AtomicThreadEvent>): ConsistencyVerdict<ReleaseAcquireConsistencyWitness> {
        val writesBeforeRelation = WritesBeforeRelation(execution)
        return if (writesBeforeRelation.inconsistent || !writesBeforeRelation.isIrreflexive())
            ReleaseAcquireInconsistency()
        else
            ConsistencyWitness(ReleaseAcquireConsistencyWitness(writesBeforeRelation))
    }

}

// We use a map from pairs (location, event), because some events
// (e.g. ObjectAllocation events) can encompass several memory locations simultaneously.
private typealias ReadModifyWriteChainsMutableMap =
    MutableMap<Pair<MemoryLocation, AtomicThreadEvent>, MutableList<AtomicThreadEvent>>

class WritesBeforeRelation(
    val execution: Execution<AtomicThreadEvent>
) : Relation<AtomicThreadEvent> {

    private val readsMap: MutableMap<MemoryLocation, ArrayList<AtomicThreadEvent>> = mutableMapOf()

    private val writesMap: MutableMap<MemoryLocation, ArrayList<AtomicThreadEvent>> = mutableMapOf()

    private val relations: MutableMap<MemoryLocation, RelationMatrix<AtomicThreadEvent>> = mutableMapOf()

    private val rmwChains: ReadModifyWriteChainsMutableMap = mutableMapOf()

    // TODO: use both for rmw-chains and wb irreflexivity
    var inconsistent = false
        private set

    init {
        initializeWritesBeforeOrder()
        initializeReadModifyWriteChains()
        saturate()
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
            if (initEvent!!.label.isWriteAccessTo(memId))
                writes.add(initEvent)
            writes.addAll(allocEvents.filter { it.label.isWriteAccessTo(memId) })
            relations[memId] = RelationMatrix(writes, buildIndexer(writes)) { x, y ->
                causalityOrder.lessThan(x, y)
            }
        }
    }

    private fun initializeReadModifyWriteChains() {
        val chainsMap : ReadModifyWriteChainsMutableMap = mutableMapOf()
        for (event in execution.enumerationOrderSorted()) {
            val label = event.label
            if (label !is WriteAccessLabel || !label.isExclusive)
                continue
            val readFrom = event.exclusiveReadPart.readsFrom
            val chain = chainsMap.computeIfAbsent(label.location to readFrom) {
                mutableListOf(readFrom)
            }
            // TODO: this should be detected earlier ---
            //  we need to recalculate rmw chains on execution reset
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

    fun saturate() {
        if (inconsistent || !isIrreflexive())
            return
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
            }
        }
    }

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        // TODO: make this code pattern look nicer (it appears several times in codebase)
        var xloc = (x.label as? WriteAccessLabel)?.location
        var yloc = (y.label as? WriteAccessLabel)?.location
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
            // TODO: fix this IF (if chain is not a sublist of coherence exit immediately)
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