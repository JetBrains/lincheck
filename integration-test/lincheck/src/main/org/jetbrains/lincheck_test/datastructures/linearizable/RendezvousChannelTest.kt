/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck_test.datastructures.linearizable

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.kotlinx.lincheck_test.*
import org.jetbrains.lincheck_test.datastructures.SequentialIntChannel
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Options
import org.jetbrains.lincheck.datastructures.Param

@ExperimentalCoroutinesApi
@InternalCoroutinesApi
@Param(name = "value", gen = IntGen::class, conf = "1:5")
class RendezvousChannelTest : AbstractLincheckTest() {
    private val ch = Channel<Int>()

    @Operation
    suspend fun send(@Param(name = "value") value: Int) = ch.send(value)

    @Operation
    suspend fun receive() = ch.receive()

    @Operation
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