/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test925
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.test925.RunChecker925.Companion.pool
import org.jetbrains.kotlinx.lincheck_test.gpmc.coroutines.BaseRunCoroutineTests
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Ignore

class Example {
    val inputChannel = Channel<Int>()
    val outputChannel = Channel<Int>()
    val processingChannel1 = Channel<Int>()
    val processingChannel2 = Channel<Int>()
}

fun main(): Unit = runBlocking(pool) {
    val example = Example()

    launch(pool) {
        for (i in 1..5) {
            example.inputChannel.send(i)
        }
        example.inputChannel.close()
    }

    launch(pool) {
        processInput(example)
    }

    repeat(5) {
        val res = example.outputChannel.receive()
        check(res == ((it + 1) * 10 + 1) * 2)
    }
}

suspend fun processInput(example: Example) = coroutineScope {
    launch(pool) {
        for (i in example.inputChannel) {
            example.processingChannel1.send(i * 10)
        }
        example.processingChannel1.close()
    }

    launch(pool) {
        for (i in example.processingChannel1) {
            example.processingChannel2.send(i + 1)
        }
        example.processingChannel2.close()
    }

    launch(pool) {
        for (i in example.processingChannel2) {
            example.outputChannel.send(i * 2)
        }
        example.outputChannel.close()
    }
}

class RunChecker925 : BaseRunCoroutineTests(false, 1000) {
    companion object {
        lateinit var pool: ExecutorCoroutineDispatcher
    }
    override fun block() {
        pool = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        pool.use {
            runBlocking(pool) { main() }
        }
    }
}