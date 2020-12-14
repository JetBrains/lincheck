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
import org.jetbrains.kotlinx.lincheck.nvm.nonVolatile
import org.jetbrains.kotlinx.lincheck.paramgen.ThreadIdGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private const val THREADS_NUMBER = 5

@StressCTest(
    sequentialSpecification = SequentialTestAndSet::class,
    threads = THREADS_NUMBER,
    addCrashes = true,
    actorsBefore = 0,
    actorsPerThread = 1
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
class NRLTestAndSet(private val threadsCount: Int) : VerifierState() {
    private val R = MutableList(threadsCount) { nonVolatile(0) }
    private val Response = MutableList(threadsCount) { nonVolatile(0) }

    @Volatile
    private var Winner = -1

    @Volatile
    private var Doorway = true

    // Volatile memory
    private val tas = LinearizableTestAndSet()

    override fun extractState() = tas.extractState()

    @Recoverable(recoverMethod = "testAndSetRecover")
    fun testAndSet(p: Int): Int {
        R[p].writeAndFlush(value = 1)
        val returnValue: Int
        if (!Doorway) {
            returnValue = 1
        } else {
            R[p].writeAndFlush(value = 2)
            Doorway = false
            returnValue = tas.testAndSet()
            if (returnValue == 0) {
                Winner = p
            }
        }
        Response[p].writeAndFlush(value = returnValue)
        R[p].writeAndFlush(value = 3)
        return returnValue
    }

    private fun testAndSetRecover(p: Int): Int {
        if (R[p].read() < 2) return testAndSet(p)
        if (R[p].read() == 3) return Response[p].read()
        if (Winner == -1) {
            Doorway = false
            R[p].writeAndFlush(value = 4)
            tas.testAndSet()
            for (i in 0 until p) {
                wailUntil { R[i].read().let { it == 0 || it == 3 } }
            }
            for (i in p + 1 until threadsCount) {
                wailUntil { R[i].read().let { it == 0 || it > 2 } }
            }
            if (Winner == -1) {
                Winner = p
            }
        }
        val returnValue = if (Winner == p) 0 else 1
        Response[p].writeAndFlush(value = returnValue)
        R[p].writeAndFlush(value = 3)
        return returnValue
    }

    private inline fun wailUntil(condition: () -> Boolean) {
        while (!condition()) {
        }
    }
}
