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

/** Checks [MemoryModel.JAM21] executions using RMW atomicity, writes-before, and volatile PSC acyclicity. */
class JmmConsistencyChecker : ConsistencyChecker<AtomicThreadEvent, MutableExtendedExecution> {

    private val releaseAcquireChecker = ReleaseAcquireConsistencyChecker()

    override fun check(execution: MutableExtendedExecution): Inconsistency? {
        execution.readModifyWriteOrderComputable.apply {
            // The aggregate can defer incremental updates while its verdict is unknown.
            reset()
            initialize()
            compute()
        }
        val readModifyWriteOrder = execution.readModifyWriteOrderComputable.value
        if (!readModifyWriteOrder.isConsistent()) return ReadModifyWriteAtomicityViolation()

        // A full check can follow any number of additions or a reset to another execution frontier.
        execution.writesBeforeOrderComputable.reset()
        releaseAcquireChecker.check(execution)?.let { return it }
        val writesBeforeOrder = execution.writesBeforeOrderComputable.value

        val volatileEvents = execution.filter { it.isVolatileAccess() }
        if (volatileEvents.isEmpty()) return null
        val enumerator = buildEnumerator(volatileEvents)
        val coherenceOrders = CoherenceOrder.generate(
            execution,
            execution.memoryAccessEventIndex,
            readModifyWriteOrder,
            writesBeforeOrder,
            happensBeforeSameLocationOrder,
        )
        for (coherenceOrder in coherenceOrders) {
            val order = SequentialConsistencyMixedAccessOrder(coherenceOrder)
            if (topologicalSorting(order.toGraph(volatileEvents, enumerator)) != null) return null
        }
        return CoherenceViolation()
    }
}
