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

import kotlinx.coroutines.channels.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.test.verifier.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.*
import org.junit.*
import java.util.concurrent.*

class BufferedChannelCustomTest : VerifierState() {
    private val ch = Channel<Int>(3)

    override fun extractState(): Pair<Any, Boolean> {
        val state = mutableListOf<Any>()
        while (true) {
            val x = poll() ?: break // no elements
            state.add(x)
            if (x is String) break // closed/cancelled
        }
        return state to isClosedForReceive()
    }

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
                    operation(actor(s,1), VoidResult)
                    operation(actor(s,2), VoidResult)
                    operation(actor(s,3), VoidResult)
                    operation(actor(s,4), VoidResult)
                }
                thread {
                    operation(actor(r), ValueResult(1))
                    operation(actor(r), ValueResult(2))
                    operation(actor(s,5), VoidResult)
                    operation(actor(s,6), Suspended)
                    operation(actor(r), NoResult)
                }
            }
        }, true)
    }

    @Test
    fun mixedTest() {
        verify(BufferedChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(s,1), VoidResult)
                    operation(actor(s,2), VoidResult)
                    operation(actor(p), ValueResult(1))
                }
                thread {
                    operation(actor(s,3), VoidResult)
                    operation(actor(s,4), VoidResult)
                    operation(actor(p), ValueResult(2))
                }
            }
        }, true)
    }

    @Test
    fun testSuspendablePostPart() {
        verify(BufferedChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(p), ValueResult(null))
                }
                thread {
                    operation(actor(o, 1), ValueResult(true))
                    operation(actor(o, 2), ValueResult(true))
                }
            }
            post {
                operation(actor(r), ValueResult(1))
                operation(actor(r), ValueResult(2))
                operation(actor(r), Suspended)
            }
        }, true)
    }

    @Test
    fun testVoidResult() {
        verify(BufferedChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(c, 5), VoidResult)
                }
                thread {
                    operation(actor(r), ValueResult("Cancelled(5)"))
                    operation(actor(s, 5), ValueResult("Cancelled(5)"))
                }
            }
        }, true)
    }
}
