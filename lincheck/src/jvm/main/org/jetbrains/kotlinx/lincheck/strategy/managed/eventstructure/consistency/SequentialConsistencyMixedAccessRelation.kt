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
import org.jetbrains.lincheck.util.MemoryOrdering
import org.jetbrains.lincheck.util.Relation

/**
 * Orders volatile accesses in an execution that may also contain weaker memory accesses.
 *
 * Uses the following PSC (partial sequential consistency order) relation variant:
 *     `[Vol] ; (po ∪ co ∪ rb ∪ hbloc ∪ po;hb;po) ; [Vol]`.
 *
 * The relation is not transitively closed, thus it is not an order by itself;
 * it is only suitable for checking acyclicity, which is equivalent to irreflexivity of its transitive closure.
 *
 * Sequentially consistent fences `fullFence()` are not yet supported.
 *
 * See the following paper on the details of the PSC relation definition:
 *   - [Repairing sequential consistency in C/C++ 11](https://dl.acm.org/doi/pdf/10.1145/3140587.3062352).
 */
class SequentialConsistencyMixedAccessRelation(
    val execution: Execution<AtomicThreadEvent>,
    val coherenceOrder: Relation<AtomicThreadEvent>,
) : Relation<AtomicThreadEvent> {

    override fun invoke(x: AtomicThreadEvent, y: AtomicThreadEvent): Boolean {
        // skip non-volatile accesses
        if (!x.isVolatileAccess() || !y.isVolatileAccess()) return false

        // po (program order)
        if (x.threadId == y.threadId && x.threadPosition < y.threadPosition) return true

        if (sameLocation(x, y)) {
            // co (coherence)
            if (x.label is WriteAccessLabel && y.label is WriteAccessLabel) {
                if (coherenceOrder(x, y)) return true
            }

            // rb (read-before)
            if (x.label is ReadAccessLabel && y.label is WriteAccessLabel) {
                if (coherenceOrder(x.readsFrom, y)) return true
            }

            // hbloc (same-location happens-before)
            if (happensBeforeOrder(x, y)) return true
        }

        // po ; hb ; po
        val xSuccessor = execution.nextEvent(x) ?: return false
        val yPredecessor = execution.previousEvent(y) ?: return false
        if (happensBeforeOrder(xSuccessor, yPredecessor)) return true

        return false
    }
}

internal fun AtomicThreadEvent.isVolatileAccess(): Boolean {
    val label = label as? MemoryAccessLabel ?: return false
    return label.memoryOrdering == MemoryOrdering.VOLATILE && (label.isWrite || label.isResponse)
}
