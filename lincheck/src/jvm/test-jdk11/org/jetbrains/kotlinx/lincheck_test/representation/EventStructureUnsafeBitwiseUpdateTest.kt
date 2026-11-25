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

import org.jetbrains.lincheck.LincheckSettings.MemoryModel
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import java.util.concurrent.locks.ReentrantLock

/**
 * Checks how cancelling a condition wait appears in an event-structure trace.
 *
 * A timed-out wait on a condition cancels its queue node with `Unsafe.getAndBitwiseAndInt`.
 */
class EventStructureUnsafeBitwiseUpdateTest : BaseTraceRepresentationTest("event_structure_unsafe_bitwise_update") {
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    override fun operation() {
        lock.lock()
        try {
            condition.awaitNanos(0L)
        } finally {
            lock.unlock()
        }
    }

    override fun ModelCheckingOptions.customize() {
        useExperimentalModelChecking()
        memoryModel(MemoryModel.RelaxedJava)
    }
}
