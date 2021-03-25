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
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.annotations.Recoverable
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.paramgen.ThreadIdGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Ignore
import org.junit.Test

private const val THREADS_NUMBER = 3

internal interface Counter {
    fun increment(threadId: Int)
    fun get(threadId: Int): Int
}

/**
 * @see  <a href="https://www.cs.bgu.ac.il/~hendlerd/papers/NRL.pdf">Nesting-Safe Recoverable Linearizability</a>
 */
@StressCTest(
    sequentialSpecification = SequentialCounter::class,
    threads = THREADS_NUMBER,
    recover = Recover.NRL
)
internal class CounterTest : Counter {
    private val counter = NRLCounter(THREADS_NUMBER + 2)

    @Operation
    override fun increment(@Param(gen = ThreadIdGen::class) threadId: Int) = counter.increment(threadId)

    @Operation
    override fun get(@Param(gen = ThreadIdGen::class) threadId: Int) = counter.get(threadId)

    @Test
    fun test() = LinChecker.check(this::class.java)
}

internal class SequentialCounter : VerifierState(), Counter {
    private var value = 0

    override fun get(threadId: Int) = value
    override fun increment(threadId: Int) {
        value++
    }

    override fun extractState() = value
}

private class NRLCounter(threadsCount: Int) : Counter {
    private val r = List(threadsCount) { NRLReadWriteObject<Int>(threadsCount).also { it.write(0, 0) } }
    private val checkPointer = MutableList(threadsCount) { nonVolatile(0) }
    private val currentValue = MutableList(threadsCount) { nonVolatile(0) }

    @Recoverable
    override fun get(threadId: Int) = r.sumBy { it.read()!! }

    @Recoverable(beforeMethod = "incrementBefore", recoverMethod = "incrementRecover")
    override fun increment(threadId: Int) = incrementImpl(threadId)

    private fun incrementImpl(p: Int) {
        r[p].write(1 + currentValue[p].value, p)
        checkPointer[p].value = 1
    }

    private fun incrementRecover(p: Int) {
        if (checkPointer[p].value == 0) return incrementImpl(p)
    }

    private fun incrementBefore(p: Int) {
        currentValue[p].value = r[p].read()!!
        checkPointer[p].value = 0
        currentValue[p].flush()
        checkPointer[p].flush()
    }
}

@StressCTest(
    sequentialSpecification = SequentialCounter::class,
    threads = THREADS_NUMBER,
    recover = Recover.NRL,
    minimizeFailedScenario = false
)
internal abstract class CounterFailingTest {
    private val counter = createFailingCounter()

    abstract fun createFailingCounter(): Counter

    @Operation
    fun increment(@Param(gen = ThreadIdGen::class) threadId: Int) = counter.increment(threadId)

    @Operation
    fun get(@Param(gen = ThreadIdGen::class) threadId: Int) = counter.get(threadId)

    @Test(expected = LincheckAssertionError::class)
    fun testFails() = LinChecker.check(this::class.java)
}

internal class CounterFailingTest1 : CounterFailingTest() {
    override fun createFailingCounter() = NRLFailingCounter1(THREADS_NUMBER + 2)
}

@Ignore("Can't find an error. To be fixed")
internal class CounterFailingTest2 : CounterFailingTest() {
    override fun createFailingCounter() = NRLFailingCounter2(THREADS_NUMBER + 2)
}

internal class CounterFailingTest3 : CounterFailingTest() {
    override fun createFailingCounter() = NRLFailingCounter3(THREADS_NUMBER + 2)
}

internal class CounterFailingTest4 : CounterFailingTest() {
    override fun createFailingCounter() = NRLFailingCounter4(THREADS_NUMBER + 2)
}

internal class NRLFailingCounter1(threadsCount: Int) : VerifierState(), Counter {
    private val r = List(threadsCount) { NRLReadWriteObject<Int>(threadsCount).also { it.write(0, 0) } }
    private val checkPointer = MutableList(threadsCount) { nonVolatile(0) }
    private val currentValue = MutableList(threadsCount) { nonVolatile(0) }

    override fun extractState() = r.sumBy { it.read()!! }

    @Recoverable
    override fun get(threadId: Int) = r.sumBy { it.read()!! }

