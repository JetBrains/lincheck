/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

internal typealias TraceDebuggerEventId = Long

/**
 * Internal interface representing an abstract tracker for trace debugger events.
 * Provides functionality to handle and manipulate trace debugger event IDs to ensure consistency
 * across multiple runs, especially in scenarios involving cached computations.
 */
internal interface AbstractTraceDebuggerEventTracker: AutoCloseable {
    /**
     * Resets ids numeration and starts them from 0.
     */
    fun resetIds()
    
    /**
     * Advances the current trace debugger event id with the delta, associated with the old id [oldId],
     * previously received with [getNextId].
     *
     * If for the given [oldId] there is no saved `newId`,
     * the function saves the current trace debugger event id and associates it with the [oldId].
     * On subsequent re-runs, when for the given [oldId] there exists a saved `newId`,
     * the function sets the counter to the `newId`.
     *
     * This function is typically used to account for some cached computations:
     * on the first run the actual computation is performed and its result is cached,
     * and on subsequent runs the cached value is re-used.
     * One example of such a situation is the `invokedynamic` instruction.
     *
     * In such cases, on the first run, the performed computation may allocate more trace debugger events,
     * assigning more trace debugger event ids to them.
     * On subsequent runs, however, these trace debugger events will not be allocated,
     * and thus the trace debugger event ids numbering may vary.
     * To account for this, before the first invocation of the cached computation,
     * the last allocated trace debugger event id [oldId] can be saved, and after the computation,
     * the new last trace debugger event id can be associated with it via a call `advanceCurrentId(oldId)`.
     * On subsequent re-runs, the cached computation will be skipped, but the
     * current trace debugger event id will still be advanced by the required delta via a call to `advanceCurrentId(oldId)`.
     */
    fun advanceCurrentId(oldId: TraceDebuggerEventId)
    
    /**
     * @return id of the current trace debugger event and increments the global counter.
     */
    fun getNextId(): TraceDebuggerEventId
}
