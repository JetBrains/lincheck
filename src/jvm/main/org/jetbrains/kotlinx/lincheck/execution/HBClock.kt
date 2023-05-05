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

    override fun toString() = clock.joinToString(prefix = "[", separator = ",", postfix = "]") {
        if (it == 0) "-" else "$it"
    }

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

data class ResultWithClock(val result: Result, val clockOnStart: HBClock)

fun Result.withEmptyClock(threads: Int) = ResultWithClock(this, emptyClock(threads))
fun List<Result>.withEmptyClock(threads: Int): List<ResultWithClock> = map { it.withEmptyClock(threads) }
fun List<ResultWithClock>.withEmptyClock() = map { it.result.withEmptyClock(it.clockOnStart.threads) }