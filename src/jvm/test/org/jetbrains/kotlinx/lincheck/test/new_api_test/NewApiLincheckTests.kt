/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.test.new_api_test

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import org.jctools.queues.atomic.MpscLinkedAtomicQueue
import org.jetbrains.kotlinx.lincheck.new_api.ExperimentalLincheckApi
import org.jetbrains.kotlinx.lincheck.new_api.runLincheckTest
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

@ExperimentalLincheckApi
class NewApiLincheckTests {
    @Test
    fun concurrentHashMapTest() = runLincheckTest({ ConcurrentHashMap<Int, String>() }) {
        it.operation(::get)
        it.operation(::put)
        it.operation1(::remove)
        it.sequentialSpecification = { HashMap<Int, String>() }
        it.testingTimeInSeconds = 5
    }

    @Test
    fun multiProducerSingleConsumerQueueTest() = runLincheckTest({ MpscLinkedAtomicQueue<Int>() }) {
        with(it) {
            val offer = operation(::offer)
            offer.blocking = true
            operation(::offer).let {
                it.blocking = true
            }
            operation(::offer).run {
                blocking = true
            }
            operation(::offer).also {
                it.blocking = true
            }
            operation(::offer) configuredWith {
                blocking = true
            }
            nonParallel {
                operation(::peek)
                operation(::poll)
            }
            operation(::size)
            testingTimeInSeconds = 5
            checkObstructionFreedom = true
            sequentialSpecification = { ArrayDeque<Int>() }
        }
    }

    @Test
    fun channelLincheckTest() = runLincheckTest({ Channel<Int>() }) {
        it.operation(::trySend)
        it.operation(::send)
        it.operation1("sendViaSelect") { element: Int -> select { onSend(element) {} } }
        it.operation(::tryReceive)
        it.operation(::receive)
        it.operation0("receiveViaSelect") { select { onReceive { it } } }
        it.operation(::close)
        it.operation(::cancel)
        it.testingTimeInSeconds = 10
    }

    @Test
    fun channelLincheckTestCustomized() = runLincheckTest({ Channel<Int>() }) {
        it.operation(::trySend)
        it.operation(::send)
        it.operation1("sendViaSelect") { element: Int -> select { onSend(element) {} } }
        it.operation(::tryReceive)
        it.operation(::receive)
        it.operation0("receiveViaSelect") {
            select { onReceive { it } }
        }
        it.operation1("close") { token: String -> close(Throwable(token)) }
        it.operation1("cancel") { token: String -> cancel(CancellationException(token)) }
        it.testingTimeInSeconds = 10
    }
}