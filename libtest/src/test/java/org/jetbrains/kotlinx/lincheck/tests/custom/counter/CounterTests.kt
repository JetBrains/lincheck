/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.tests.custom.counter

import org.jetbrains.kotlinx.lincheck.ErrorType
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.tests.AbstractLinCheckTest

class CounterWrong0Test : AbstractLinCheckTest(expectedError = ErrorType.INCORRECT_RESULTS) {
    private val counter = CounterWrong0()

    @Operation
    fun incAndGet(): Int = counter.incrementAndGet()

    override fun extractState(): Any = counter.get()
}

class CounterCorrectTest : AbstractLinCheckTest(expectedError = ErrorType.NO_ERROR) {
    private val counter = CounterCorrect()

    @Operation
    fun incAndGet(): Int = counter.incrementAndGet()

    override fun extractState(): Any = counter.get()
}

class CounterWrong1Test : AbstractLinCheckTest(expectedError = ErrorType.INCORRECT_RESULTS) {
    private val counter = CounterWrong1()

    @Operation
    fun incAndGet(): Int = counter.incrementAndGet()

    override fun extractState(): Any = counter.get()
}

class CounterWrong2Test : AbstractLinCheckTest(expectedError = ErrorType.INCORRECT_RESULTS) {
    private val counter = CounterWrong2()

    @Operation
    fun incAndGet(): Int = counter.incrementAndGet()

    override fun extractState(): Any = counter.get()
}

class CounterGetTest : AbstractLinCheckTest(expectedError = ErrorType.NO_ERROR) {
    private val counter = CounterGet()

    @Operation
    fun incAndGet(): Int = counter.incrementAndGet()

    @Operation
    fun get(): Int = counter.get()

    override fun extractState(): Any = counter.get()
}