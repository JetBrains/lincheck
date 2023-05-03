/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.test.verifier

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.test.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.util.concurrent.atomic.*

class AllowExtraSuspensionCorrectTest : AbstractLincheckTest() {
    private val mutex = Mutex()
    private var counter = AtomicInteger()

    @Operation(allowExtraSuspension = true)
    suspend fun inc(): Int = mutex.withLock {
        counter.getAndIncrement()
    }

    @Operation
    suspend fun dec() = counter.getAndDecrement()

    override fun <O : Options<O, *>> O.customize() {
        sequentialSpecification(CounterSequential::class.java)
    }
}

class AllowExtraSuspensionIncorrectTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    private val mutex = Mutex()
    private var counter = AtomicInteger()

    @Operation
    suspend fun inc(): Int = mutex.withLock {
        counter.getAndIncrement()
    }

    @Operation
    suspend fun dec() = counter.getAndDecrement()

    override fun <O : Options<O, *>> O.customize() {
        sequentialSpecification(CounterSequential::class.java)
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
    @Operation(allowExtraSuspension = true)
    suspend fun operation2() {
        val c = c.incrementAndGet()
        if (c == 6) return
        suspendCancellableCoroutine<Unit> {  }
    }

    override fun <O : Options<O, *>> O.customize() {
        iterations(10)
        actorsBefore(0)
        threads(2)
        actorsPerThread(3)
        actorsAfter(0)
    }
}

class CounterSequential : VerifierState() {
    private var counter = 0

    suspend fun inc(): Int = counter++
    suspend fun dec() = counter--

    override fun extractState() = counter
}
