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

// TODO: what information should we display to help identify the cause of inconsistency:
//   a cycle in writes-before relation?
class ReleaseAcquireInconsistency : Inconsistency() {
    override fun toString(): String {
        return "Release/Acquire inconsistency detected"
    }
}

class ReleaseAcquireConsistencyChecker : ConsistencyChecker<AtomicThreadEvent, MutableExtendedExecution> {

    override fun check(execution: MutableExtendedExecution): Inconsistency? {
        val writesBeforeOrder = execution.writesBeforeOrder
        writesBeforeOrder.compute()
        return if (!writesBeforeOrder.isConsistent())
            ReleaseAcquireInconsistency()
        else null
    }

}
