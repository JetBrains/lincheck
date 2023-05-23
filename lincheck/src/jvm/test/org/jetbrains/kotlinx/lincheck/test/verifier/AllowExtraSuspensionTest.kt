/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
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
        requireStateEquivalenceImplCheck(false)
    }
}

class CounterSequential : VerifierState() {
    private var counter = 0

    suspend fun inc(): Int = counter++
    suspend fun dec() = counter--

    override fun extractState() = counter
}
