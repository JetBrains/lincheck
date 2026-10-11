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
import org.jetbrains.lincheck.util.CachedRelation
import org.jetbrains.lincheck.util.Relation
import org.jetbrains.lincheck.util.RelationMatrix


/** Closes memory-access constraints under transitivity and coherence. */
class MemoryModelConsistencyOrder(
    val execution: Execution<AtomicThreadEvent>,
    val memoryAccessEventIndex: AtomicMemoryAccessEventIndex,
    val memoryAccessOrder: Relation<AtomicThreadEvent>,
) : CachedRelation<AtomicThreadEvent>, ExecutionTracker<AtomicThreadEvent, Execution<AtomicThreadEvent>> {

    private var computed = false

    override fun onAdd(event: AtomicThreadEvent) = invalidate()

    override fun onReset(execution: Execution<AtomicThreadEvent>) = invalidate()

    private var consistent = true

    private var relation: RelationMatrix<AtomicThreadEvent>? = null

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean =
        relation?.invoke(x, y) ?: false

    fun isConsistent(): Boolean {
        return consistent
    }

    private fun initialize() {
        // TODO: optimize -- build the relation only for write and read-response events
        relation = RelationMatrix(execution, execution.buildEnumerator())
    }

    override fun compute() {
        if (computed) return
        initialize()
        val relation = this.relation!!
        relation.add(memoryAccessOrder)
        relation.fixpoint {
            // TODO: maybe we can remove this check without affecting performance?
            if (!isIrreflexive()) {
                consistent = false
                return@fixpoint
            }
            coherenceClosure()
            transitiveClosure()
        }
        computed = true
    }

    override fun invalidate() {
        computed = false
        consistent = true
        relation = null
    }

    private fun RelationMatrix<AtomicThreadEvent>.coherenceClosure() {
        for (location in memoryAccessEventIndex.locations) {
            coherenceClosure(location)
        }
    }

    private fun RelationMatrix<AtomicThreadEvent>.coherenceClosure(location: MemoryLocation) {
        val relation = this
        for (read in memoryAccessEventIndex.getReadResponses(location)) {
            for (write in memoryAccessEventIndex.getWrites(location)) {
                if (relation(write, read) && write != read.readsFrom) {
                    relation[write, read.readsFrom] = true
                }
                if (relation(read.readsFrom, write)) {
                    relation[read, write] = true
                }
            }
        }
    }

}
