/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.execution

import org.jetbrains.kotlinx.lincheck.*

data class HBClock(val clock: LincheckAtomicIntArray) {
    val threads: Int get() = clock.array.size
    val empty: Boolean get() = clock.toArray().all { it == 0 }
    operator fun get(i: Int) = clock.array[i].value

    override fun toString() = clock.toArray().joinToString(prefix = "[", separator = ",", postfix = "]") {
        if (it == 0) "-" else "$it"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other != null && this::class != other::class) return false
        other as HBClock
        return clock.toArray().contentEquals(other.clock.toArray())
    }

    override fun hashCode() = clock.toArray().contentHashCode()
}

fun emptyClock(size: Int) = HBClock(emptyClockArray(size))
fun emptyClockArray(size: Int) = LincheckAtomicIntArray(size)

data class ResultWithClock(val result: Result, val clockOnStart: HBClock) : Finalizable {
    override fun finalize() {
        if(result is Finalizable) result.finalize()
    }
}

fun Result.withEmptyClock(threads: Int) = ResultWithClock(this, emptyClock(threads))
fun List<Result>.withEmptyClock(threads: Int): List<ResultWithClock> = map { it.withEmptyClock(threads) }
fun List<ResultWithClock>.withEmptyClock() = map { it.result.withEmptyClock(it.clockOnStart.threads) }