/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.test.distributed.common

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.distributed.NodeFailureInfo
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest
import org.junit.Test
import java.util.concurrent.ThreadLocalRandom
import kotlin.concurrent.thread

class NodeFailureInfoTest : AbstractLincheckTest() {
    var nodeFailureInfo = NodeFailureInfo(5, 2)
    val counter = atomic(0)

    @Test
    fun test() {
        for (i in 0 until 10) {
            val jobs = mutableListOf<Job>()
            repeat(5) {
                jobs += GlobalScope.launch(newSingleThreadContext(it.toString())){
                    repeat(100) { i ->
                        delay(ThreadLocalRandom.current().nextInt(0, 5).toLong())
                        if (nodeFailureInfo.trySetFailed(it)) {
                            counter.incrementAndGet()
                            return@launch
                        }
                    }
                }
            }
            runBlocking {
                jobs.forEach { it.join() }
            }
            check(counter.value <= 2)
            counter.lazySet(0)
            nodeFailureInfo = NodeFailureInfo(5, 2)
        }
    }
}