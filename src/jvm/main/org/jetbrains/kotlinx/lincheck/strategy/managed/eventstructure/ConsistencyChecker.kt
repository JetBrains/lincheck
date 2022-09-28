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

abstract class Inconsistency

class InconsistentExecutionException(reason: Inconsistency): Exception(reason.toString())

fun interface ConsistencyChecker {
    fun check(execution: Execution): Inconsistency?
}

val idleConsistencyChecker : ConsistencyChecker =
    ConsistencyChecker { null }

interface IncrementalConsistencyChecker {

    /**
     * Checks whether adding the given [event] to the current execution retains execution's consistency.
     *
     * @return `null` if execution remains consistent,
     *   otherwise returns non-null [Inconsistency] object
     *   representing the reason of inconsistency.
     */
    fun check(event: Event): Inconsistency?

    /**
     * Resets the internal state of consistency checker to [execution].
     */
    fun reset(execution: Execution)
}

val idleIncrementalConsistencyChecker = object : IncrementalConsistencyChecker {

    override fun check(event: Event): Inconsistency? = null

    override fun reset(execution: Execution) {}

}