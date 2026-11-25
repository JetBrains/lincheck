/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.guide

import org.jetbrains.lincheck.Lincheck
import org.jetbrains.lincheck.LincheckSettings
import org.jctools.queues.atomic.MpscLinkedAtomicQueue
import org.jetbrains.lincheck.datastructures.Operation
import org.junit.Ignore
import org.junit.Test

class MPSCQueueTest {
    private val queue = MpscLinkedAtomicQueue<Int>()

    @Operation
    public fun offer(x: Int) = queue.offer(x)

    @Operation(nonParallelGroup = "consumers")
    public fun poll(): Int? = queue.poll()

    @Operation(nonParallelGroup = "consumers")
    public fun peek(): Int? = queue.peek()

    @Test
    fun stressTest() = Lincheck.runConcurrentTest(LincheckSettings { stress() }, this::class)

    @Test
    fun modelCheckingTest() = Lincheck.runConcurrentTest(LincheckSettings { modelChecking() }, this::class)

    @Ignore("We need to support loop detection for this test")
    @Test
    fun eventStructureModelCheckingTest() = Lincheck.runConcurrentTest(LincheckSettings {
        modelChecking { enablePartialOrderReduction() }
    }, this::class)
}
