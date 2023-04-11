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
package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest
import java.util.concurrent.atomic.*
import kotlin.reflect.KClass

abstract class AbstractCounterTest(
    private val counter: Counter,
    vararg expectedErrors: KClass<out LincheckFailure>
) : AbstractLincheckTest(*expectedErrors) {
    @Operation
    fun incAndGet(): Int = counter.incAndGet()
}

class CounterCorrectTest : AbstractCounterTest(CounterCorrect())
class CounterWrong0Test : AbstractCounterTest(CounterWrong0(), IncorrectResultsFailure::class)
class CounterWrong1Test : AbstractCounterTest(CounterWrong1(), IncorrectResultsFailure::class)
class CounterWrong2Test : AbstractCounterTest(CounterWrong2(), IncorrectResultsFailure::class)

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
    @Volatile
    private var c: Int = 0

    override fun incAndGet(): Int = ++c
    override fun get(): Int = c
}

private class CounterCorrect : Counter {
    private val c = AtomicInteger()

    override fun incAndGet(): Int = c.incrementAndGet()
    override fun get(): Int = c.get()
}
