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

package org.jetbrains.kotlinx.lincheck.distributed.queue

import kotlinx.atomicfu.AtomicInt
import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.distributed.MessageSentEvent
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import kotlin.random.Random


/**
 * The interface for message queue. Different implementations provide different guarantees of message delivery order.
 */
internal interface MessageQueue<Message> {
    fun put(msg: MessageSentEvent<Message>)
    fun get(): MessageSentEvent<Message>?
    fun getTillEmpty() : MessageSentEvent<Message>?
}

internal class SynchronousMessageQueue<Message> : MessageQueue<Message> {
    private val messageQueue = LinkedBlockingQueue<MessageSentEvent<Message>>()
    override fun put(msg: MessageSentEvent<Message>) {
        messageQueue.put(msg)
    }

    override fun get(): MessageSentEvent<Message>?  {
        cntGet++
        return messageQueue.poll()
    }

    override fun getTillEmpty(): MessageSentEvent<Message>? = get()
}

@Volatile
var cntGet : Long = 0

val cntPut = atomic(0)

internal class FifoMessageQueue<Message>(val numberOfNodes: Int) : MessageQueue<Message> {
    private val messageQueues = Array(numberOfNodes) {
        LinkedBlockingQueue<MessageSentEvent<Message>>()
    }

    override fun put(msg: MessageSentEvent<Message>) {
        messageQueues[msg.receiver].put(msg)
    }

    override fun get(): MessageSentEvent<Message>? {
        cntGet++
        val index = Random.nextInt(numberOfNodes)
        return messageQueues[index].poll()
    }

    override fun getTillEmpty(): MessageSentEvent<Message>? {
        cntGet++
        for (i in (0 until numberOfNodes).shuffled()) {
            val msg = messageQueues[i].poll()
            if (msg != null) {
                return msg
            }
        }
        return null
    }
}

internal class AsynchronousMessageQueue<Message>(private val numberOfNodes: Int) : MessageQueue<Message> {
    private val messageQueues = Array(numberOfNodes) {
        LinkedBlockingQueue<MessageSentEvent<Message>>()
    }

    override fun put(msg: MessageSentEvent<Message>) {
        cntPut.incrementAndGet()
        val rand: ThreadLocalRandom = ThreadLocalRandom.current()
        val queueToPut = rand.nextInt(numberOfNodes)
        messageQueues[queueToPut].put(msg)
    }

    override fun get(): MessageSentEvent<Message>? {
        cntGet++
        val rand: ThreadLocalRandom = ThreadLocalRandom.current()
        val index = rand.nextInt(numberOfNodes)
        return messageQueues[index].poll()
    }

    override fun getTillEmpty() : MessageSentEvent<Message>? {
        cntGet++
        val rand: ThreadLocalRandom = ThreadLocalRandom.current()
        var first = rand.nextInt(numberOfNodes)
        for (i in (0 until numberOfNodes).shuffled()) {
            val msg = messageQueues[i].poll()
            if (msg != null) {
                return msg
            }
        }
        return null
    }
}