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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.kotlinx.lincheck.distributed.*

internal class EnvironmentImpl<Message, Log>(
    val context: DistributedRunnerContext<Message, Log>,
    override val nodeId: Int,
    override val log: MutableList<Log> = mutableListOf()
) :
    Environment<Message, Log> {
    companion object {
        const val TICK_TIME = 1
    }

    override val numberOfNodes = context.addressResolver.totalNumberOfNodes

    private val probability = context.probabilities[nodeId]

    override fun getAddressesForClass(cls: Class<out Node<Message>>) = context.addressResolver[cls]

    override suspend fun send(message: Message, receiver: Int) {
        if (isFinished) {
            logMessage(LogLevel.ALL_EVENTS) {
                "[$nodeId]: Cannot send, the environment is closed"
            }
            return
        }
        if (context.failureInfo[nodeId] || context.failureInfo[receiver]) {
            logMessage(LogLevel.ALL_EVENTS) {
                "[$nodeId]: Cannot send, we are failed we=${context.failureInfo[nodeId]}, receiver=${context.failureInfo[receiver]}"
            }
            return
        }
        if (context.addressResolver.canFail(nodeId) &&
            probability.nodeFailed() &&
            context.failureInfo.trySetFailed(nodeId)
        ) {
            println("[$nodeId]: Failed on message $message to $receiver")
            throw CrashError()
        }
        context.incClock(nodeId)

        val event = MessageSentEvent(
            message = message,
            sender = nodeId,
            receiver = receiver,
            id = context.messageId.getAndIncrement(),
            clock = context.vectorClock[nodeId].copyOf()
        )
        logMessage(LogLevel.ALL_EVENTS) {
            "[$nodeId]: Before sending $event"
        }
        context.events[nodeId].add(event)
        try {
            repeat(probability.duplicationRate()) {
                context.messageHandler[nodeId, event.receiver].send(event)
                logMessage(LogLevel.MESSAGES) {
                    "[$nodeId]: Send $event to $receiver ${context.messageHandler[nodeId, event.receiver].hashCode()}, by channel {channel.hashCode()}"
                }
            }
        } catch (e: ClosedSendChannelException) {
            logMessage(LogLevel.ALL_EVENTS) {
                "[$nodeId]: Channel $receiver is closed"
            }
        }
    }

    override fun events(): Array<List<Event>> = if (isFinished) {
        context.events.map { it.toList() }.toTypedArray()
    } else {
        throw IllegalAccessException("Cannot access events until the execution is over ")
    }

    @Volatile
    internal var isFinished = false

    override suspend fun withTimeout(ticks: Int, block: suspend CoroutineScope.() -> Unit) = try {
        val r = context.executorContext.increment()
        logMessage(LogLevel.ALL_EVENTS) {
            "[$nodeId]: With timeout, waiting, counter is $r"
        }
        val res = withTimeoutOrNull((ticks * TICK_TIME).toLong(), block)
    } catch (e: Throwable) {
        logMessage(LogLevel.ALL_EVENTS) {
            "[$nodeId]: Exception in timeout $e"
        }
    } finally {
        val r = context.executorContext.decrement()
        logMessage(LogLevel.ALL_EVENTS) {
            "[$nodeId]: With timeout, finished, counter is $r"
        }
    }

    override fun getLogs() = context.logs
}