/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.datastructures.linearizable

import org.jetbrains.lincheck_test.AbstractLincheckIntegrationTest
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.Param
import java.util.concurrent.ConcurrentLinkedQueue

@Param(name = "value", gen = IntGen::class, conf = "1:5")
class ConcurrentLinkedQueueTest : AbstractLincheckIntegrationTest() {
    private val queue = ConcurrentLinkedQueue<Int>()

    @Operation
    fun add(e: Int) = queue.add(e)

    @Operation
    fun offer(e: Int) = queue.offer(e)

    @Operation
    fun peek() = queue.peek()

    @Operation
    fun poll() = queue.poll()

    override fun LincheckSettings.customize() {
        if (strategy is TestingStrategy.ModelChecking) (strategy as TestingStrategy.ModelChecking).analyzeStdLib(true)
    }
}
