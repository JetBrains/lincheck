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

package org.jetbrains.kotlinx.lincheck.util

import org.jetbrains.kotlinx.lincheck.execution.HBClock
import org.jetbrains.kotlinx.lincheck.execution.emptyClock
import kotlin.math.*

interface VectorClock {
    fun isEmpty(): Boolean

    operator fun get(tid: ThreadID): Int
}

interface MutableVectorClock : VectorClock {
    operator fun set(tid: ThreadID, timestamp: Int)

    fun increment(tid: ThreadID, n: Int) {
        set(tid, get(tid) + n)
    }

    fun merge(other: VectorClock)

    fun clear()
}

fun VectorClock.observes(tid: ThreadID, timestamp: Int): Boolean =
    timestamp <= get(tid)

fun MutableVectorClock.increment(tid: ThreadID) {
    increment(tid, 1)
}

operator fun VectorClock.plus(other: VectorClock): MutableVectorClock =
    copy().apply { merge(other) }

fun VectorClock(capacity: Int = 0): VectorClock =
    MutableVectorClock(capacity)

fun MutableVectorClock(capacity: Int = 0): MutableVectorClock =
    IntArrayClock(capacity)

fun VectorClock.copy(): MutableVectorClock {
    // TODO: make VectorClock sealed interface?
    check(this is IntArrayClock)
    return copy()
}

private class IntArrayClock(capacity: Int = 0) : MutableVectorClock {
    var clock = emptyIntArrayClock(capacity)

    val capacity: Int
        get() = clock.size

    override fun isEmpty(): Boolean =
        clock.all { it == -1 }

    override fun get(tid: ThreadID): Int =
        if (tid < capacity) clock[tid] else -1

    override fun set(tid: ThreadID, timestamp: Int) {
        expandIfNeeded(tid)
        clock[tid] = timestamp
    }

    override fun increment(tid: ThreadID, n: Int) {
        expandIfNeeded(tid)
        clock[tid] += n
    }

    override fun merge(other: VectorClock) {
        // TODO: make VectorClock sealed interface?
        check(other is IntArrayClock)
        if (capacity < other.capacity) {
            expand(other.capacity)
        }
        for (i in 0 until capacity) {
            clock[i] = max(clock[i], other[i])
        }
    }

    override fun clear() {
        clock.fill(-1)
    }

    private fun expand(newCapacity: Int) {
        require(newCapacity > capacity)
        val newClock = emptyIntArrayClock(newCapacity)
        copyInto(newClock)
        clock = newClock
    }

    private fun expandIfNeeded(tid: ThreadID) {
        if (tid >= capacity) {
            expand(tid + 1)
        }
    }

    fun copy() = IntArrayClock(capacity).also { copyInto(it.clock) }

    private fun copyInto(other: IntArray) {
        require(other.size >= capacity)
        // TODO: use arraycopy?
        //  System.arraycopy(old, 0, clock, 0, capacity)
        for (i in 0 until capacity) {
            other[i] = clock[i]
        }
    }

    override fun equals(other: Any?): Boolean =
        (other is IntArrayClock) && (clock.contentEquals(other.clock))

    override fun hashCode(): Int =
        clock.contentHashCode()

    override fun toString() =
        clock.joinToString(prefix = "[", separator = ",", postfix = "]")

    companion object {
        private fun emptyIntArrayClock(capacity: Int) =
            IntArray(capacity) { -1 }
    }
}

fun VectorClock.toHBClock(capacity: Int, tid: ThreadID, aid: Int): HBClock {
    check(this is IntArrayClock)
    val result = emptyClock(capacity)
    for (i in 0 until capacity) {
        if (i == tid) {
            result.clock[i] = get(i)
            continue
        }
        result.clock[i] = 1 + get(i)
    }
    return result
}