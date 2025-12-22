/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util

import org.jetbrains.kotlinx.lincheck.execution.HBClock
import org.jetbrains.kotlinx.lincheck.execution.emptyClock
import kotlin.math.*

interface VectorClock {
    fun isEmpty(): Boolean

    operator fun get(tid: ThreadId): Int
}

interface MutableVectorClock : VectorClock {
    operator fun set(tid: ThreadId, timestamp: Int)

    fun increment(tid: ThreadId, n: Int) {
        set(tid, get(tid) + n)
    }

    fun merge(other: VectorClock)

    fun clear()
}

fun VectorClock.observes(tid: ThreadId, timestamp: Int): Boolean =
    timestamp <= get(tid)

fun MutableVectorClock.increment(tid: ThreadId) {
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

    override fun get(tid: ThreadId): Int =
        if (tid < capacity) clock[tid] else -1

    override fun set(tid: ThreadId, timestamp: Int) {
        expandIfNeeded(tid)
        clock[tid] = timestamp
    }

    override fun increment(tid: ThreadId, n: Int) {
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

    private fun expandIfNeeded(tid: ThreadId) {
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

fun VectorClock.toHBClock(capacity: Int, tid: ThreadId, aid: Int): HBClock {
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