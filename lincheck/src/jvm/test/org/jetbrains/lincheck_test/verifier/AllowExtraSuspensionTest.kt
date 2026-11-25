/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck_test.verifier

import org.jetbrains.lincheck.LincheckSettings
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck_test.*
import java.util.concurrent.atomic.*

class AllowExtraSuspensionCorrectTest : AbstractLincheckTest() {
    private val mutex = Mutex()
    private var counter = AtomicInteger()

    @Operation
    suspend fun inc(): Int = mutex.withLock {
        counter.getAndIncrement()
    }

    @Operation
    suspend fun dec() = counter.getAndDecrement()

    override fun LincheckSettings.customize() {
        // We ignore this test with EventStructure for now, as it uses coroutines, which are not currently supported
        ignoreExperimentalModelChecking()
        scenarioGeneration { sequentialSpecification = CounterSequential::class.java }
    }
}

class AllowExtraSuspensionIncorrectTest : AbstractLincheckTest() {
    private val mutex = Mutex()
    private var counter = AtomicInteger()

    @Operation
    suspend fun inc(): Int = mutex.withLock {
        counter.getAndIncrement()
    }

    @Operation
    suspend fun dec() = counter.getAndDecrement()

    override fun LincheckSettings.customize() {
        // We ignore this test with EventStructure for now, as it uses coroutines, which are not currently supported
        ignoreExperimentalModelChecking()
        scenarioGeneration { sequentialSpecification = CounterSequential::class.java }
    }
}

// One of the operations should always succeed without suspension
class OnlyExtraSuspensionsHaveToBeAtomicTest : AbstractLincheckTest() {
    private val c = atomic(0)

    @InternalCoroutinesApi
    @Operation(cancellableOnSuspension = true)
    suspend fun operation1() {
        val c = c.incrementAndGet()
        if (c == 6) return
        suspendCancellableCoroutine<Unit> {  }
    }

    @InternalCoroutinesApi
    @Operation
    suspend fun operation2() {
        val c = c.incrementAndGet()
        if (c == 6) return
        suspendCancellableCoroutine<Unit> {  }
    }

    override fun LincheckSettings.customize() {
        // We ignore this test with EventStructure for now, as it uses coroutines, which are not currently supported
        ignoreExperimentalModelChecking()
        scenarioGeneration {
            randomScenarios = 10
            operationsBefore = 0
            threads = 2
            operationsPerThread = 3
            operationsAfter = 0
        }
    }
}

class CounterSequential {
    private var counter = 0

    suspend fun inc(): Int = counter++
    suspend fun dec() = counter--
}
