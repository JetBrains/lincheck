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

import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck_benchmark.AbstractLincheckBenchmark
import java.util.concurrent.*

@Param(name = "value", gen = IntGen::class, conf = "1:5")
class ConcurrentLinkedQueueBenchmark : AbstractLincheckBenchmark() {
    private val queue = ConcurrentLinkedQueue<Int>()

    @Operation
    fun add(e: Int) = queue.add(e)

    @Operation
    fun offer(e: Int) = queue.offer(e)

    @Operation
    fun peek() = queue.peek()

    @Operation
    fun poll() = queue.poll()

}
