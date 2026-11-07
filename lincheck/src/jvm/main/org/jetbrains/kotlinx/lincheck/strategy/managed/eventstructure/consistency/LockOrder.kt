/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency

import org.jetbrains.kotlinx.lincheck.strategy.managed.ObjectNumber
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.AtomicThreadEvent
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.EventLabel
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.Execution
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.ExecutionTracker
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.LockLabel
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.MutexLabel
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.UnlockLabel
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.eventPositionComparator

/**
 * Orders lock responses and unlocks of each mutex for exploration,
 * independently of the execution's critical-section order.
 * Mutual exclusion is checked separately by [LockConsistencyChecker].
 */
class LockOrder : ExecutionTracker<AtomicThreadEvent, Execution<AtomicThreadEvent>> {
    private val positions = mutableMapOf<ObjectNumber, MutableMap<AtomicThreadEvent, Int>>()

    override fun onReset(execution: Execution<AtomicThreadEvent>) {
        positions.clear()
        execution.filter { it.label.isOrdered }.sortedWith(eventPositionComparator).forEach(::onAdd)
    }

    override fun onAdd(event: AtomicThreadEvent) {
        val label = event.label
        if (!label.isOrdered) return
        val events = positions.getOrPut((label as MutexLabel).mutexID) { mutableMapOf() }
        check(event !in events)
        events[event] = events.size
    }

    /**
     * Returns an ordered event's position, or -1 for a non-mutex event.
     */
    fun position(event: AtomicThreadEvent): Int {
        val label = event.label as? MutexLabel ?: return -1
        return positions.getValue(label.mutexID).getValue(event)
    }

    /**
     * Sorts candidates by their mutex events for exploration.
     */
    fun <T> sort(events: List<T>, selector: (T) -> AtomicThreadEvent): List<T> =
        events.sortedBy { position(selector(it)) }

    private val EventLabel.isOrdered: Boolean
        get() = this is UnlockLabel || this is LockLabel && isResponse
}
