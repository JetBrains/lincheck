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

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.test.*

@InternalCoroutinesApi
@Param(name = "value", gen = IntGen::class, conf = "1:5")
class BufferedChannelTest : AbstractLincheckTest() {
    private val c = Channel<Int>(2)

    @Operation(cancellableOnSuspension = false)
    suspend fun send(@Param(name = "value") value: Int) = c.send(value)

    @Operation(cancellableOnSuspension = false)
    suspend fun receive() = c.receive()

    @Operation
    fun poll() = c.tryReceive().getOrNull()

    @Operation
    fun offer(@Param(name = "value") value: Int) = c.trySend(value).isSuccess

    override fun <O : Options<O, *>> O.customize() {
        sequentialSpecification(SequentiaBuffered2IntChannel::class.java)
    }
}

@InternalCoroutinesApi
class SequentiaBuffered2IntChannel : SequentialIntChannel(capacity = 2)