/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
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

/**
 * Removes from the synchronization candidates of a read request the writes
 * it cannot read from without violating happens-before consistency.
 *
 * For each thread, the latest write to the read's location in the request's happens-before prefix is taken.
 * A candidate that happens-before one of these writes is overwritten by it before the read,
 * so reading from it would close the cycle `rf⁻¹ ; hb ; hb`
 * through a write that the coherence order must place after the candidate.
 * The same-thread case is a write program-order-before the thread's latest observed write.
 *
 * Shared by the memory models whose coherence order respects happens-before between same-location accesses
 * (release-acquire and relaxed Java); the sequentially consistent checker has its own, stronger filter.
 *
 * @param readRequest the read request event, already added to [execution].
 * @param candidates the writes to the read's location that the request may synchronize with.
 * @return the candidates that are not overwritten in the request's happens-before prefix.
 */
internal fun filterOverwrittenWrites(
    execution: ExtendedExecution,
    readRequest: AtomicThreadEvent,
    candidates: Sequence<AtomicThreadEvent>,
): Sequence<AtomicThreadEvent> {
    val location = (readRequest.label as ReadAccessLabel).location
    val observedWrites = execution.memoryAccessEventIndex
        .getLatestWrites(location, readRequest.happensBeforeClock)
        .toList()
    return candidates.filter { write ->
        observedWrites.none { observed -> happensBeforeOrder(write, observed) }
    }
}
