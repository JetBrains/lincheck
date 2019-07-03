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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.test.verifier.*
import kotlinx.coroutines.channels.Channel
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityVerifier
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

class BufferedChannelCustomTest : VerifierState() {
    val ch = Channel<Int>(3)

    override fun extractState(): Any {
        val elements = mutableListOf<Int>()
        while (!ch.isEmpty) elements.add(ch.poll()!!)
        val closed = ch.isClosedForSend || ch.isClosedForReceive
        return elements to closed
    }

    suspend fun send(value: Int) = ch.send(value)

    suspend fun receive(): Int = ch.receive()

    fun poll() = ch.poll()

    fun offer(value: Int) = ch.offer(value)

    private val r = BufferedChannelCustomTest::receive
    private val s = BufferedChannelCustomTest::send
    private val o = BufferedChannelCustomTest::offer
    private val p = BufferedChannelCustomTest::poll

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
                    operation(actor(s,6), NoResult)
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
}
