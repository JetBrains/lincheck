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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest

@ExperimentalCoroutinesApi
@InternalCoroutinesApi
@Param(name = "value", gen = IntGen::class, conf = "1:5")
class RendezvousChannelTest : AbstractLincheckTest() {
    private val ch = Channel<Int>()

    @Operation(handleExceptionsAsResult = [ClosedSendChannelException::class])
    suspend fun send(@Param(name = "value") value: Int) = ch.send(value)

    @Operation(handleExceptionsAsResult = [ClosedReceiveChannelException::class])
    suspend fun receive() = ch.receive()

    @Operation(handleExceptionsAsResult = [ClosedReceiveChannelException::class])
    suspend fun receiveOrNull() = ch.receiveCatching().getOrNull()

    @Operation
    fun close() = ch.close()

    override fun <O : Options<O, *>> O.customize() {
        sequentialSpecification(SequentialRendezvousIntChannel::class.java)
        iterations(10)
    }
}

@InternalCoroutinesApi
class SequentialRendezvousIntChannel : SequentialIntChannel(capacity = 0)