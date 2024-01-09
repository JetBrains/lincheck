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

import org.jetbrains.kotlinx.lincheck.utils.*

data class AtomicityViolation(val write1: Event, val write2: Event): Inconsistency()

class AtomicityChecker : IncrementalConsistencyChecker<AtomicThreadEvent> {

    private var execution: Execution<AtomicThreadEvent> = executionOf()

    override fun check(event: AtomicThreadEvent): Inconsistency? {
        val writeLabel = event.label.refine<WriteAccessLabel> { isExclusive }
            ?: return null
        val location = writeLabel.location
        val readFrom = event.exclusiveReadPart.readsFrom
        val other = execution.find { other ->
            other != event && other.label.satisfies<WriteAccessLabel> {
                isExclusive && this.location == location && other.exclusiveReadPart.readsFrom == readFrom
            }
        }
        return if (other != null) AtomicityViolation(other, event) else null
    }

    override fun check(): Inconsistency? {
        return null
    }

    override fun reset(execution: Execution<AtomicThreadEvent>) {
        this.execution = execution
    }

}