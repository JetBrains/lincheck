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
import org.jetbrains.kotlinx.lincheck_test.verifier.*
import org.jetbrains.lincheck.datastructures.actor
import org.jetbrains.lincheck.datastructures.verifier.LinearizabilityVerifier
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
                    operation(actor(sendFun, 1, cancelOnSuspension = true), Cancelled)
                }
                thread {
                    operation(actor(pollFun), ValueResult(null))
                }
            }
        }, true)
    }

    @Test
    fun testCancellation_02() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun, cancelOnSuspension = true), Cancelled)
                }
                thread {
                    operation(actor(offerFun, 1), ValueResult(true))
                }
            }
        }, false)
    }

    @Test
    fun testCancellation_03() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 1, cancelOnSuspension = true), VoidResult)
                }
                thread {
                    operation(actor(receiveFun, cancelOnSuspension = true), Cancelled)
                }
            }
        }, false)
    }

    @Test
    fun testCancellation_04() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 1, cancelOnSuspension = true), Cancelled)
                }
                thread {
                    operation(actor(receiveFun, cancelOnSuspension = true), Cancelled)
                }
            }
        }, true)
    }

    @Test
    fun testCancellation_05() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 1), Suspended)
                }
                thread {
                    operation(actor(receiveFun, cancelOnSuspension = true), Cancelled)
                }
            }
        }, true)
    }

    @Test
    fun testFirst() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueResult(101))
                }
                thread {
                    operation(actor(receiveFun), Suspended)
                }
                thread {
                    operation(actor(sendFun, 1), VoidResult)
                }
            }
        }, true)
    }

    @Test
    fun test0() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueResult(103))
                    operation(actor(sendFun, 1), Suspended)
                }
                thread {
                    operation(actor(sendFun, 3), VoidResult)
                    operation(actor(sendFun, 2), Suspended)
                }
                thread {
                    operation(actor(receiveFun), ValueResult(103))
                    operation(actor(sendFun, 3), VoidResult)
                }
            }
        }, true)
    }

    @Test
    fun testNoResult() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), Suspended)
                }
                thread {
                    operation(actor(receiveFun), ValueResult(101))
                    operation(actor(receiveFun), ValueResult(102))
                }
                thread {
                    operation(actor(sendFun, 1), VoidResult)
                    operation(actor(sendFun, 2), VoidResult)
                }
            }
        }, true)
    }

    @Test
    fun test1() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 2), VoidResult)
                    operation(actor(receiveFun), ValueResult(105))
                }
                thread {
                    operation(actor(rOrNull), ValueResult(102))
                    operation(actor(sendFun, 5), VoidResult)
                }
            }
        }, true)
    }

    @Test
    fun test2() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), Suspended)
                }
                thread {
                    operation(actor(receiveFun), ValueResult(101))
                }
                thread {
                    operation(actor(sendFun, 1), VoidResult)
                }
            }
        }, true)
    }

    @Test
    fun test3() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueResult(103))
                }
                thread {
                    operation(actor(receiveFun), ValueResult(101))
                }
                thread {
                    operation(actor(receiveFun), ValueResult(104))
                }
                thread {
                    operation(actor(receiveFun), ValueResult(102))
                }
                thread {
                    operation(actor(sendFun, 1), VoidResult)
                    operation(actor(sendFun, 3), VoidResult)
                    operation(actor(sendFun, 2), VoidResult)
                    operation(actor(sendFun, 4), VoidResult)
                }
            }
        }, true)
    }

    @Test
    fun test4() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueResult(102))
                }
                thread {
                    operation(actor(receiveFun), ValueResult(101))
                }
                thread {
                    operation(actor(sendFun, 1), VoidResult)
                    operation(actor(sendFun, 2), VoidResult)
                }
            }
        }, true)
    }

    @Test
    fun test5() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueResult(102))
                    operation(actor(receiveFun), Suspended)
                }
                thread {
                    operation(actor(receiveFun), Suspended)
                    operation(actor(receiveFun), NoResult)
                }
                thread {
                    operation(actor(receiveFun), ValueResult(101))
                    operation(actor(receiveFun), Suspended)
                }
                thread {
                    operation(actor(sendFun, 1), VoidResult)
                    operation(actor(sendFun, 2), VoidResult)
                }
            }
        }, true)
    }

    @Test
    fun test6() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueResult(102))
                    operation(actor(receiveFun), Suspended)
                }
                thread {
                    operation(actor(receiveFun), Suspended)
                    operation(actor(receiveFun), NoResult)
                }
                thread {
                    operation(actor(receiveFun), ValueResult(101))
                    operation(actor(receiveFun), Suspended)
                }
                thread {
                    operation(actor(sendFun, 1), VoidResult)
                    operation(actor(sendFun, 2), VoidResult)
                }
            }
        }, true)
    }

    @Test
    fun test7() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueResult(104))
                    operation(actor(receiveFun), Suspended)
                    operation(actor(receiveFun), NoResult)
                }
                thread {
                    operation(actor(receiveFun), ValueResult(101))
                    operation(actor(receiveFun), Suspended)
                    operation(actor(sendFun, 5), NoResult)
                }
                thread {
                    operation(actor(sendFun, 1), VoidResult)
                    operation(actor(sendFun, 4), VoidResult)
                    operation(actor(receiveFun), Suspended)
                }
            }
        }, true)
    }

    @Test
    fun test8() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 4), VoidResult)
                    operation(actor(receiveFun), Suspended)
                    operation(actor(receiveFun), NoResult)
                }
                thread {
                    operation(actor(receiveFun), ValueResult(104))
                    operation(actor(receiveFun), Suspended)
                    operation(actor(sendFun, 2), NoResult)
                }
            }
        }, true)
    }

    @Test
    fun test9() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 4), VoidResult)
                    operation(actor(receiveFun), Suspended)
                    operation(actor(receiveFun), NoResult)
                }
                thread {
                    operation(actor(receiveFun), ValueResult(104))
                    operation(actor(receiveFun), Suspended)
                    operation(actor(sendFun, 2), NoResult)
                }
            }
        }, true)
    }

    @Test
    fun test10() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 1), Suspended)
                }
                thread {
                    operation(actor(sendFun, 2), VoidResult)
                }
                thread {
                    operation(actor(sendFun, 3), Suspended)
                }
                thread {
                    operation(actor(sendFun, 4), Suspended)
                }
                thread {
                    operation(actor(receiveFun), ValueResult(102))
                }
            }
        }, true)
    }

    @Test
    fun test11() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 1), Suspended)
                }
                thread {
                    operation(actor(sendFun, 2), VoidResult)
                }
                thread {
                    operation(actor(sendFun, 3), Suspended)
                }
                thread {
                    operation(actor(sendFun, 4), Suspended)
                }
                thread {
                    operation(actor(receiveFun), ValueResult(102))
                }
            }
        }, true)
    }

    @Test
    fun test12() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueResult(105))
                    operation(actor(receiveFun), ValueResult(103))
                    operation(actor(receiveFun), Suspended)
                }
                thread {
                    operation(actor(sendFun, 5), VoidResult)
                    operation(actor(sendFun, 3), VoidResult)
                    operation(actor(receiveFun), Suspended)
                }
            }
        }, true)
    }

    @Test
    fun test13() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), Suspended)
                    operation(actor(receiveFun), NoResult)
                    operation(actor(receiveFun), NoResult)
                }
                thread {
                    operation(actor(receiveFun), ValueResult(101))
                    operation(actor(receiveFun), ValueResult(104))
                    operation(actor(sendFun, 5), VoidResult)
                }
                thread {
                    operation(actor(sendFun, 1), VoidResult)
                    operation(actor(sendFun, 4), VoidResult)
                    operation(actor(receiveFun), ValueResult(105))
                }
            }
        }, true)
    }

    @Test
    fun test14() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 1), Suspended)
                    operation(actor(receiveFun), NoResult)
                }
                thread {
                    operation(actor(sendFun, 2), Suspended)
                    operation(actor(receiveFun), NoResult)
                }
                thread {
                    operation(actor(sendFun, 3), Suspended)
                    operation(actor(receiveFun), NoResult)
                }
                thread {
                    operation(actor(sendFun, 4), Suspended)
                    operation(actor(receiveFun), NoResult)
                }
            }
        }, true)
    }


    @Test
    fun testStates() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueResult(101))
                    operation(actor(receiveFun), ValueResult(103))
                }
                thread {
                    operation(actor(receiveFun), ValueResult(102))
                    operation(actor(receiveFun), ValueResult(104))
                }
                thread {
                    operation(actor(sendFun, 1), VoidResult)
                    operation(actor(sendFun, 2), VoidResult)
                    operation(actor(sendFun, 3), VoidResult)
                    operation(actor(sendFun, 4), VoidResult)
                }
            }
        }, true)
    }

    @Test
    fun test15() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(receiveFun), ValueResult(102))
                    operation(actor(sendFun, 5), Suspended)
                    operation(actor(sendFun, 4), NoResult)
                    operation(actor(receiveFun), NoResult)
                }
                thread {
                    operation(actor(sendFun, 2), VoidResult)
                    operation(actor(sendFun, 5), VoidResult)
                    operation(actor(receiveFun), ValueResult(103))
                    operation(actor(sendFun, 5), Suspended)
                }
                thread {
                    operation(actor(receiveFun), ValueResult(105))
                    operation(actor(sendFun, 3), VoidResult)
                    operation(actor(sendFun, 3), Suspended)
                    operation(actor(receiveFun), NoResult)
                }
                thread {
                    operation(actor(sendFun, 2), Suspended)
                    operation(actor(sendFun, 3), NoResult)
                    operation(actor(sendFun, 1), NoResult)
                    operation(actor(receiveFun), NoResult)
                }
            }
        }, true)
    }

    @Test
    fun test16() {
        verify(RendezvousChannelCustomTest::class.java, LinearizabilityVerifier::class.java, {
            parallel {
                thread {
                    operation(actor(sendFun, 4), VoidResult)
                    operation(actor(receiveFun), ValueResult(102))
                    operation(actor(sendFun, 4), VoidResult)
                    operation(actor(sendFun, 4), VoidResult)
                }
                thread {
                    operation(actor(sendFun, 2), VoidResult)
                    operation(actor(receiveFun), ValueResult(103))
                    operation(actor(receiveFun), ValueResult(104))
                    operation(actor(sendFun, 1), VoidResult)
                }
                thread {
                    operation(actor(receiveFun), ValueResult(104))
                    operation(actor(sendFun, 2), VoidResult)
                    operation(actor(receiveFun), ValueResult(101))
                    operation(actor(sendFun, 2), Suspended)
                }
                thread {
                    operation(actor(sendFun, 3), VoidResult)
                    operation(actor(receiveFun), ValueResult(102))
                    operation(actor(receiveFun), ValueResult(104))
                    operation(actor(sendFun, 4), Suspended)
                }
            }
        }, true)
    }
}


