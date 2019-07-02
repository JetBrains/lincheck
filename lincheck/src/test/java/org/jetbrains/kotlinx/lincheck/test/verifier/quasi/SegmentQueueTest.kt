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

private const val K = 2

@Ignore
@StressCTest(verifier = QuasiLinearizabilityVerifier::class, iterations = 10, invocationsPerIteration = 10, actorsBefore = 5, actorsAfter = 5, actorsPerThread = 10, threads = 2)
@QuasiLinearizabilityVerifierConf(factor = K, sequentialImplementation = SegmentQueueTest.SeqImpl::class)
class SegmentQueueTest {
    private val q = SegmentQueue<Int>(2)

    @Operation
    fun enqueue(item: Int) = q.enqueue(item)

    @Operation
    fun dequeue(): Int? = q.dequeue()

    @Test
    fun test() = LinChecker.check(SegmentQueueTest::class.java)

    data class SeqImpl @JvmOverloads constructor(val q: Queue<Int> = ArrayDeque<Int>()) {
        fun enqueue(item: Int) = q.offer(item)
        fun dequeue(): Int? = q.poll()
    }
}
