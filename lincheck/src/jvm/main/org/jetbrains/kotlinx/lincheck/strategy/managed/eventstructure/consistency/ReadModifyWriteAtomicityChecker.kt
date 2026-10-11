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

// TODO: restore atomicity violation information
class ReadModifyWriteAtomicityViolation(/*val write1: Event, val write2: Event*/) : Inconsistency() {
    override fun toString(): String {
        return "Atomicity violation detected"
    }
}

class ReadModifyWriteAtomicityChecker(
    execution: MutableExtendedExecution
) : AbstractFullyIncrementalConsistencyChecker<AtomicThreadEvent, MutableExtendedExecution>(execution) {

    override fun doIncrementalCheck(event: AtomicThreadEvent): ConsistencyVerdict {
        check(execution.readModifyWriteOrderComputable.computed)
        val readModifyWriteOrder = execution.readModifyWriteOrderComputable.value
        val entry = readModifyWriteOrder.add(event)
            ?: return ConsistencyVerdict.Consistent
        if (!entry.isConsistent()) {
            val inconsistency = ReadModifyWriteAtomicityViolation()
            return ConsistencyVerdict.Inconsistent(inconsistency)
        }
        return ConsistencyVerdict.Consistent
    }

    override fun doReset(): ConsistencyVerdict {
        execution.readModifyWriteOrderComputable.apply {
            reset()
            initialize()
            compute()
        }
        val readModifyWriteOrder = execution.readModifyWriteOrderComputable.value
        if (!readModifyWriteOrder.isConsistent()) {
            val inconsistency = ReadModifyWriteAtomicityViolation()
            return ConsistencyVerdict.Inconsistent(inconsistency)
        }
        return ConsistencyVerdict.Consistent
    }

}
