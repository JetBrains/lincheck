/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test

import kotlinx.atomicfu.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*

class BlockingOperationTest {
    @Operation(blocking = true)
    fun blocking(): Unit = synchronized(this) {}

    @Test
    fun test() = LincheckOptions {
        (this as LincheckOptionsImpl)
        mode = LincheckMode.ModelChecking
        testingTimeInSeconds = 1
        verifier = EpsilonVerifier::class.java
        checkObstructionFreedom = true
        generateBeforeAndAfterParts = false
    }.check(this::class)

}

class CausesBlockingOperationTest {
    private val counter = atomic(0)

    @Operation
    fun operation() {
        while (counter.value % 2 != 0) {}
    }

    @Operation(causesBlocking = true)
    fun causesBlocking() {
        counter.incrementAndGet()
        counter.incrementAndGet()
    }

    @Test
    fun test() = LincheckOptions {
        (this as LincheckOptionsImpl)
        mode = LincheckMode.ModelChecking
        verifier = EpsilonVerifier::class.java
        checkObstructionFreedom = true
        generateBeforeAndAfterParts = false
    }.check(this::class)
}