/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.verifier.linearizability

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest
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
class CounterWithArrayWrongTest : AbstractCounterTest(CounterWithArrayWrong(), IncorrectResultsFailure::class)

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

private class CounterWithArrayWrong : Counter {
    private var counter = arrayOf(CounterImpl())

    override fun incAndGet(): Int = counter[0].incAndGet()
    override fun get(): Int = counter[0].get()

    private class CounterImpl {
        private var c: Int = 0

        fun incAndGet(): Int = ++c
        fun get(): Int = c
    }
}

private class CounterCorrect : Counter {
    private val c = AtomicInteger()

    override fun incAndGet(): Int = c.incrementAndGet()
    override fun get(): Int = c.get()
}
