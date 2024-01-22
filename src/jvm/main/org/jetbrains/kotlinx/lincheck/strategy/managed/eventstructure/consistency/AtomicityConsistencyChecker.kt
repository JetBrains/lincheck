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

class ReadModifyWriteChainsStorage {

    abstract class Entry {
        abstract val event: AtomicThreadEvent
        abstract val chain: ReadModifyWriteChain
        abstract val position: Int
    }

    private data class EntryImpl(
        override val event: AtomicThreadEvent,
        override val chain: ReadModifyWriteChain,
        override val position: Int,
    ) : Entry() {

        init {
            require(isValid())
        }

        private fun isValid(): Boolean = when {
            // we store only write accesses in the chain
            !event.label.isWriteAccess() -> false
            // non-exclusive write access can only be the first one in the chain
            !event.label.isExclusiveWriteAccess() -> (position == 0) && (event == chain[0])
            // otherwise, consider entry to be valid
            else -> true
        }
    }

    private val eventChainMap = mutableMapOf<Event, MutableReadModifyWriteChain>()

    private val rmwChainsMap = mutableMapOf<MemoryLocation, MutableList<MutableReadModifyWriteChain>>()

    fun getReadModifyWriteChain(event: Event): ReadModifyWriteChain {
        return eventChainMap[event] ?: emptyList()
    }

    fun getReadModifyWriteChains(location: MemoryLocation): List<ReadModifyWriteChain> {
        return rmwChainsMap[location] ?: emptyList()
    }

    val sameReadModifyWriteChain = Relation<AtomicThreadEvent> { x, y ->
        val chain = getReadModifyWriteChain(x)
        chain.isNotEmpty() && chain == getReadModifyWriteChain(y)
    }

    fun add(event: AtomicThreadEvent) {
        val writeLabel = event.label.refine<WriteAccessLabel> { isExclusive }
            ?: return
        val location = writeLabel.location
        val readFrom = event.exclusiveReadPart.readsFrom
        val chain = eventChainMap[readFrom] ?: arrayListOf()
        // TODO: handle inconsistency?
        // if (readFrom != chain.lastOrNull()) {
        //
        // }
        // if the read-from events is not yet mapped to any rmw chain,
        // then we about to start a new one
        if (chain.isEmpty()) {
            check(!readFrom.label.isExclusiveWriteAccess())
            chain.add(readFrom)
            rmwChainsMap.updateInplace(location, default = arrayListOf()) {
                add(chain)
            }
        }
        chain.add(event)
        eventChainMap[event] = chain
    }

    fun compute(execution: Execution<AtomicThreadEvent>) {

    }

    fun reset() {
        eventChainMap.clear()
        rmwChainsMap.clear()
    }

}