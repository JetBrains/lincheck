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

import org.jetbrains.kotlinx.lincheck.test.verifier.actor
import org.jetbrains.kotlinx.lincheck.test.verifier.verify
import kotlinx.coroutines.channels.Channel
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.LinearizabilityVerifier
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test

class RendezvousChannelCustomTest : VerifierState() {
    val ch = Channel<Int>()

    override fun extractState() = ch.isClosedForSend

    suspend fun send(value: Int) {
        ch.send(value)
        value + 2
    }

    suspend fun receive(): Int = ch.receive() + 100

    suspend fun receiveOrNull(): Int? = ch.receiveOrNull()?.plus(100)

    private val r = RendezvousChannelCustomTest::receive
    private val rOrNull = RendezvousChannelCustomTest::receiveOrNull
    private val s = RendezvousChannelCustomTest::send

    @Test
    fun testFirst() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(r), ValueResult(101))
                }
                thread {
                    operation(actor(r), Suspended)
                }
                thread {
                    operation(actor(s, 1), SuspendedVoidResult)
                }
            }
        }, expected = true)
    }

    @Test
    fun test0() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(r), ValueResult(103))
                    operation(actor(s,1), Suspended)
                }
                thread {
                    operation(actor(s,3), VoidResult)
                    operation(actor(s,2), Suspended)
                }
                thread {
                    operation(actor(r),ValueResult(103, wasSuspended = true))
                    operation(actor(s,3), SuspendedVoidResult)
                }
            }
        }, true)
    }

    @Test
    fun testNoResult() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(r), Suspended)
                }
                thread {
                    operation(actor(r),ValueResult(101, wasSuspended = true))
                    operation(actor(r),ValueResult(102))
                }
                thread {
                    operation(actor(s,1), VoidResult)
                    operation(actor(s,2), SuspendedVoidResult)
                }
            }
        }, true)
    }

    @Test
    fun test1() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(s,2), VoidResult)
                    operation(actor(r),ValueResult(105, wasSuspended = true))
                }
                thread {
                    operation(actor(rOrNull), ValueResult(102, wasSuspended = true))
                    operation(actor(s,5), VoidResult)
                }
            }
        }, true)
    }

    @Test
    fun test2() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(r),Suspended)
                }
                thread {
                    operation(actor(r),ValueResult(101, wasSuspended = false))
                }
                thread {
                    operation(actor(s,1), SuspendedVoidResult)
                }
            }
        }, true)
    }

    @Test
    fun test3() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(r),ValueResult(103, wasSuspended = true))
                }
                thread {
                    operation(actor(r),ValueResult(101, wasSuspended = true))
                }
                thread {
                    operation(actor(r),ValueResult(104, wasSuspended = true))
                }
                thread {
                    operation(actor(r),ValueResult(102))
                }
                thread {
                    operation(actor(s,1), VoidResult)
                    operation(actor(s,3), VoidResult)
                    operation(actor(s,2), SuspendedVoidResult)
                    operation(actor(s,4), VoidResult)
                }
            }
        }, true)
    }

    @Test
    fun test4() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(r),ValueResult(102, wasSuspended = true))
                }
                thread {
                    operation(actor(r),ValueResult(101))
                }
                thread {
                    operation(actor(s,1), SuspendedVoidResult)
                    operation(actor(s,2), VoidResult)
                }
            }
        }, true)
    }

    @Test
    fun test5() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(r),ValueResult(102, wasSuspended = true))
                    operation(actor(r),Suspended)
                }
                thread {
                    operation(actor(r),Suspended)
                    operation(actor(r),NoResult)
                }
                thread {
                    operation(actor(r),ValueResult(101, wasSuspended = true))
                    operation(actor(r),Suspended)
                }
                thread {
                    operation(actor(s,1), VoidResult)
                    operation(actor(s,2), VoidResult)
                }
            }
        }, true)
    }

    @Test
    fun test6() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(r),ValueResult(102, wasSuspended = true))
                    operation(actor(r),Suspended)
                }
                thread {
                    operation(actor(r),Suspended)
                    operation(actor(r),NoResult)
                }
                thread {
                    operation(actor(r),ValueResult(101))
                    operation(actor(r),Suspended)
                }
                thread {
                    operation(actor(s,1), SuspendedVoidResult)
                    operation(actor(s,2), VoidResult)
                }
            }
        }, true)
    }

    @Test
    fun test7() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(r),ValueResult(104, wasSuspended = true))
                    operation(actor(r),Suspended)
                    operation(actor(r),NoResult)
                }
                thread {
                    operation(actor(r),ValueResult(101))
                    operation(actor(r),Suspended)
                    operation(actor(s,5), NoResult)
                }
                thread {
                    operation(actor(s,1), SuspendedVoidResult)
                    operation(actor(s,4), VoidResult)
                    operation(actor(r),Suspended)
                }
            }
        }, true)
    }

    @Test
    fun test8() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(s,4), SuspendedVoidResult)
                    operation(actor(r), Suspended)
                    operation(actor(r), NoResult)
                }
                thread {
                    operation(actor(r),ValueResult(104))
                    operation(actor(r), Suspended)
                    operation(actor(s,2), NoResult)
                }
            }
        }, true)
    }

    @Test
    fun test9() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(s,4), VoidResult)
                    operation(actor(r),Suspended)
                    operation(actor(r),NoResult)
                }
                thread {
                    operation(actor(r),ValueResult(104, wasSuspended = true))
                    operation(actor(r),Suspended)
                    operation(actor(s,2), NoResult)
                }
            }
        }, true)
    }

    @Test
    fun test10() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(s,1), Suspended)
                }
                thread {
                    operation(actor(s,2), VoidResult)
                }
                thread {
                    operation(actor(s,3), Suspended)
                }
                thread {
                    operation(actor(s,4), Suspended)
                }
                thread {
                    operation(actor(r),ValueResult(102, wasSuspended = true))
                }
            }
        }, true)
    }

    @Test
    fun test11() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(s,1), Suspended)
                }
                thread {
                    operation(actor(s,2), SuspendedVoidResult)
                }
                thread {
                    operation(actor(s,3), Suspended)
                }
                thread {
                    operation(actor(s,4), Suspended)
                }
                thread {
                    operation(actor(r),ValueResult(102))
                }
            }
        }, true)
    }

    @Test
    fun test12() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(r),ValueResult(105, wasSuspended = true))
                    operation(actor(r),ValueResult(103))
                    operation(actor(r),Suspended)
                }
                thread {
                    operation(actor(s,5), VoidResult)
                    operation(actor(s,3), SuspendedVoidResult)
                    operation(actor(r),Suspended)
                }
            }
        }, true)
    }

    @Test
    fun test13() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(r),Suspended)
                    operation(actor(r),NoResult)
                    operation(actor(r),NoResult)
                }
                thread {
                    operation(actor(r),ValueResult(101, wasSuspended = true))
                    operation(actor(r),ValueResult(104))
                    operation(actor(s,5), VoidResult)
                }
                thread {
                    operation(actor(s,1), VoidResult)
                    operation(actor(s,4), SuspendedVoidResult)
                    operation(actor(r),ValueResult(105, wasSuspended = true))
                }
            }
        }, true)
    }

    @Test
    fun test14() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(s,1), Suspended)
                    operation(actor(r),NoResult)
                }
                thread {
                    operation(actor(s,2), Suspended)
                    operation(actor(r),NoResult)
                }
                thread {
                    operation(actor(s,3), Suspended)
                    operation(actor(r),NoResult)
                }
                thread {
                    operation(actor(s,4), Suspended)
                    operation(actor(r),NoResult)
                }
            }
        }, true)
    }


    @Test
    fun testStates() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(r),ValueResult(101, wasSuspended = true))
                    operation(actor(r),ValueResult(103, wasSuspended = true))
                }
                thread {
                    operation(actor(r),ValueResult(102, wasSuspended = true))
                    operation(actor(r),ValueResult(104, wasSuspended = true))
                }
                thread {
                    operation(actor(s,1), VoidResult)
                    operation(actor(s,2), VoidResult)
                    operation(actor(s,3), VoidResult)
                    operation(actor(s,4), VoidResult)
                }
            }
        }, true)
    }

    @Test
    fun test15() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(r),ValueResult(102, wasSuspended = true))
                    operation(actor(s,5), Suspended)
                    operation(actor(s,4), NoResult)
                    operation(actor(r),NoResult)
                }
                thread {
                    operation(actor(s,2), VoidResult)
                    operation(actor(s,5), VoidResult)
                    operation(actor(r),ValueResult(103, wasSuspended = true))
                    operation(actor(s,5), Suspended)
                }
                thread {
                    operation(actor(r),ValueResult(105, wasSuspended = true))
                    operation(actor(s,3), VoidResult)
                    operation(actor(s,3), Suspended)
                    operation(actor(r),NoResult)
                }
                thread {
                    operation(actor(s,2), Suspended)
                    operation(actor(s,3), NoResult)
                    operation(actor(s,1), NoResult)
                    operation(actor(r),NoResult)
                }
            }
        }, true)
    }

    @Test
    fun test16() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(s,4), VoidResult)
                    operation(actor(r),ValueResult(102, wasSuspended = true))
                    operation(actor(s,4), VoidResult)
                    operation(actor(s,4), SuspendedVoidResult)
                }
                thread {
                    operation(actor(s,2), VoidResult)
                    operation(actor(r),ValueResult(103))
                    operation(actor(r),ValueResult(104, wasSuspended = true))
                    operation(actor(s,1), VoidResult)
                }
                thread {
                    operation(actor(r),ValueResult(104, wasSuspended = true))
                    operation(actor(s,2), VoidResult)
                    operation(actor(r),ValueResult(101, wasSuspended = true))
                    operation(actor(s,2), Suspended)
                }
                thread {
                    operation(actor(s,3), SuspendedVoidResult)
                    operation(actor(r),ValueResult(102, wasSuspended = true))
                    operation(actor(r),ValueResult(104))
                    operation(actor(s,4), Suspended)
                }
            }
        }, true)
    }
}


