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
import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicInteger

class EventStructureExecutionGraphTest : BaseTraceRepresentationTest("event_structure_execution_graph") {
    private var value = 0
    @Volatile private var published = 0
    private val counter = AtomicInteger(0)

    override fun operation() {
        value = 7
        VarHandle.releaseFence()
        published = 1
        VarHandle.acquireFence()
        check(published == 1)
        check(value == 7)
        check(counter.compareAndSet(0, 2))
    }

    override fun ModelCheckingOptions.customize() {
        useExperimentalModelChecking()
        memoryModel(MemoryModel.RelaxedJava)
    }
}
