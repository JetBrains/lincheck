/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
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
    fun maxThreadId(): Int
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

fun VectorClock(): VectorClock =
    ThreadMapClock()

fun MutableVectorClock(defaultVal: Int = -1): MutableVectorClock =
    ThreadMapClock(defaultVal)

fun VectorClock.copy(): MutableVectorClock {
    // TODO: make VectorClock sealed interface?
    check(this is ThreadMapClock)
    return copy()
}


private class ThreadMapClock(private val defaultVal: Int = -1) : MutableVectorClock {
    var clock = mutableThreadMapOf<Int>()

    override fun isEmpty(): Boolean =
        clock.isEmpty()

    override fun get(tid: ThreadId): Int =
        clock.getOrDefault(tid, defaultVal)

    override fun maxThreadId(): Int {
        return clock.keys.maxOrNull() ?: -1
    }

    override fun set(tid: ThreadId, timestamp: Int) {
        clock.set(tid, timestamp)
    }

    override fun increment(tid: ThreadId, n: Int) {
        // TODO: not sure what is the exact behaviour when tid is not already there
        // Note that the default value here is 0.
        clock.set(tid, get(tid) + n)
    }

    override fun merge(other: VectorClock) {
        // TODO: make VectorClock sealed interface?
        check(other is ThreadMapClock)
        for (i in other.clock.keys) {
            clock[i] = max(get(i), other[i])
        }
    }

    override fun clear() {
        clock.clear();
    }

    fun copy(): ThreadMapClock  {
        val newClock = ThreadMapClock()
        newClock.clock = clock.toMutableMap()
        return newClock
    }

    private fun copyFrom(other: ThreadMapClock) {
        clock = other.clock.toMutableMap();
    }

    override fun equals(other: Any?): Boolean =
        (other is ThreadMapClock) && (clock.equals(other.clock))

    override fun hashCode(): Int =
        clock.hashCode()

    override fun toString() =
        clock.toString()
}

fun VectorClock.toHBClock(capacity: Int, tid: ThreadId, aid: Int): HBClock {
    check(this is ThreadMapClock)
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