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
package org.jetbrains.kotlinx.lincheck.test.verifier.nlr

import junit.framework.Assert.assertTrue
import junit.framework.Assert.fail
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.annotations.Recoverable
import org.jetbrains.kotlinx.lincheck.nvm.NVMCache
import org.jetbrains.kotlinx.lincheck.nvm.nonVolatile
import org.jetbrains.kotlinx.lincheck.paramgen.ThreadIdGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

private const val THREADS_NUMBER = 2


@StressCTest(
    sequentialSpecification = SequentialCounter::class,
    threads = THREADS_NUMBER,
    addCrashes = true
)
internal class CounterFailingTest {
    private val counter = NRLFailingCounter(THREADS_NUMBER + 2)

    @Operation
    fun increment(@Param(gen = ThreadIdGen::class) threadId: Int) = counter.increment(threadId)

    @Operation
    fun get(@Param(gen = ThreadIdGen::class) threadId: Int) = counter.get(threadId)

    @Test
    fun testFails() {
        try {
            LinChecker.check(this::class.java)
        } catch (e: LincheckAssertionError) {
            assertTrue(
                "Expected invalid execution, but found: ${e.message}",
                e.message!!.contains("Invalid execution result")
            )
            return
        }
        fail("Invalid result expected")
    }
}

private class NRLFailingCounter @Recoverable constructor(threadsCount: Int) : VerifierState() {
    private val R = List(threadsCount) { NRLReadWriteObject<Int>(threadsCount).also { it.write(0, 0) } }
    private val Response = MutableList(threadsCount) { nonVolatile(0) }
    private val CheckPointer = MutableList(threadsCount) { nonVolatile(0) }
    private val CurrentValue = MutableList(threadsCount) { nonVolatile(0) }

    init {
        NVMCache.flush(0)
    }

    override fun extractState() = R.sumBy { it.read()!! }

    @Recoverable
    fun get(p: Int): Int {
        val returnValue = R.sumBy { it.read()!! }
        Response[p].write(returnValue, p)
        Response[p].flush(p)
        return returnValue
    }

    @Recoverable(beforeMethod = "incrementBefore", recoverMethod = "incrementRecover")
    fun increment(p: Int) {
        incrementImpl(p)
    }

    private fun incrementImpl(p: Int) {
        R[p].write(1 + CurrentValue[p].read(p), p)
        CheckPointer[p].write(1, p)
        CheckPointer[p].flush(p)
    }

    private fun incrementRecover(p: Int) {
        if (CheckPointer[p].read(p) == 0) return incrementImpl(p)
    }

    private fun incrementBefore(p: Int) {
        CurrentValue[p].write(R[p].read()!!, p)
        CheckPointer[p].write(0, p)
        CurrentValue[p].flush(p)
        CurrentValue[p].flush(p) // should be CheckPointer[p].flush(p)
    }
}
