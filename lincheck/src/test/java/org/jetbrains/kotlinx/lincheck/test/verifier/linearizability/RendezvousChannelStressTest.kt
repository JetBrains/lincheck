/*-
 * #%L
 * Lincheck
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
package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import kotlinx.coroutines.channels.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.*
import org.junit.*

@LogLevel(LoggingLevel.DEBUG)
@Param(name = "value", gen = IntGen::class, conf = "1:5")
@StressCTest(sequentialSpecification = SequentialRendezvousIntChannel::class, verifier = LinearizabilityVerifier::class)
class RendezvousChannelStressTest {
    private val ch = Channel<Int>()

    @Operation(handleExceptionsAsResult = [ClosedSendChannelException::class])
    suspend fun send(@Param(name = "value") value: Int) = ch.send(value)

    @Operation(handleExceptionsAsResult = [ClosedReceiveChannelException::class])
    suspend fun receive() = ch.receive() + 100

    @Operation(handleExceptionsAsResult = [ClosedReceiveChannelException::class])
    suspend fun receiveOrNull() = ch.receiveOrNull()?.plus(100)

    @Operation
    fun close() = ch.close()

    @Test
    fun test() = LinChecker.check(RendezvousChannelStressTest::class.java)
}

class SequentialRendezvousIntChannel : SequentialIntChannel(capacity = 0)