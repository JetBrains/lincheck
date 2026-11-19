/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.IntUnaryOperator

/**
 * Checks that a function-update method of a JDK atomic appears in the execution graph
 * as the read and the compare-and-set of its retry loop.
 */
class EventStructureAtomicUpdateFunctionTest : BaseTraceRepresentationTest("event_structure_atomic_update_function") {
    private val counter = AtomicInteger(0)

    override fun operation() {
        check(counter.updateAndGet(Increment) == 1)
        check(counter.get() == 1)
    }

    override fun ModelCheckingOptions.customize() {
        useExperimentalModelChecking()
        memoryModel(MemoryModel.RelaxedJava)
        printExecutionGraph(true)
    }

    // Not a lambda: the name of a lambda class, which the graph would contain, changes from run to run.
    private object Increment : IntUnaryOperator {
        override fun applyAsInt(operand: Int): Int = operand + 1
    }
}
