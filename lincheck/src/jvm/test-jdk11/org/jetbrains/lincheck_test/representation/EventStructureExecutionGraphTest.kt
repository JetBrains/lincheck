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
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicInteger

class EventStructureExecutionGraphTest : BaseTraceRepresentationTest("event_structure_execution_graph") {
    private var value = 0
    @Volatile private var published = 0
    private val counter = AtomicInteger(0)
    private val chars = charArrayOf('\u8003')
    private val booleans = booleanArrayOf(true)

    companion object {
        private val charHandle = MethodHandles.arrayElementVarHandle(CharArray::class.java)
        private val booleanHandle = MethodHandles.arrayElementVarHandle(BooleanArray::class.java)
    }

    override fun operation() {
        value = 7
        VarHandle.releaseFence()
        published = 1
        VarHandle.acquireFence()
        check(published == 1)
        check(value == 7)
        check(counter.compareAndSet(0, 2))
        check(charHandle.getAndBitwiseAnd(chars, 0, '\u8001') as Char == '\u8003')
        check(chars[0] == '\u8001')
        check(booleanHandle.getAndBitwiseXor(booleans, 0, true) as Boolean)
        check(!booleans[0])
    }

    override fun ModelCheckingOptions.customize() {
        useExperimentalModelChecking()
        memoryModel(MemoryModel.RelaxedJava)
        printExecutionGraph(true)
    }
}
