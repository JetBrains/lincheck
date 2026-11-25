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
import org.jetbrains.kotlinx.lincheck_test.datastructures.*
import org.jetbrains.lincheck.*
import org.jetbrains.lincheck.datastructures.*
import org.junit.*

class ObstructionFreedomViolationTest {
    private val q = MSQueueBlocking()

    @Operation
    fun enqueue(x: Int) = q.enqueue(x)

    @Operation
    fun dequeue(): Int? = q.dequeue()

    //@Test // TODO: Please, uncomment me and comment the line below to run the test and get the output
    @Test(expected = AssertionError::class)
    fun runModelCheckingTest() = Lincheck.runConcurrentTest(LincheckSettings {
        modelChecking { checkObstructionFreedom = true }
    }, this::class)

    @Ignore("Times out")
    @Test(expected = AssertionError::class)
    fun runEventStructureModelCheckingTest() = Lincheck.runConcurrentTest(LincheckSettings {
        modelChecking {
            checkObstructionFreedom = true
            enablePartialOrderReduction()
        }
    }, this::class)
}
