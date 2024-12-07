/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_benchmark.running

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.specifications.*
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck_benchmark.AbstractLincheckBenchmark

@InternalCoroutinesApi
class BufferedChannelBenchmark : AbstractLincheckBenchmark() {
    private val c = Channel<Int>(2)

    @Operation(cancellableOnSuspension = false)
    suspend fun send(value: Int) = c.send(value)

    @Operation(cancellableOnSuspension = false)
    suspend fun receive() = c.receive()

    @Operation
    fun poll() = c.tryReceive().getOrNull()

    override fun <O : Options<O, *>> O.customize() {
        iterations(10)
        sequentialSpecification(SequentiaBuffered2IntChannel::class.java)
    }
}

@InternalCoroutinesApi
class SequentiaBuffered2IntChannel : SequentialIntChannel(capacity = 2)