/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.util.VectorClock
import org.jetbrains.kotlinx.lincheck.util.mutableThreadMapOf
import org.jetbrains.lincheck.util.collections.*

/**
 * Stores events satisfying [predicate], grouped by thread in program order.
 */
class ThreadEventIndex<E : ThreadEvent>(private val predicate: (E) -> Boolean) {
    private val eventsByThread = mutableThreadMapOf<SortedMutableList<E>>()

    /**
     * Indexes [event] if it satisfies [predicate].
     * Events must be indexed in program order.
      */
    fun index(event: E) {
        if (!predicate(event)) return
        val events = eventsByThread.getOrPut(event.threadId) { sortedMutableListOf() }
        check(events.isEmpty() || events.last().threadPosition < event.threadPosition)
        events.add(event)
    }

    /**
     * Returns the list of events satisfying [predicate] from given [threadId].
     */
    operator fun get(threadId: Int): SortedList<E> =
        eventsByThread[threadId] ?: sortedListOf()

    /**
     * Returns the latest match at or before [position], or null if there is none.
     */
    fun latestAt(threadId: Int, position: Int): E? {
        val events = get(threadId)
        val index = events.binarySearchBy(position) { it.threadPosition }
        // On a miss, `binarySearchBy` returns `-insertionPoint - 1`,
        // where `insertionPoint` is the index of the first event after [position];
        // thus, `-index - 2 == insertionPoint - 1` is the index of the latest event before [position],
        // or `-1` if there is none, in which case `getOrNull` returns null.
        return events.getOrNull(if (index >= 0) index else -index - 2)
    }

    /**
     * Returns the latest match in each thread's prefix specified by [clock].
     */
    fun latestAt(clock: VectorClock): Sequence<E> =
        eventsByThread.keys.asSequence().mapNotNull { thread -> latestAt(thread, clock[thread]) }

    /**
     * Clears the index.
     */
    fun reset() {
        eventsByThread.clear()
    }
}
