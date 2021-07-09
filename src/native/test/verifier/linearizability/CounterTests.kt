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
package verifier.linearizability

import AbstractLincheckStressTest
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import kotlin.native.concurrent.*
import kotlin.reflect.KClass

interface CounterTest {
    fun incAndGet(): Int
}

abstract class AbstractCounterTest(
    private val counter: Counter,
    vararg expectedErrors: KClass<out LincheckFailure>
) : AbstractLincheckStressTest<CounterTest>(*expectedErrors), CounterTest {
    override fun incAndGet(): Int = counter.incAndGet()

    fun <T : LincheckStressConfiguration<CounterTest>> T.customizeOperations() {
        operation(CounterTest::incAndGet)
    }

    override fun extractState(): Any = counter.get()
}

class CounterCorrectTest : AbstractCounterTest(CounterCorrect()) {
    override fun <T : LincheckStressConfiguration<CounterTest>> T.customize() {
        customizeOperations()

        initialState { CounterCorrectTest() }
    }
}

class CounterWrong0Test : AbstractCounterTest(CounterWrong0(), IncorrectResultsFailure::class) {
    override fun <T : LincheckStressConfiguration<CounterTest>> T.customize() {
        customizeOperations()

        initialState { CounterWrong0Test() }
    }
}

class CounterWrong1Test : AbstractCounterTest(CounterWrong1(), IncorrectResultsFailure::class) {
    override fun <T : LincheckStressConfiguration<CounterTest>> T.customize() {
        customizeOperations()

        initialState { CounterWrong1Test() }
    }
}

class CounterWrong2Test : AbstractCounterTest(CounterWrong2(), IncorrectResultsFailure::class) {
    override fun <T : LincheckStressConfiguration<CounterTest>> T.customize() {
        customizeOperations()

        initialState { CounterWrong2Test() }
    }
}

interface Counter {
    fun incAndGet(): Int
    fun get(): Int
}

private class CounterWrong0 : Counter {
    private var c: Int = 0

    override fun incAndGet(): Int = ++c
    override fun get(): Int = c
}

private class CounterWrong1 : Counter {
    private var c: Int = 0

    override fun incAndGet(): Int {
        c++
        return c
    }

    override fun get(): Int = c
}

private class CounterWrong2 : Counter {
    private var c: Int = 0

    override fun incAndGet(): Int = ++c
    override fun get(): Int = c
}

private class CounterCorrect : Counter {
    private val c = AtomicInt(0)

    override fun incAndGet(): Int = c.addAndGet(1)
    override fun get(): Int = c.value
}
