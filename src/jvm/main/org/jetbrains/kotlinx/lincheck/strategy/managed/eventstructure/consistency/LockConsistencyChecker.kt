/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.kotlinx.lincheck.util.*

class LockConsistencyViolation(val event1: Event, val event2: Event) : Inconsistency()

class LockConsistencyChecker : ConsistencyChecker<AtomicThreadEvent, MutableExtendedExecution> {

    override fun check(execution: MutableExtendedExecution): Inconsistency? {
        /*
         * We construct a map that maps an unlock (or notify) event to its single matching lock (or wait) event.
         * Because the single initialization event may encode several initial unlock events
         * (for different lock objects), we need to handle this case in a special way.
         * Therefore, for the first lock (the one synchronizing-with the initialization event),
         * we instead use the lock object itself as the key in the map.
         */
        // TODO: make incremental (inconsistency can be triggered when processing unlock/notify event)
        // TODO: generalize (to arbitrary unique-flagged events) and refactor!
        // TODO: unify with the atomicity checker?
        val mapping = mutableMapOf<Any, Event>()
        for (event in execution) {
            val label = event.label.refine<MutexLabel> { isResponse && (this is LockLabel || this is WaitLabel) }
                ?: continue
            if (label is WaitLabel && (event.notifiedBy.label as NotifyLabel).isBroadcast)
                continue
            val key: Any = when (event.syncFrom.label) {
                is UnlockLabel, is NotifyLabel -> event.syncFrom
                else -> label.mutexID
            }
            val other = mapping.put(key, event)
            if (other != null)
                return LockConsistencyViolation(event, other)
        }
        return null
    }

}