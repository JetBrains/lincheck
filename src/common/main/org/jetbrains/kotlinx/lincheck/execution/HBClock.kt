/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.execution

import org.jetbrains.kotlinx.lincheck.Result

data class HBClock(val clock: IntArray) {
    val threads: Int get() = clock.size
    val empty: Boolean get() = clock.all { it == 0 }
    operator fun get(i: Int) = clock[i]

    override fun toString() = clock.joinToString(prefix = "[", separator = ",", postfix = "]") {
        if (it == 0) "-" else "$it"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other === null) return false
        if (this::class != other::class) return false
        other as HBClock
        return clock.contentEquals(other.clock)
    }

    override fun hashCode() = clock.contentHashCode()
}

fun emptyClock(size: Int) = HBClock(emptyClockArray(size))
fun emptyClockArray(size: Int) = IntArray(size) { 0 }

data class ResultWithClock(val result: Result, val clockOnStart: HBClock)

fun Result.withEmptyClock(threads: Int) = ResultWithClock(this, emptyClock(threads))
fun List<Result>.withEmptyClock(threads: Int): List<ResultWithClock> = map { it.withEmptyClock(threads) }
fun List<ResultWithClock>.withEmptyClock() = map { it.result.withEmptyClock(it.clockOnStart.threads) }