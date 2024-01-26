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

// TODO: override toString()
class AtomicityViolation(val write1: Event, val write2: Event) : Inconsistency()

// TODO: what should we return as a witness?
class AtomicityConsistencyChecker : IncrementalConsistencyChecker<AtomicThreadEvent, Unit> {

    private var execution: Execution<AtomicThreadEvent> = executionOf()

    override fun check(event: AtomicThreadEvent): ConsistencyVerdict<Unit> {
        val writeLabel = event.label.refine<WriteAccessLabel> { isExclusive }
            ?: return ConsistencyWitness(Unit)
        val location = writeLabel.location
        val readFrom = event.exclusiveReadPart.readsFrom
        val other = execution.find { other ->
            other != event && other.label.satisfies<WriteAccessLabel> {
                isExclusive && this.location == location && other.exclusiveReadPart.readsFrom == readFrom
            }
        }
        return if (other != null)
            AtomicityViolation(other, event)
        else
            ConsistencyWitness(Unit)
    }

    override fun check(): ConsistencyVerdict<Unit> {
        return ConsistencyWitness(Unit)
    }

    override fun reset(execution: Execution<AtomicThreadEvent>) {
        this.execution = execution
    }

}

typealias ReadModifyWriteChain = List<AtomicThreadEvent>
typealias MutableReadModifyWriteChain = MutableList<AtomicThreadEvent>

fun ReadModifyWriteChainsStorage.Entry.isConsistent() = when {
    // write-part of atomic-read-modify write operation should read-from
    // the preceding write event in the chain
    event.label.isExclusiveWriteAccess() ->
        event.exclusiveReadPart.readsFrom == chain[position - 1]
    // the other case is a non-exclusive write access,
    // which should be the first event in the chain
    // (this is enforced by the `isValid` check in the constructor)
    else -> true
}

fun ReadModifyWriteChainsStorage.isConsistent() =
    entries.all { it.isConsistent() }

class ReadModifyWriteChainsStorage {

    abstract class Entry {
        abstract val event: AtomicThreadEvent
        abstract val chain: ReadModifyWriteChain
        abstract val position: Int
    }

    private data class EntryImpl(
        override val event: AtomicThreadEvent,
        override val chain: MutableReadModifyWriteChain,
        override val position: Int,
    ) : Entry() {

        init {
            require(isValid())
        }

        private fun isValid(): Boolean =
            chain.isNotEmpty() && when {
                // we store only write accesses in the chain
                !event.label.isWriteAccess() -> false
                // non-exclusive write access can only be the first one in the chain
                !event.label.isExclusiveWriteAccess() -> (position == 0) && (event == chain[0])
                // otherwise, consider entry to be valid
                else -> true
            }
    }

    val entries: Collection<Entry>
        get() = eventMap.values

    private val eventMap = mutableMapOf<Event, EntryImpl>()

    private val rmwChainsMap = mutableMapOf<MemoryLocation, MutableList<MutableReadModifyWriteChain>>()

    operator fun get(event: Event): Entry? {
        return eventMap[event]
    }

    operator fun get(location: MemoryLocation): List<ReadModifyWriteChain>? {
        return rmwChainsMap[location]
    }

    // val sameReadModifyWriteChain = Relation<AtomicThreadEvent> { x, y ->
    //     val chain = getReadModifyWriteChain(x)
    //     chain.isNotEmpty() && chain == getReadModifyWriteChain(y)
    // }

    fun add(event: AtomicThreadEvent) {
        val writeLabel = event.label.refine<WriteAccessLabel> { isExclusive }
            ?: return
        val location = writeLabel.location
        val readFrom = event.exclusiveReadPart.readsFrom
        val chain = when {
            /* Because the initialization (or object allocation) event
             * may encode several initialization writes (e.g., one for each field of an object),
             * we cannot map this initialization event to a single rmw chain.
             * Instead, we need to map it to a different rmw chain for each location.
             * To do so, we can utilize the fact that in such a scenario,
             * the first chain for a given location may start with the chain
             * beginning at the initialization event.
             * If the first chain starts with some other event,
             * it means we are about to create a new chain and insert it at the beginning of the list.
             */
            readFrom.label.isInitializingWriteAccess() ->
                rmwChainsMap[location]?.get(0)?.takeIf { it[0] == readFrom } ?: arrayListOf()
            // otherwise we simply take the chain mapped to the read-from event
            else ->
                eventMap[readFrom]?.chain?.ensure { it.isNotEmpty() } ?: arrayListOf()
        }
        // if the read-from event is not yet mapped to any rmw chain,
        // then we about to start a new one
        if (chain.isEmpty()) {
            check(!readFrom.label.isExclusiveWriteAccess())
            chain.add(readFrom)
            if (!readFrom.label.isInitializingWriteAccess()) {
                eventMap[readFrom] = EntryImpl(readFrom, chain, position = 0)
            }
            rmwChainsMap.updateInplace(location, default = arrayListOf()) {
                // we order chains with respect to the enumeration order of their starting events
                var position = indexOfFirst { readFrom.id < it[0].id }
                if (position == -1)
                    position = size
                add(position, chain)
            }
        }
        chain.add(event)
        eventMap[event] = EntryImpl(event, chain, position = chain.size - 1)
    }

    fun compute(execution: Execution<AtomicThreadEvent>) {
        /* It is important to add events in some causality-compatible order
         * (such as event enumeration order).
         * This guarantees the following property: a mapping `w -> c`, where
         *   - `w` is an exclusive write event from the rmw pair of events (r, w),
         *   - `c` is a rmw chain to which `w` belongs to,
         * would be added to the map only after mapping `w' -> c` is added,
         * where `w'` is the write event from which `r` reads-from.
         * In particular, for atomic-consistent executions, it implies that
         * the rmw-chains would be added in their order from the begging of the chain to its end.
         */
        for (event in execution.enumerationOrderSorted()) {
            add(event)
        }
    }

    fun reset() {
        eventMap.clear()
        rmwChainsMap.clear()
    }

}