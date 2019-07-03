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
package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import kotlinx.coroutines.channels.Channel
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityVerifier
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

@Param(name = "value", gen = IntGen::class, conf = "1:5")
@StressCTest(verifier = LinearizabilityVerifier::class, actorsAfter = 0)
class BufferedChannelMixedStressTest : VerifierState() {

    val ch = Channel<Int>(2)

    @Operation
    suspend fun send(@Param(name = "value") value: Int) = ch.send(value)

    @Operation
    suspend fun receive() = ch.receive()

    @Operation
    fun poll() = ch.poll()

    @Operation
    fun offer(@Param(name = "value") value: Int) = ch.offer(value)

    @Test
    fun test() = LinChecker.check(BufferedChannelMixedStressTest::class.java)

    override fun extractState(): Any {
        val elements = mutableListOf<Int>()
        while (!ch.isEmpty) elements.add(ch.poll()!!)
        val closed = ch.isClosedForSend || ch.isClosedForReceive
        return elements to closed
    }
}
