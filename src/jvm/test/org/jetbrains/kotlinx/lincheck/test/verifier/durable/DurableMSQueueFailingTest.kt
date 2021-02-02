/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.test.verifier.durable

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.paramgen.ThreadIdGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.test.verifier.linearizability.SequentialQueue
import org.junit.Assert.assertThrows
import org.junit.Test

private const val THREADS_NUMBER = 3

@StressCTest(
    sequentialSpecification = SequentialQueue::class,
    threads = THREADS_NUMBER,
    recover = Recover.DURABLE
)
class DurableMSQueueFailingTest { // no recover method
    private val q = DurableMSQueue<Int>(2 + THREADS_NUMBER)

    @Operation
    fun push(value: Int) = q.push(value)

    @Operation
    fun pop(@Param(gen = ThreadIdGen::class) threadId: Int) = q.pop(threadId)

    @Test
    fun testFails() {
        assertThrows(Throwable::class.java) { LinChecker.check(this::class.java) }
    }
}
