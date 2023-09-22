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

import org.jetbrains.kotlinx.lincheck.ensure
import org.jetbrains.kotlinx.lincheck.execution.HBClock
import org.jetbrains.kotlinx.lincheck.execution.emptyClock
import kotlin.math.max

interface VectorClock {
    fun isEmpty(): Boolean

    operator fun get(tid: ThreadID): Int
}

interface MutableVectorClock : VectorClock {
    fun put(tid: ThreadID, timestamp: Int): Int

    fun merge(other: VectorClock)

    fun clear()
}

fun VectorClock.observes(tid: ThreadID, timestamp: Int): Boolean =
    timestamp <= get(tid)

operator fun MutableVectorClock.set(tid: ThreadID, timestamp: Int) {
    put(tid, timestamp)
}

fun MutableVectorClock.update(tid: ThreadID, timestamp: Int) {
    put(tid, timestamp).ensure { it < timestamp }
}

fun MutableVectorClock.increment(tid: ThreadID, n: Int) {
    put(tid, get(tid) + n)
}

fun MutableVectorClock.increment(tid: ThreadID) {
    increment(tid, 1)
}

// TODO: use sealed interfaces to make when exhaustive
operator fun VectorClock.plus(other: VectorClock): MutableVectorClock =
    copy().apply { merge(other) }

fun VectorClock.copy(): MutableVectorClock {
    check(this is IntArrayClock)
    return copy()
}

fun VectorClock(nThreads: Int): VectorClock =
    MutableVectorClock(nThreads)

fun MutableVectorClock(nThreads: Int): MutableVectorClock =
    IntArrayClock(nThreads)

private class IntArrayClock(val nThreads: Int) : MutableVectorClock {
    val clock = IntArray(nThreads) { -1 }

    override fun isEmpty(): Boolean =
        clock.all { it == -1 }

    override fun get(tid: ThreadID): Int =
        clock[tid]

    override fun put(tid: ThreadID, timestamp: Int): Int =
        clock[tid].also { clock[tid] = timestamp }

    override fun merge(other: VectorClock) {
        for (i in 0 until nThreads) {
            clock[i] = max(clock[i], other[i])
        }
    }

    override fun clear() {
        clock.fill(-1)
    }

    fun copy() = IntArrayClock(nThreads).also {
        for (i in 0 until nThreads) {
            it.clock[i] = clock[i]
        }
    }

    override fun equals(other: Any?): Boolean =
        (other is IntArrayClock) && (clock.contentEquals(other.clock))

    override fun hashCode(): Int =
        clock.contentHashCode()

}

fun VectorClock.toHBClock(tid: ThreadID, aid: Int): HBClock {
    check(this is IntArrayClock)
    val result = emptyClock(clock.size - 2)
    for (i in 0 until clock.size - 2) {
        if (i == tid) {
            result.clock[i] = clock[i].ensure { it == aid }
            continue
        }
        result.clock[i] = 1 + clock[i]
    }
    return result
}

// class VectorClock<P, T>(val partialOrder: PartialOrder<T>, clock: Map<P, T> = mapOf()) {
//
//     private val _clock: MutableMap<P, T> = clock.toMutableMap()
//
//     // TODO: this pattern is covered by explicit backing fields KEEP
//     //   https://github.com/Kotlin/KEEP/issues/278
//     val clock: Map<P, T>
//         get() = _clock
//
//     fun copy(): VectorClock<P, T> =
//         VectorClock(partialOrder, this.clock)
//
//     operator fun get(part: P): T? =
//         clock[part]
//
//     fun observes(part: P, timestamp: T): Boolean =
//         clock[part]?.let { partialOrder.lessOrEqual(timestamp, it) } ?: false
//
//     fun outdated(part: P, timestamp: T): Boolean =
//         clock[part]?.let { partialOrder.lessThan(timestamp, it) } ?: false
//
//     operator fun set(part: P, timestamp: T) {
//         _clock[part] = timestamp
//     }
//
//     fun update(part: P, timestamp: T) {
//         _clock.update(part, default = timestamp) { oldTimestamp ->
//             require(partialOrder.lessOrEqual(oldTimestamp, timestamp)) {
//                 "Attempt to update vector clock to older timestamp: old=$oldTimestamp, new=$timestamp."
//             }
//             timestamp
//         }
//     }
//
//     operator fun plus(other: VectorClock<P, T>) =
//         copy().apply { merge(other) }
//
//     infix fun merge(other: VectorClock<P, T>) {
//         require(partialOrder == other.partialOrder) {
//             "Attempt to merge vector clocks ordered by differed partial orders."
//         }
//         _clock.mergeReduce(other.clock, partialOrder::max)
//     }
//
// }