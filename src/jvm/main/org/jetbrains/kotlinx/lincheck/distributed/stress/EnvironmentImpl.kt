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

import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.Event
import org.jetbrains.kotlinx.lincheck.distributed.MessageSentEvent
import org.jetbrains.kotlinx.lincheck.distributed.Node
import java.lang.IllegalArgumentException

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

    private val probability = Probability(context.tesCfg, numberOfNodes)

    override fun getAddressesForClass(cls: Class<out Node<Message>>) = context.addressResolver[cls]

    override suspend fun send(message: Message, receiver: Int) {
        if (isFinished) {
            logMessage(LogLevel.ALL_EVENTS) {
                "[$nodeId]: Cannot send, the environment is closed"
            }
            return
        }
        if (context.failureInfo[nodeId] || context.failureInfo[receiver]) {
            return
            //TODO: it doesn't work:(
        }
        if (probability.nodeFailed() &&
            context.failureInfo.trySetFailed(nodeId)
        ) {
            throw NodeFailureException(nodeId)
        }
        context.incClock(nodeId)

        val event = MessageSentEvent(
            message = message,
            sender = nodeId,
            receiver = receiver,
            id = context.messageId.getAndIncrement(),
            clock = context.vectorClock[nodeId].copyOf()
        )
        val aaa = if (nodeId == receiver) {
            "AAAAAAAAAAAAAAAAAAAA"
        } else {
            ""
        }
        logMessage(LogLevel.ALL_EVENTS) {
            "[$nodeId]: $aaa Before sending $event"
        }
        context.events[nodeId].add(event)
        try {
            repeat(probability.duplicationRate()) {
                context.incomeMessages[event.receiver].send(event)
                logMessage(LogLevel.MESSAGES) {
                    "[$nodeId]: Send $event to $receiver ${context.incomeMessages[event.receiver].hashCode()}"
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
    override suspend fun <T> withTimeout(ticks: Int, block: suspend () -> T): T? =
        withTimeout(ticks * TICK_TIME, block)
}