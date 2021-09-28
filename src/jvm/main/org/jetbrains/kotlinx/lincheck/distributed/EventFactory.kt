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

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.execution.emptyClockArray

internal class EventFactory<M, L>(testCfg: DistributedCTestConfiguration<M, L>) {
    private var msgId = 0
    private val _events = mutableListOf<Pair<Int, Event>>()
    val events: List<Pair<Int, Event>>
        get() = _events
    val numberOfNodes = testCfg.addressResolver.totalNumberOfNodes
    private val vectorClocks = Array(numberOfNodes) {
        VectorClock(emptyClockArray(numberOfNodes), it)
    }
    lateinit var nodeInstances: Array<out Node<M, L>>

    fun createMessageEvent(msg: M, sender: Int, receiver: Int): MessageSentEvent<M> {
        val event = MessageSentEvent(
            message = msg,
            receiver = receiver,
            id = msgId++,
            vectorClocks[sender].incAndCopy(),
            nodeInstances[sender].stateRepresentation()
        )
        _events.add(sender to event)
        return event
    }

    fun createOperationEvent(actor: Actor, iNode: Int) {
        val event =
            OperationStartEvent(actor, vectorClocks[iNode].incAndCopy(), nodeInstances[iNode].stateRepresentation())
        _events.add(iNode to event)
    }

    fun createNodeRecoverEvent(iNode: Int) {
        _events.add(iNode to ProcessRecoveryEvent(vectorClocks[iNode].copy(), nodeInstances[iNode].stateRepresentation()))
    }

    fun createMessageReceiveEvent(sentEvent: MessageSentEvent<M>, sender: Int) {
        val receiver = sentEvent.receiver
        val event = MessageReceivedEvent(
            message = sentEvent.message,
            sender = sender,
            id = sentEvent.id,
            clock = vectorClocks[receiver].maxClock(sentEvent.clock),
            state = nodeInstances[receiver].stateRepresentation()
        )
        _events.add(receiver to event)
    }

    fun createTimerTickEvent(name: String, iNode: Int) {
        _events.add(
            iNode to TimerTickEvent(
                timerName = name,
                clock = vectorClocks[iNode].copy(),
                state = nodeInstances[iNode].stateRepresentation()
            )
        )
    }

    fun createInternalEvent(attachment: Any, iNode: Int) {
        _events.add(
            iNode to InternalEvent(
                attachment,
                vectorClocks[iNode].copy(),
                nodeInstances[iNode].stateRepresentation()
            )
        )
    }

    fun createNodeCrashEvent(iNode: Int) {
        _events.add(iNode to NodeCrashEvent(vectorClocks[iNode].copy(), nodeInstances[iNode].stateRepresentation()))
    }

    fun createScenarioFinishEvent(iNode: Int) {
        _events.add(
            iNode to ScenarioFinishEvent(
                vectorClocks[iNode].copy(),
                nodeInstances[iNode].stateRepresentation()
            )
        )
    }

    fun events(): List<Pair<Int, Event>> = _events
}