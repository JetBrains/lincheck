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

package org.jetbrains.kotlinx.lincheck.distributed.stress

import kotlinx.atomicfu.atomicArrayOfNulls
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder
import java.util.concurrent.ThreadLocalRandom


interface MessageChannel<E> {
    suspend fun send(item: E)

    suspend fun receive(): E

    suspend fun close(): Boolean

    fun clear()
}

class FifoChannel<E> : MessageChannel<E> {
    private val channel = Channel<E>(UNLIMITED)

    override suspend fun send(item: E) {
        channel.send(item)
        logMessage(LogLevel.ALL_EVENTS) {
            "Push message $item to channel ${channel.hashCode()}"
        }
    }

    override suspend fun receive() : E {
        val res = channel.receive()
        logMessage(LogLevel.ALL_EVENTS) {
            "Poll message $res from channel ${channel.hashCode()}"
        }
        return res
    }

    override suspend fun close() = channel.close()

    override fun hashCode(): Int {
        return channel.hashCode()
    }

    override fun clear() {
        do {
            val r = channel.poll()
            if (r != null) println("AAAAAA $r")
        } while (r != null)
    }
}

class AsynchronousChannel<E> : MessageChannel<E> {
    companion object {
        const val MIX_RATE = 3
    }

    private val random: ThreadLocalRandom = ThreadLocalRandom.current()
    private val channel = Channel<E>(UNLIMITED)

    override suspend fun send(item: E) {
        channel.send(item)
    }

    override suspend fun receive(): E {
        var item = channel.receive()
        val mixRate = random.nextInt(0, MIX_RATE)
        repeat(mixRate) {
            channel.send(item)
            item = channel.poll()!!
        }
        return item
    }

    override suspend fun close(): Boolean = channel.close()

    override fun clear() {
        do {
            val r = channel.poll()
            if (r != null) println("AAAAAA $r")
        } while (r != null)
    }
}


class ChannelHandler<E>(
    private val messageOrder: MessageOrder,
    private val numberOfNodes: Int
) {
    private fun createChannels(): Array<MessageChannel<E>> = when (messageOrder) {
        MessageOrder.SYNCHRONOUS -> {
            val channel = FifoChannel<E>()
            Array(numberOfNodes) { channel }
        }
        MessageOrder.FIFO -> Array(numberOfNodes) { FifoChannel() }
        MessageOrder.ASYNCHRONOUS -> Array(numberOfNodes) { AsynchronousChannel() }
    }

    private val channels = atomicArrayOfNulls<Array<MessageChannel<E>>>(numberOfNodes)

    init {
        repeat(numberOfNodes) {
            channels[it].lazySet(createChannels())
        }
    }

    operator fun get(sender: Int, receiver: Int) = channels[receiver].value!![sender]

    suspend fun close(i: Int) = channels[i].value!!.forEach { it.close() }

    fun reset(i: Int) {
        channels[i].value = createChannels()
    }

    fun clear() {
        repeat(numberOfNodes) {
            channels[it].value!!.forEach { it.clear() }
        }
    }
}