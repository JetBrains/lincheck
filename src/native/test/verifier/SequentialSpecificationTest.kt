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
package verifier

import AbstractLincheckStressTest
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import kotlin.native.concurrent.*

interface CounterInterface {
    fun set(value: Int)
    fun get(): Int
}

class TestCounter : VerifierState(), CounterInterface {
    private val c = AtomicInt(0)

    override fun set(value: Int) {
        c.value = value
    }

    override fun get() = c.value + 1
    override fun extractState() = c.value
}

class SequentialSpecificationTest : AbstractLincheckStressTest<CounterInterface>(IncorrectResultsFailure::class) {
    override fun <T : LincheckStressConfiguration<CounterInterface>> T.customize() {
        sequentialSpecification(SequentialSpecification<CounterInterface> { CorrectCounter() })

        initialState { TestCounter() }

        operation(CounterInterface::get)
        operation(IntGen(""), CounterInterface::set)
    }
}


class CorrectCounter : VerifierState(), CounterInterface {
    private var c = 0
    override fun set(value: Int) {
        c = value
    }

    override fun get(): Int = c
    override fun extractState() = c
}
