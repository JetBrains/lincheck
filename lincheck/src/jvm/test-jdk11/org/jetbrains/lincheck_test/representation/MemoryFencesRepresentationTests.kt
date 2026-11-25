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

import org.jetbrains.lincheck.LincheckSettings.MemoryModel
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.util.UnsafeHolder
import java.lang.invoke.VarHandle

/** Checks how the memory fence calls of `VarHandle` and `Unsafe` appear in the trace. */
class MemoryFencesTraceRepresentationTest : BaseTraceRepresentationTest("memory_fences_trace") {

    override fun operation() {
        val unsafe = UnsafeHolder.UNSAFE
        VarHandle.fullFence()
        VarHandle.acquireFence()
        VarHandle.releaseFence()
        VarHandle.loadLoadFence()
        VarHandle.storeStoreFence()
        unsafe.fullFence()
        unsafe.loadFence()
        unsafe.storeFence()
    }
}

/** Checks which fence events the experimental strategy creates for the fence calls of `VarHandle` and `Unsafe`. */
class MemoryFencesExecutionGraphTest : BaseTraceRepresentationTest("memory_fences_execution_graph") {
    private var value = 0

    override fun operation() {
        val unsafe = UnsafeHolder.UNSAFE
        value = 1
        VarHandle.storeStoreFence()
        value = 2
        VarHandle.loadLoadFence()
        value = 3
        unsafe.storeFence()
        value = 4
        unsafe.loadFence()
        value = 5
        unsafe.fullFence()
    }

    override fun ModelCheckingOptions.customize() {
        useExperimentalModelChecking()
        memoryModel(MemoryModel.RelaxedJava)
        printExecutionGraph(true)
    }
}
