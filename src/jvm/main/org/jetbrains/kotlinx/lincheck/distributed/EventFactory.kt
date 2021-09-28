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

package org.jetbrains.kotlinx.lincheck.distributed

import kotlinx.serialization.Serializable
import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.execution.emptyClockArray


/**
 * Event for a node.
 */
sealed class Event {
    abstract val iNode: Int
}

/**
 *
 */
data class MessageSentEvent<Message>(
    override val iNode: Int,
    val message: Message,
    val receiver: Int,
    val id: Int,
    val clock: VectorClock,
    val state: String
) : Event() {
    override fun toString(): String =
        "Send $message to $receiver, messageId=$id, clock=${clock}" + if (state.isNotBlank()) ", state=$state" else ""
}

data class MessageReceivedEvent<Message>(
    override val iNode: Int,
    val message: Message,
    val sender: Int,
    val id: Int,
    val clock: VectorClock,
    val state: String
) : Event() {
    override fun toString(): String =
        "Received $message from $sender, messageId=$id, clock=${clock}" + if (state.isNotBlank()) ", state={$state}" else ""
}

data class InternalEvent(override val iNode: Int, val attachment: Any, val clock: VectorClock, val state: String) :
    Event() {
    override fun toString(): String =
        "$attachment, clock=$clock" + if (state.isNotBlank()) ", state={$state}" else ""
}

@Serializable
data class NodeCrashEvent(override val iNode: Int, val clock: VectorClock, val state: String) : Event()

data class NodeRecoveryEvent(override val iNode: Int, val clock: VectorClock, val state: String) : Event()

data class OperationStartEvent(override val iNode: Int, val actor: Actor, val clock: VectorClock, val state: String) :
    Event() {
    override fun toString(): String =
        "Start operation $actor, clock=${clock}" + if (state.isNotBlank()) ", state={$state}" else ""
}

data class ScenarioFinishEvent(override val iNode: Int, val clock: VectorClock, val state: String) :
    Event() {
    override fun toString(): String =
        "Finish scenario, clock=${clock}" + if (state.isNotBlank()) ", state={$state}" else ""
}

data class CrashNotificationEvent(
    override val iNode: Int,
    val crashedNode: Int,
    val clock: VectorClock,
    val state: String
) : Event()

data class SetTimerEvent(override val iNode: Int, val timerName: String, val clock: VectorClock, val state: String) :
    Event()

data class TimerTickEvent(override val iNode: Int, val timerName: String, val clock: VectorClock, val state: String) :
    Event()

data class CancelTimerEvent(override val iNode: Int, val timerName: String, val clock: VectorClock, val state: String) :
    Event()

data class NetworkPartitionEvent(override val iNode: Int, val partitions: List<Set<Int>>, val partitionCount: Int) :
    Event()

data class NetworkRecoveryEvent(override val iNode: Int, val partitionCount: Int) : Event()

internal class EventFactory<M, L>(testCfg: DistributedCTestConfiguration<M, L>) {
    private var msgId = 0
    private val _events = mutableListOf<Event>()
    val events: List<Event>
        get() = _events
    val numberOfNodes = testCfg.addressResolver.totalNumberOfNodes
    private val vectorClocks = Array(numberOfNodes) {
        VectorClock(emptyClockArray(numberOfNodes), it)
    }
    lateinit var nodeInstances: Array<out Node<M, L>>

    fun createMessageEvent(msg: M, sender: Int, receiver: Int): MessageSentEvent<M> {
        val event = MessageSentEvent(
            iNode = sender,
            message = msg,
            receiver = receiver,
            id = msgId++,
            vectorClocks[sender].incAndCopy(),
            nodeInstances[sender].stateRepresentation()
        )
        _events.add(event)
        return event
    }

    fun createOperationEvent(actor: Actor, iNode: Int) {
        val event =
            OperationStartEvent(
                iNode,
                actor,
                vectorClocks[iNode].incAndCopy(),
                nodeInstances[iNode].stateRepresentation()
            )
        _events.add(event)
    }

    fun createNodeRecoverEvent(iNode: Int) {
        _events.add(
            NodeRecoveryEvent(
                iNode,
                vectorClocks[iNode].copy(),
                nodeInstances[iNode].stateRepresentation()
            )
        )
    }

    fun createMessageReceiveEvent(sentEvent: MessageSentEvent<M>) {
        val receiver = sentEvent.receiver
        val event = MessageReceivedEvent(
            iNode = receiver,
            message = sentEvent.message,
            sender = sentEvent.iNode,
            id = sentEvent.id,
            clock = vectorClocks[receiver].maxClock(sentEvent.clock),
            state = nodeInstances[receiver].stateRepresentation()
        )
        _events.add(event)
    }

    fun createTimerTickEvent(name: String, iNode: Int) {
        _events.add(
            TimerTickEvent(
                iNode = iNode,
                timerName = name,
                clock = vectorClocks[iNode].copy(),
                state = nodeInstances[iNode].stateRepresentation()
            )
        )
    }

    fun createInternalEvent(attachment: Any, iNode: Int) {
        _events.add(
            InternalEvent(
                iNode,
                attachment,
                vectorClocks[iNode].copy(),
                nodeInstances[iNode].stateRepresentation()
            )
        )
    }

    fun createNodeCrashEvent(iNode: Int) {
        _events.add(NodeCrashEvent(iNode, vectorClocks[iNode].copy(), nodeInstances[iNode].stateRepresentation()))
    }

    fun createScenarioFinishEvent(iNode: Int) {
        _events.add(
            ScenarioFinishEvent(iNode,
                vectorClocks[iNode].copy(),
                nodeInstances[iNode].stateRepresentation()
            )
        )
    }
}