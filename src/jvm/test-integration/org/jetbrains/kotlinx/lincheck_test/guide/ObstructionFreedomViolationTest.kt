/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.guide

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck_test.datastructures.MSQueueBlocking
import org.junit.*

class ObstructionFreedomViolationTest  {
    private val q = MSQueueBlocking()

    @Operation
    fun enqueue(x: Int) = q.enqueue(x)

    @Operation
    fun dequeue(): Int? = q.dequeue()

    //@Test // TODO: Please, uncomment me and comment the line below to run the test and get the output
    @Test(expected = AssertionError::class)
    fun runModelCheckingTest() = ModelCheckingOptions()
        .checkObstructionFreedom(true)
        .check(this::class)
}