/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test

import org.jetbrains.lincheck.Lincheck.runConcurrentTest
import org.jetbrains.lincheck.LincheckSettings
import kotlinx.atomicfu.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.verifier.EpsilonVerifier
import org.junit.*

class BlockingOperationTest {
    @Operation(blocking = true)
    fun blocking(): Unit = synchronized(this) {}

    @Test
    fun test() = runConcurrentTest(LincheckSettings {
        modelChecking { checkObstructionFreedom = true }
        scenarioGeneration {
            verifier = EpsilonVerifier::class.java
            operationsBefore = 0
            operationsAfter = 0
        }
    }, this::class)
}

class CausesBlockingOperationTest {
    private val counter = atomic(0)

    @Operation(blocking = true)
    fun operation() {
        while (counter.value % 2 != 0) {}
    }

    @Operation
    fun causesBlocking() {
        counter.incrementAndGet()
        counter.incrementAndGet()
    }

    @Test
    fun test() = runConcurrentTest(LincheckSettings {
        modelChecking { checkObstructionFreedom = true }
        scenarioGeneration {
            verifier = EpsilonVerifier::class.java
            randomScenarios = 20
            operationsBefore = 0
            operationsAfter = 0
        }
    }, this::class)
}