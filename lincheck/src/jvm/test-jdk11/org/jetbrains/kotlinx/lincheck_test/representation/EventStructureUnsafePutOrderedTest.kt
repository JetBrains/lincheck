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
import org.jetbrains.lincheck.util.UnsafeHolder

/**
 * Checks that `sun.misc.Unsafe.putOrdered*` is a release write in the execution graph.
 */
class EventStructureUnsafePutOrderedTest : BaseTraceRepresentationTest("event_structure_unsafe_put_ordered") {
    private var value = 0

    @Suppress("DEPRECATION")
    override fun operation() {
        // The offset is not kept in a field: reading it would put a layout-dependent number into the graph.
        unsafe.putOrderedInt(this, unsafe.objectFieldOffset(valueField), 1)
        check(value == 1)
    }

    override fun ModelCheckingOptions.customize() {
        useExperimentalModelChecking()
        memoryModel(MemoryModel.RelaxedJava)
        printExecutionGraph(true)
    }

    companion object {
        private val unsafe = UnsafeHolder.UNSAFE
        private val valueField = EventStructureUnsafePutOrderedTest::class.java.getDeclaredField("value")
    }
}
