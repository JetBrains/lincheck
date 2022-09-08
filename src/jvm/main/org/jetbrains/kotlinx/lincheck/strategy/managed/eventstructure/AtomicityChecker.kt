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

data class AtomicityViolation(val write1: Event, val write2: Event): Inconsistency()

class AtomicityChecker : IncrementalConsistencyChecker {

    private var isInitialized: Boolean = false

    private lateinit var eventStructure: EventStructure

    fun initialize(eventStructure: EventStructure) {
        check(!isInitialized)
        isInitialized = true
        this.eventStructure = eventStructure
    }

    override fun check(event: Event): Inconsistency? {
        if (event.label !is MemoryAccessLabel)
            return null
        if (event.label.accessKind != MemoryAccessKind.Write || !event.label.isExclusive)
            return null
        val readFrom = event.exclusiveReadPart.readsFrom
        eventStructure.currentExecution.find {
            it.label is MemoryAccessLabel
                && it.label.accessKind == MemoryAccessKind.Write
                && it.label.isExclusive
                && it.exclusiveReadPart.readsFrom == readFrom
        }?.let { return AtomicityViolation(it, event) }
        return null
    }

    override fun reset(execution: Execution) {
        return
    }

}