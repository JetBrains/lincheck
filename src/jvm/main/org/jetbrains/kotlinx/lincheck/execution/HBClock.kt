/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.execution

import org.jetbrains.kotlinx.lincheck.Result

data class HBClock(val clock: IntArray) {
    val threads: Int get() = clock.size
    val empty: Boolean get() = clock.all { it == 0 }
    operator fun get(i: Int) = clock[i]

    fun set(other: HBClock) {
        check(clock.size == other.clock.size)
        for (i in clock.indices) {
            clock[i] = other.clock[i]
        }
    }

    fun reset() {
        for (i in clock.indices) {
            clock[i] = 0
        }
    }

    /**
     * Checks whether the clock contains information for any thread
     * excluding the one this clock is associated with.
     */
    fun isEmpty(clockThreadId: Int) = clock.filterIndexed { t, _ -> t != clockThreadId }.all { it == 0 }

    fun copy(): HBClock {
        return HBClock(clock.copyOf())
    }

    override fun toString() = clock.joinToString(prefix = "[", separator = ",", postfix = "]")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HBClock
        return clock.contentEquals(other.clock)
    }

    override fun hashCode() = clock.contentHashCode()
}

fun emptyClock(size: Int) = HBClock(emptyClockArray(size))
fun emptyClockArray(size: Int) = IntArray(size) { 0 }

data class ResultWithClock(val result: Result?, val clockOnStart: HBClock)

fun Result.withEmptyClock(threads: Int) = ResultWithClock(this, emptyClock(threads))
fun List<Result>.withEmptyClock(threads: Int): List<ResultWithClock> = map { it.withEmptyClock(threads) }
fun List<ResultWithClock>.withEmptyClock() = mapNotNull { it.result?.withEmptyClock(it.clockOnStart.threads) }