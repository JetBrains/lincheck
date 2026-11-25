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
import org.jetbrains.lincheck.datastructures.Operation
import org.junit.Test
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class ConcurrentLinkedQueueTest {
    private val s = ConcurrentLinkedQueue<Int>()

    @Operation
    fun add(value: Int) = s.add(value)

    @Operation
    fun poll(): Int? = s.poll()

    @Test
    fun stressTest() = Lincheck.runConcurrentTest(LincheckSettings {
        stress()
        scenarioGeneration { sequentialSpecification = SequentialQueue::class.java }
    }, this::class)
}

class SequentialQueue {
    private val q = LinkedList<Int>()

    fun add(x: Int) = q.add(x)
    fun poll(): Int? = q.poll()
}
