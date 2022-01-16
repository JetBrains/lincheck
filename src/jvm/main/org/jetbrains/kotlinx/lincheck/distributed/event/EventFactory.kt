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

package org.jetbrains.kotlinx.lincheck.distributed.event

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.distributed.DistributedCTestConfiguration
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.execution.emptyClockArray


internal class EventFactory<M, L>(testCfg: DistributedCTestConfiguration<M, L>) {
    private var msgId = 0
    private val _events = mutableListOf<Event>()
    val events: List<Event>
        get() = _events
    val numberOfNodes = testCfg.addressResolver.nodeCount
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
            ScenarioFinishEvent(
                iNode,
                vectorClocks[iNode].copy(),
                nodeInstances[iNode].stateRepresentation()
            )
        )
    }

    fun createCrashNotificationEvent(iNode: Int, crashedNode: Int) {
        _events.add(
            CrashNotificationEvent(
                iNode, crashedNode, vectorClocks[iNode].copy(),
                nodeInstances[iNode].stateRepresentation()
            )
        )
    }

    fun createSetTimerEvent(iNode: Int, timerName: String) {
        _events.add(
            SetTimerEvent(
                iNode,
                timerName,
                vectorClocks[iNode].copy(),
                nodeInstances[iNode].stateRepresentation()
            )
        )
    }

    fun createCancelTimerEvent(iNode: Int, timerName: String) {
        _events.add(
            CancelTimerEvent(
                iNode,
                timerName,
                vectorClocks[iNode].copy(),
                nodeInstances[iNode].stateRepresentation()
            )
        )
    }
}