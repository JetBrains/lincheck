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

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.annotations.Recoverable
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.paramgen.ThreadIdGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private const val THREADS_NUMBER = 5

interface TAS {
    fun testAndSet(threadId: Int): Int
}

@StressCTest(
    sequentialSpecification = SequentialTestAndSet::class,
    threads = THREADS_NUMBER,
    recover = Recover.NRL,
    actorsBefore = 0,
    actorsPerThread = 1,
    minimizeFailedScenario = false
)
internal class TestAndSetTest {
    private val tas = NRLTestAndSet(THREADS_NUMBER + 2)

    @Operation
    fun testAndSet(@Param(gen = ThreadIdGen::class) threadId: Int) = tas.testAndSet(threadId)

    @Test
    fun test() = LinChecker.check(this::class.java)
}

internal class SequentialTestAndSet : VerifierState() {
    private var value = 0
    fun testAndSet() = value.also { value = 1 }
    fun testAndSet(ignore: Int) = testAndSet()
    override fun extractState() = value
}

internal class LinearizableTestAndSet : VerifierState() {
    private val value = AtomicInteger(0)

    fun testAndSet() = if (value.compareAndSet(0, 1)) 0 else 1
    public override fun extractState() = value.get()
}

/**
 * @see  <a href="https://www.cs.bgu.ac.il/~hendlerd/papers/NRL.pdf">Nesting-Safe Recoverable Linearizability</a>
 */
internal class NRLTestAndSet(private val threadsCount: Int) : VerifierState(), TAS {
    private val r = MutableList(threadsCount) { nonVolatile(0) }
    private val response = MutableList(threadsCount) { nonVolatile(0) }
    private val winner = nonVolatile(-1)
    private val doorway = nonVolatile(true)
    private val tas = nonVolatile(0)

    override fun extractState() = tas.value

    @Recoverable(recoverMethod = "testAndSetRecover")
    override fun testAndSet(p: Int): Int {
        r[p].setAndFlush(1)
        val returnValue: Int
        if (!doorway.value) {
            returnValue = 1
        } else {
            r[p].setAndFlush(2)
            doorway.setAndFlush(false)
            returnValue = if (tas.compareAndSet(0, 1)) 0 else 1
            if (returnValue == 0) {
                winner.setAndFlush(p)
            }
        }
        response[p].setAndFlush(returnValue)
        r[p].setAndFlush(3)
        return returnValue
    }

    private fun testAndSetRecover(p: Int): Int {
        if (r[p].value < 2) return testAndSet(p)
        if (r[p].value == 3) return response[p].value
        if (winner.value == -1) {
            doorway.setAndFlush(false)
            r[p].setAndFlush(4)
            for (i in 0 until p) {
                wailUntil { r[i].value.let { it == 0 || it == 3 } }
            }
            for (i in p + 1 until threadsCount) {
                wailUntil { r[i].value.let { it == 0 || it > 2 } }
            }
            if (winner.value == -1) {
                winner.setAndFlush(p)
            }
        }
        val returnValue = if (winner.value == p) 0 else 1
        response[p].setAndFlush(returnValue)
        r[p].setAndFlush(3)
        return returnValue
    }

    private inline fun wailUntil(condition: () -> Boolean) {
        while (!condition()) {
        }
    }
}
