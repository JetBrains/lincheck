/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck_test.verifier.linearizability

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.kotlinx.lincheck_test.*
import org.jetbrains.lincheck_test.datastructures.SequentialIntChannel
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Options
import org.jetbrains.lincheck.datastructures.Param

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

    override fun <O : Options<O, *>> O.customize() {
        sequentialSpecification(SequentialBuffered2IntChannel::class.java)
    }
}

@InternalCoroutinesApi
class SequentialBuffered2IntChannel : SequentialIntChannel(capacity = 2)