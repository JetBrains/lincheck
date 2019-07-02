/*-
 * #%L
 * lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
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

package org.jetbrains.kotlinx.lincheck.test.verifier.quasi

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.quasi.QuasiLinearizabilityVerifier
import org.jetbrains.kotlinx.lincheck.verifier.quasi.QuasiLinearizabilityVerifierConf
import org.junit.Ignore
import org.junit.Test
import java.util.*

private const val K = 3

@Ignore
@StressCTest(verifier = QuasiLinearizabilityVerifier::class, iterations = 10, invocationsPerIteration = 2000, actorsBefore = 30, actorsAfter = 10, actorsPerThread = 5, threads = 2)
@QuasiLinearizabilityVerifierConf(factor = K, sequentialImplementation = KRelaxedPopStackTest.StackImpl::class)
class KRelaxedPopStackTest {
    private val s = KRelaxedPopStack<Int>(3)

    @Operation
    fun push(item: Int) = s.push(item)

    /**
     * Several fictitious push operations are added to increase the number of push operations in generated scenario
     * -> increase the stack size for k-relaxed pop() operation
     */
    @Operation
    fun push1(item: Int) = s.push(item)

    @Operation
    fun push2(item: Int) = s.push(item)

    @Operation
    fun push3(item: Int) = s.push(item)

    @Operation
    fun push4(item: Int) = s.push(item)

    @Operation
    fun pop(): Int? = s.pop()

    @Test
    fun test() = LinChecker.check(KRelaxedPopStackTest::class.java)

    data class StackImpl (val s: LinkedList<Int> = LinkedList()) {
        fun push(item: Int) = s.push(item)
        fun push1(item: Int) = s.push(item)
        fun push2(item: Int) = s.push(item)
        fun push3(item: Int) = s.push(item)
        fun push4(item: Int) = s.push(item)
        fun pop(): Int? = if (s.isEmpty()) null else s.pop()
    }
}