    @Recoverable(beforeMethod = "incrementBefore", recoverMethod = "incrementRecover")
    override fun increment(threadId: Int) = incrementImpl(threadId)

    private fun incrementImpl(p: Int) {
        r[p].write(1 + currentValue[p].value, p)
        checkPointer[p].value = 1
    }

    private fun incrementRecover(p: Int) {
        if (checkPointer[p].value == 0) return incrementImpl(p)
    }

    private fun incrementBefore(p: Int) {
        currentValue[p].value = r[p].read()!!
        checkPointer[p].value = 0
        // here should be currentValue[p].flush()
        checkPointer[p].flush()
    }
}

internal class NRLFailingCounter2(threadsCount: Int) : VerifierState(), Counter {
    private val r = List(threadsCount) { NRLReadWriteObject<Int>(threadsCount).also { it.write(0, 0) } }
    private val checkPointer = MutableList(threadsCount) { nonVolatile(0) }
    private val currentValue = MutableList(threadsCount) { nonVolatile(0) }

    override fun extractState() = r.sumBy { it.read()!! }

    @Recoverable
    override fun get(threadId: Int) = r.sumBy { it.read()!! }

    @Recoverable(beforeMethod = "incrementBefore", recoverMethod = "incrementRecover")
    override fun increment(threadId: Int) = incrementImpl(threadId)

    private fun incrementImpl(p: Int) {
        r[p].write(1 + currentValue[p].value, p)
        checkPointer[p].value = 1
    }

    private fun incrementRecover(p: Int) {
        if (checkPointer[p].value == 0) return incrementImpl(p)
    }

    private fun incrementBefore(p: Int) {
        currentValue[p].value = r[p].read()!!
        checkPointer[p].value = 0
        currentValue[p].flush()
        // here should be checkPointer[p].flush()
    }
}

internal class NRLFailingCounter3(threadsCount: Int) : VerifierState(), Counter {
    private val r = List(threadsCount) { NRLReadWriteObject<Int>(threadsCount).also { it.write(0, 0) } }
    private val checkPointer = MutableList(threadsCount) { nonVolatile(0) }
    private val currentValue = MutableList(threadsCount) { nonVolatile(0) }

    override fun extractState() = r.sumBy { it.read()!! }

    @Recoverable
    override fun get(threadId: Int) = r.sumBy { it.read()!! }

    @Recoverable(beforeMethod = "incrementBefore", recoverMethod = "incrementRecover")
    override fun increment(threadId: Int) = incrementImpl(threadId)

    private fun incrementImpl(p: Int) {
        r[p].write(1 + currentValue[p].value, p)
        checkPointer[p].value = 1
    }

    private fun incrementRecover(p: Int) {
        // incrementImpl should be called
        if (checkPointer[p].value == 0) return increment(p)
    }

    private fun incrementBefore(p: Int) {
        currentValue[p].value = r[p].read()!!
        checkPointer[p].value = 0
        currentValue[p].flush()
        checkPointer[p].flush()
    }
}

internal class NRLFailingCounter4(threadsCount: Int) : VerifierState(), Counter {
    private val r = List(threadsCount) { NRLReadWriteObject<Int>(threadsCount).also { it.write(0, 0) } }
    private val checkPointer = MutableList(threadsCount) { nonVolatile(0) }
    private val currentValue = MutableList(threadsCount) { nonVolatile(0) }

    override fun extractState() = r.sumBy { it.read()!! }

    @Recoverable
    override fun get(threadId: Int) = r.sumBy { it.read()!! }

    @Recoverable(beforeMethod = "incrementBefore", recoverMethod = "incrementRecover")
    override fun increment(threadId: Int) = incrementImpl(threadId)

    private fun incrementImpl(p: Int) {
        // incorrect order of writes
        checkPointer[p].value = 1
        r[p].write(1 + currentValue[p].value, p)
    }

    private fun incrementRecover(p: Int) {
        if (checkPointer[p].value == 0) return incrementImpl(p)
    }

    private fun incrementBefore(p: Int) {
        currentValue[p].value = r[p].read()!!
        checkPointer[p].value = 0
        currentValue[p].flush()
        checkPointer[p].flush()
    }
}
