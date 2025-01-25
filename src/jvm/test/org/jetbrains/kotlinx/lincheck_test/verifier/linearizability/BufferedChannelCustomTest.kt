/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
@file:OptIn(DelicateCoroutinesApi::class)

package org.jetbrains.kotlinx.lincheck_test.verifier.linearizability

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck_test.verifier.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.*
import org.junit.*
import java.util.concurrent.*
import java.util.concurrent.CancellationException

class BufferedChannelCustomTest {
    private val ch = Channel<Int>(3)

    suspend fun send(value: Int) = try {
        ch.send(value)
    } catch (e: NumberedCancellationException) {
        e.testResult
    }

    suspend fun receive() = try {
        ch.receive()
    } catch (e: NumberedCancellationException) {
        e.testResult
    }

    fun poll() = try {
        val result = ch.tryReceive()
        if (result.isSuccess) result.getOrThrow()
        else result.exceptionOrNull().let { if (it == null) null else throw it }
    } catch (e: NumberedCancellationException) {
        e.testResult
    }

    fun offer(value: Int) = try {
        val results = ch.trySend(value)
        if (results.isSuccess) true
        else results.exceptionOrNull().let { if (it == null) false else throw it }
    } catch (e: NumberedCancellationException) {
        e.testResult
    }

    fun cancel(token: Int) = ch.cancel(NumberedCancellationException(token))

    fun isClosedForReceive() = ch.isClosedForReceive

    fun isClosedForSend() = ch.isClosedForSend

    private class NumberedCancellationException(number: Int): CancellationException() {
        val testResult = "Cancelled($number)"
    }

    private val r = BufferedChannelCustomTest::receive
    private val s = BufferedChannelCustomTest::send
    private val o = BufferedChannelCustomTest::offer
    private val p = BufferedChannelCustomTest::poll
    private val c = BufferedChannelCustomTest::cancel

    @Test
    fun test1() {
        verify(BufferedChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(s,1), VoidActorResult)
                    operation(actor(s,2), VoidActorResult)
                    operation(actor(s,3), VoidActorResult)
                    operation(actor(s,4), VoidActorResult)
                }
                thread {
                    operation(actor(r), ValueActorResult(1))
                    operation(actor(r), ValueActorResult(2))
                    operation(actor(s,5), VoidActorResult)
                    operation(actor(s,6), SuspendedActorResult)
                    operation(actor(r), NoActorResult)
                }
            }
        }, true)
    }

    @Test
    fun mixedTest() {
        verify(BufferedChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(s,1), VoidActorResult)
                    operation(actor(s,2), VoidActorResult)
                    operation(actor(p), ValueActorResult(1))
                }
                thread {
                    operation(actor(s,3), VoidActorResult)
                    operation(actor(s,4), VoidActorResult)
                    operation(actor(p), ValueActorResult(2))
                }
            }
        }, true)
    }

    @Test
    fun testSuspendablePostPart() {
        verify(BufferedChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(p), ValueActorResult(null))
                }
                thread {
                    operation(actor(o, 1), ValueActorResult(true))
                    operation(actor(o, 2), ValueActorResult(true))
                }
            }
            post {
                operation(actor(r), ValueActorResult(1))
                operation(actor(r), ValueActorResult(2))
                operation(actor(r), SuspendedActorResult)
            }
        }, true)
    }

    @Test
    fun testVoidResult() {
        verify(BufferedChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(c, 5), VoidActorResult)
                }
                thread {
                    operation(actor(r), ValueActorResult("Cancelled(5)"))
                    operation(actor(s, 5), ValueActorResult("Cancelled(5)"))
                }
            }
        }, true)
    }
}
