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

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.kotlinx.lincheck.util.observes
import org.jetbrains.lincheck.util.MemoryOrdering
import org.jetbrains.lincheck.util.Relation

/**
 * Orders volatile accesses in an execution that may also contain weaker memory accesses.
 * Uses the PSC variant `[Vol] (co ∪ rb ∪ po ∪ hbloc ∪ po;hb;po) [Vol]`, without SC fences.
 */
class SequentialConsistencyMixedAccessOrder(
    val coherenceOrder: Relation<AtomicThreadEvent>,
) : Relation<AtomicThreadEvent> {

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        if (!x.isVolatileAccess() || !y.isVolatileAccess()) return false

        // Both co and rb end at a write; rb starts at a read of an earlier write.
        if (y.label is WriteAccessLabel && getLocationForSameLocationAccesses(x, y) != null) {
            val writeX = if (x.label is ReadAccessLabel) x.readsFrom else x
            if (coherenceOrder(writeX, y)) return true
        }
        if (x.threadId == y.threadId && x.threadPosition < y.threadPosition) return true
        if (happensBeforeSameLocationOrder(x, y)) return true

        // The immediate successor of x and predecessor of y witness po;hb;po.
        val parentY = y.parent ?: return false
        return parentY.happensBeforeClock.observes(x.threadId, x.threadPosition + 1)
    }
}

internal fun AtomicThreadEvent.isVolatileAccess(): Boolean {
    val label = label as? MemoryAccessLabel ?: return false
    return label.memoryOrdering == MemoryOrdering.VOLATILE && (label.isWrite || label.isResponse)
}
