/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.representation

import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.LincheckSettings.MemoryModel
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.IntUnaryOperator

/**
 * Checks how function-update calls on a JDK atomic appear in an event-structure trace.
 */
class EventStructureAtomicUpdateFunctionTest : BaseTraceRepresentationTest("event_structure_atomic_update_function") {
    private val counter = AtomicInteger(0)

    override fun operation() {
        check(counter.updateAndGet(Increment) == 1)
        check(counter.get() == 1)
    }

    override fun LincheckSettings.customize() {
        modelChecking {
            enablePartialOrderReduction()
            memoryModel = MemoryModel.RelaxedJava
        }
    }

    // Use a named object to keep its representation stable between runs.
    private object Increment : IntUnaryOperator {
        override fun applyAsInt(operand: Int): Int = operand + 1
    }
}
