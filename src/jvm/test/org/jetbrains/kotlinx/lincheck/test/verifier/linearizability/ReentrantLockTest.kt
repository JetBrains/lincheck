/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import org.jetbrains.kotlinx.lincheck.NoResult
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.runner.NoResultError
import org.jetbrains.kotlinx.lincheck.runner.ParkedThreadFinish
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest

class ReentrantLockTest : AbstractLincheckTest() {
    private val semaphore = java.util.concurrent.Semaphore(1, true)

    @Operation(cancellableOnSuspension = false, allowExtraSuspension = true)
    fun acquire() {
        semaphore.acquire()
    }

    @Operation
    fun release() {
        semaphore.release()
    }

    override fun <O : Options<O, *>> O.customize() {
        actorsBefore(0)
        actorsAfter(0)
        sequentialSpecification(SemaphoreSequential::class.java)
    }
}

class SemaphoreSequential {
    private val s = kotlinx.coroutines.sync.Semaphore(100, 99)

    suspend fun acquire() {
        s.acquire()
    }
    fun release() {
        s.release()
    }
}