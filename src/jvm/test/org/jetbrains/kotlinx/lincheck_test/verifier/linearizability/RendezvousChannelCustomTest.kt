/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.verifier.linearizability

import kotlinx.coroutines.channels.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode
import org.jetbrains.kotlinx.lincheck.transformation.withLincheckJavaAgent
import org.jetbrains.kotlinx.lincheck_test.verifier.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.jetbrains.kotlinx.lincheck.verifier.linearizability.*
import org.junit.*

class RendezvousChannelCustomTest {
    private val ch = Channel<Int>()

    suspend fun send(value: Int) {
        ch.send(value)
        value + 2
    }

    fun offer(value: Int) = ch.trySend(value).isSuccess
    fun poll() = ch.tryReceive().getOrNull()

    suspend fun receive(): Int = ch.receive() + 100
    suspend fun receiveOrNull(): Int? = ch.receiveCatching().getOrNull()?.plus(100)

    private val receiveFun = RendezvousChannelCustomTest::receive
    private val rOrNull = RendezvousChannelCustomTest::receiveOrNull
    private val sendFun = RendezvousChannelCustomTest::send
    private val offerFun = RendezvousChannelCustomTest::offer
    private val pollFun = RendezvousChannelCustomTest::poll

    @Test
    fun testCancellation_01() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 1, cancelOnSuspension = true), CancelledActorResult)
                }
                thread {
                    operation(actor(pollFun), ValueActorResult(null))
                }
            }
        }, true)
    }

    @Test
    fun testCancellation_02() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun, cancelOnSuspension = true), CancelledActorResult)
                }
                thread {
                    operation(actor(offerFun, 1), ValueActorResult(true))
                }
            }
        }, false)
    }

    @Test
    fun testCancellation_03() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 1, cancelOnSuspension = true), VoidActorResult)
                }
                thread {
                    operation(actor(receiveFun, cancelOnSuspension = true), CancelledActorResult)
                }
            }
        }, false)
    }

    @Test
    fun testCancellation_04() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 1, cancelOnSuspension = true), CancelledActorResult)
                }
                thread {
                    operation(actor(receiveFun, cancelOnSuspension = true), CancelledActorResult)
                }
            }
        }, true)
    }

    @Test
    fun testCancellation_05() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 1), SuspendedActorResult)
                }
                thread {
                    operation(actor(receiveFun, cancelOnSuspension = true), CancelledActorResult)
                }
            }
        }, true)
    }

    @Test
    fun testFirst() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueActorResult(101))
                }
                thread {
                    operation(actor(receiveFun), SuspendedActorResult)
                }
                thread {
                    operation(actor(sendFun, 1), VoidActorResult)
                }
            }
        }, true)
    }

    @Test
    fun test0() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueActorResult(103))
                    operation(actor(sendFun, 1), SuspendedActorResult)
                }
                thread {
                    operation(actor(sendFun, 3), VoidActorResult)
                    operation(actor(sendFun, 2), SuspendedActorResult)
                }
                thread {
                    operation(actor(receiveFun), ValueActorResult(103))
                    operation(actor(sendFun, 3), VoidActorResult)
                }
            }
        }, true)
    }

    @Test
    fun testNoResult() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), SuspendedActorResult)
                }
                thread {
                    operation(actor(receiveFun), ValueActorResult(101))
                    operation(actor(receiveFun), ValueActorResult(102))
                }
                thread {
                    operation(actor(sendFun, 1), VoidActorResult)
                    operation(actor(sendFun, 2), VoidActorResult)
                }
            }
        }, true)
    }

    @Test
    fun test1() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 2), VoidActorResult)
                    operation(actor(receiveFun), ValueActorResult(105))
                }
                thread {
                    operation(actor(rOrNull), ValueActorResult(102))
                    operation(actor(sendFun, 5), VoidActorResult)
                }
            }
        }, true)
    }

    @Test
    fun test2() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), SuspendedActorResult)
                }
                thread {
                    operation(actor(receiveFun), ValueActorResult(101))
                }
                thread {
                    operation(actor(sendFun, 1), VoidActorResult)
                }
            }
        }, true)
    }

    @Test
    fun test3() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueActorResult(103))
                }
                thread {
                    operation(actor(receiveFun), ValueActorResult(101))
                }
                thread {
                    operation(actor(receiveFun), ValueActorResult(104))
                }
                thread {
                    operation(actor(receiveFun), ValueActorResult(102))
                }
                thread {
                    operation(actor(sendFun, 1), VoidActorResult)
                    operation(actor(sendFun, 3), VoidActorResult)
                    operation(actor(sendFun, 2), VoidActorResult)
                    operation(actor(sendFun, 4), VoidActorResult)
                }
            }
        }, true)
    }

    @Test
    fun test4() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueActorResult(102))
                }
                thread {
                    operation(actor(receiveFun), ValueActorResult(101))
                }
                thread {
                    operation(actor(sendFun, 1), VoidActorResult)
                    operation(actor(sendFun, 2), VoidActorResult)
                }
            }
        }, true)
    }

    @Test
    fun test5() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueActorResult(102))
                    operation(actor(receiveFun), SuspendedActorResult)
                }
                thread {
                    operation(actor(receiveFun), SuspendedActorResult)
                    operation(actor(receiveFun), NoActorResult)
                }
                thread {
                    operation(actor(receiveFun), ValueActorResult(101))
                    operation(actor(receiveFun), SuspendedActorResult)
                }
                thread {
                    operation(actor(sendFun, 1), VoidActorResult)
                    operation(actor(sendFun, 2), VoidActorResult)
                }
            }
        }, true)
    }

    @Test
    fun test6() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueActorResult(102))
                    operation(actor(receiveFun), SuspendedActorResult)
                }
                thread {
                    operation(actor(receiveFun), SuspendedActorResult)
                    operation(actor(receiveFun), NoActorResult)
                }
                thread {
                    operation(actor(receiveFun), ValueActorResult(101))
                    operation(actor(receiveFun), SuspendedActorResult)
                }
                thread {
                    operation(actor(sendFun, 1), VoidActorResult)
                    operation(actor(sendFun, 2), VoidActorResult)
                }
            }
        }, true)
    }

    @Test
    fun test7() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueActorResult(104))
                    operation(actor(receiveFun), SuspendedActorResult)
                    operation(actor(receiveFun), NoActorResult)
                }
                thread {
                    operation(actor(receiveFun), ValueActorResult(101))
                    operation(actor(receiveFun), SuspendedActorResult)
                    operation(actor(sendFun, 5), NoActorResult)
                }
                thread {
                    operation(actor(sendFun, 1), VoidActorResult)
                    operation(actor(sendFun, 4), VoidActorResult)
                    operation(actor(receiveFun), SuspendedActorResult)
                }
            }
        }, true)
    }

    @Test
    fun test8() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 4), VoidActorResult)
                    operation(actor(receiveFun), SuspendedActorResult)
                    operation(actor(receiveFun), NoActorResult)
                }
                thread {
                    operation(actor(receiveFun), ValueActorResult(104))
                    operation(actor(receiveFun), SuspendedActorResult)
                    operation(actor(sendFun, 2), NoActorResult)
                }
            }
        }, true)
    }

    @Test
    fun test9() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 4), VoidActorResult)
                    operation(actor(receiveFun), SuspendedActorResult)
                    operation(actor(receiveFun), NoActorResult)
                }
                thread {
                    operation(actor(receiveFun), ValueActorResult(104))
                    operation(actor(receiveFun), SuspendedActorResult)
                    operation(actor(sendFun, 2), NoActorResult)
                }
            }
        }, true)
    }

    @Test
    fun test10() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 1), SuspendedActorResult)
                }
                thread {
                    operation(actor(sendFun, 2), VoidActorResult)
                }
                thread {
                    operation(actor(sendFun, 3), SuspendedActorResult)
                }
                thread {
                    operation(actor(sendFun, 4), SuspendedActorResult)
                }
                thread {
                    operation(actor(receiveFun), ValueActorResult(102))
                }
            }
        }, true)
    }

    @Test
    fun test11() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 1), SuspendedActorResult)
                }
                thread {
                    operation(actor(sendFun, 2), VoidActorResult)
                }
                thread {
                    operation(actor(sendFun, 3), SuspendedActorResult)
                }
                thread {
                    operation(actor(sendFun, 4), SuspendedActorResult)
                }
                thread {
                    operation(actor(receiveFun), ValueActorResult(102))
                }
            }
        }, true)
    }

    @Test
    fun test12() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueActorResult(105))
                    operation(actor(receiveFun), ValueActorResult(103))
                    operation(actor(receiveFun), SuspendedActorResult)
                }
                thread {
                    operation(actor(sendFun, 5), VoidActorResult)
                    operation(actor(sendFun, 3), VoidActorResult)
                    operation(actor(receiveFun), SuspendedActorResult)
                }
            }
        }, true)
    }

    @Test
    fun test13() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), SuspendedActorResult)
                    operation(actor(receiveFun), NoActorResult)
                    operation(actor(receiveFun), NoActorResult)
                }
                thread {
                    operation(actor(receiveFun), ValueActorResult(101))
                    operation(actor(receiveFun), ValueActorResult(104))
                    operation(actor(sendFun, 5), VoidActorResult)
                }
                thread {
                    operation(actor(sendFun, 1), VoidActorResult)
                    operation(actor(sendFun, 4), VoidActorResult)
                    operation(actor(receiveFun), ValueActorResult(105))
                }
            }
        }, true)
    }

    @Test
    fun test14() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 1), SuspendedActorResult)
                    operation(actor(receiveFun), NoActorResult)
                }
                thread {
                    operation(actor(sendFun, 2), SuspendedActorResult)
                    operation(actor(receiveFun), NoActorResult)
                }
                thread {
                    operation(actor(sendFun, 3), SuspendedActorResult)
                    operation(actor(receiveFun), NoActorResult)
                }
                thread {
                    operation(actor(sendFun, 4), SuspendedActorResult)
                    operation(actor(receiveFun), NoActorResult)
                }
            }
        }, true)
    }


    @Test
    fun testStates() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueActorResult(101))
                    operation(actor(receiveFun), ValueActorResult(103))
                }
                thread {
                    operation(actor(receiveFun), ValueActorResult(102))
                    operation(actor(receiveFun), ValueActorResult(104))
                }
                thread {
                    operation(actor(sendFun, 1), VoidActorResult)
                    operation(actor(sendFun, 2), VoidActorResult)
                    operation(actor(sendFun, 3), VoidActorResult)
                    operation(actor(sendFun, 4), VoidActorResult)
                }
            }
        }, true)
    }

    @Test
    fun test15() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueActorResult(102))
                    operation(actor(sendFun, 5), SuspendedActorResult)
                    operation(actor(sendFun, 4), NoActorResult)
                    operation(actor(receiveFun), NoActorResult)
                }
                thread {
                    operation(actor(sendFun, 2), VoidActorResult)
                    operation(actor(sendFun, 5), VoidActorResult)
                    operation(actor(receiveFun), ValueActorResult(103))
                    operation(actor(sendFun, 5), SuspendedActorResult)
                }
                thread {
                    operation(actor(receiveFun), ValueActorResult(105))
                    operation(actor(sendFun, 3), VoidActorResult)
                    operation(actor(sendFun, 3), SuspendedActorResult)
                    operation(actor(receiveFun), NoActorResult)
                }
                thread {
                    operation(actor(sendFun, 2), SuspendedActorResult)
                    operation(actor(sendFun, 3), NoActorResult)
                    operation(actor(sendFun, 1), NoActorResult)
                    operation(actor(receiveFun), NoActorResult)
                }
            }
        }, true)
    }

    @Test
    fun test16() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 4), VoidActorResult)
                    operation(actor(receiveFun), ValueActorResult(102))
                    operation(actor(sendFun, 4), VoidActorResult)
                    operation(actor(sendFun, 4), VoidActorResult)
                }
                thread {
                    operation(actor(sendFun, 2), VoidActorResult)
                    operation(actor(receiveFun), ValueActorResult(103))
                    operation(actor(receiveFun), ValueActorResult(104))
                    operation(actor(sendFun, 1), VoidActorResult)
                }
                thread {
                    operation(actor(receiveFun), ValueActorResult(104))
                    operation(actor(sendFun, 2), VoidActorResult)
                    operation(actor(receiveFun), ValueActorResult(101))
                    operation(actor(sendFun, 2), SuspendedActorResult)
                }
                thread {
                    operation(actor(sendFun, 3), VoidActorResult)
                    operation(actor(receiveFun), ValueActorResult(102))
                    operation(actor(receiveFun), ValueActorResult(104))
                    operation(actor(sendFun, 4), SuspendedActorResult)
                }
            }
        }, true)
    }
}


