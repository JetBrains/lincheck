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
package org.jetbrains.kotlinx.lincheck.test.verifier

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@StressCTest(sequentialSpecification = CorrectCounter::class)
class IncorrectCounter {
    private var c = AtomicInteger()

    @Operation
    fun set(value: Int) = c.set(value)
    @Operation
    fun get() = c.get() + 1

    @Test(expected = AssertionError::class)
    fun test() = LinChecker.check(this::class.java)
}

class CorrectCounter: VerifierState() {
    private var c = 0
    fun set(value: Int) { c = value }
    fun get(): Int = c
    override fun extractState() = c
}
