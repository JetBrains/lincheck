/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.transformation.channel

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.kotlinx.lincheck.LoggingLevel
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * Checks that [bug with not transformed ClassLoader-s](https://github.com/JetBrains/lincheck/issues/412) is resolved.
 */
@Param.Params(
    Param(name = "elem", gen = IntGen::class, conf = "1:3"),
    Param(name = "closeToken", gen = IntGen::class, conf = "1:3")
)
class BufferedChannelTest {

    val c = BufferedChannel<Int>(1)

    @Operation(blocking = true)
    suspend fun send(@Param(name = "elem") elem: Int): Any = try {
        c.send(elem)
    } catch (e: NumberedCancellationException) {
        e.testResult
    }

    @Operation(blocking = true, cancellableOnSuspension = false)
    suspend fun receive(): Any = try {
        c.receive()
    } catch (e: NumberedCancellationException) {
        e.testResult
    }

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .iterations(1)
        .logLevel(LoggingLevel.INFO)
        .sequentialSpecification(SequentialBufferedChannel::class.java)
        .addCustomScenario {
            parallel {
                thread {
                    actor(::send, 3)
                    actor(::send, 2)
                    actor(::send, 1)
                }
                thread {
                    actor(::receive)
                    actor(::send, 3)
                    actor(::receive)
                }
                thread {
                    actor(::receive)
                    actor(::send, 3)
                    actor(::send, 3)
                }
            }
        }
        .check(this::class)
}

private class NumberedCancellationException(number: Int) : CancellationException() {
    val testResult = "Closed($number)"
}

// Sequential specification for a buffered channel
@Suppress("unused")
class SequentialBufferedChannel {
    private val capacity: Long = 1
    private val senders = ArrayList<Pair<CancellableContinuation<Unit>, Int>>()
    private val buffer = ArrayList<Int>()
    private val receivers = ArrayList<CancellableContinuation<Int>>()

    suspend fun send(x: Int) {
        if (resumeFirstReceiver(x)) return
        if (tryBufferElem(x)) return
        suspendCancellableCoroutine { cont -> senders.add(cont to x) }
    }

    private fun tryBufferElem(elem: Int): Boolean {
        if (buffer.size < capacity) {
            buffer.add(elem)
            return true
        }
        return false
    }

    private fun resumeFirstReceiver(elem: Int): Boolean {
        while (receivers.isNotEmpty()) {
            val r = receivers.removeFirst()
            if (r.resume(elem)) return true
        }
        return false
    }

    suspend fun receive(): Int {
        return getBufferedElem()
            ?: resumeFirstSender()
            ?: suspendCancellableCoroutine { cont -> receivers.add(cont) }
    }

    private fun getBufferedElem(): Int? {
        val elem = buffer.removeFirstOrNull()?.also {
            // The element is retrieved from the buffer, resume one sender and save its element in the buffer
            resumeFirstSender()?.also { buffer.add(it) }
        }
        return elem
    }

    private fun resumeFirstSender(): Int? {
        while (senders.isNotEmpty()) {
            val (sender, elem) = senders.removeFirst()
            if (sender.resume(Unit)) return elem
        }
        return null
    }
}

@OptIn(InternalCoroutinesApi::class)
private fun <T> CancellableContinuation<T>.resume(res: T): Boolean {
    val token = tryResume(res) ?: return false
    completeResume(token)
    return true
}