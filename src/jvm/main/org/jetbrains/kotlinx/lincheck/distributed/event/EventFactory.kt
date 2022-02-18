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
import org.jetbrains.kotlinx.lincheck.distributed.DistributedStateHolder
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.jetbrains.kotlinx.lincheck.execution.emptyClockArray

/**
 * Creates and stores the events which happen during the execution.
 */
internal class EventFactory<M>(testCfg: DistributedCTestConfiguration<M>, val storeStates: Boolean) {
    private var msgId = 0
    private val _events = mutableListOf<Event>()
    val events: List<Event>
        get() = _events
    private val vectorClocks = Array(testCfg.addressResolver.nodeCount) {
        VectorClock(emptyClockArray(testCfg.addressResolver.nodeCount), it)
    }

    // To access `stateRepresentation`
    lateinit var nodeInstances: Array<out Node<M>>

    // Guarantees that crash won't happen while the state representation is collected.
    private fun <T> safeDatabaseAccess(f: () -> T): T {
        val prev = DistributedStateHolder.canCrashBeforeAccessingDatabase
        DistributedStateHolder.canCrashBeforeAccessingDatabase = false
        return f().also { DistributedStateHolder.canCrashBeforeAccessingDatabase = prev }
    }

    private fun getState(iNode: Int) = if (storeStates) {
        nodeInstances[iNode].stateRepresentation()
    } else ""

    fun createMessageEvent(msg: M, sender: Int, receiver: Int): MessageSentEvent<M> = safeDatabaseAccess {
        val event = MessageSentEvent(
            iNode = sender,
            message = msg,
            receiver = receiver,
            id = msgId++,
            vectorClocks[sender].incAndCopy(),
            getState(sender)
        )
        _events.add(event)
        event
    }

    fun createOperationEvent(actor: Actor, iNode: Int) = safeDatabaseAccess {
        val event =
            OperationStartEvent(
                iNode,
                actor,
                vectorClocks[iNode].incAndCopy(),
                getState(iNode)
            )
        _events.add(event)
    }

    fun createNodeRecoverEvent(iNode: Int) = safeDatabaseAccess {
        _events.add(
            NodeRecoveryEvent(
                iNode,
                vectorClocks[iNode].copy(),
                getState(iNode)
            )
        )
    }

    fun createMessageReceiveEvent(sentEvent: MessageSentEvent<M>) = safeDatabaseAccess {
        val receiver = sentEvent.receiver
        val event = MessageReceivedEvent(
            iNode = receiver,
            message = sentEvent.message,
            sender = sentEvent.iNode,
            id = sentEvent.id,
            clock = vectorClocks[receiver].maxClock(sentEvent.clock),
            state = getState(receiver)
        )
        _events.add(event)
    }

    fun createTimerTickEvent(name: String, iNode: Int) = safeDatabaseAccess {
        _events.add(
            TimerTickEvent(
                iNode = iNode,
                timerName = name,
                clock = vectorClocks[iNode].copy(),
                state = getState(iNode)
            )
        )
    }

    fun createInternalEvent(attachment: Any, iNode: Int) = safeDatabaseAccess {
        _events.add(
            InternalEvent(
                iNode,
                attachment,
                vectorClocks[iNode].copy(),
                getState(iNode)
            )
        )
    }

    fun createNodeCrashEvent(iNode: Int) = safeDatabaseAccess {
        _events.add(NodeCrashEvent(iNode, vectorClocks[iNode].copy(), getState(iNode)))
    }

    fun createScenarioFinishEvent(iNode: Int) = safeDatabaseAccess {
        _events.add(
            ScenarioFinishEvent(
                iNode,
                vectorClocks[iNode].copy(),
                getState(iNode)
            )
        )
    }

    fun createCrashNotificationEvent(iNode: Int, crashedNode: Int) = safeDatabaseAccess {
        _events.add(
            CrashNotificationEvent(
                iNode, crashedNode, vectorClocks[iNode].copy(),
                getState(iNode)
            )
        )
    }

    fun createSetTimerEvent(iNode: Int, timerName: String) = safeDatabaseAccess {
        _events.add(
            SetTimerEvent(
                iNode,
                timerName,
                vectorClocks[iNode].copy(),
                getState(iNode)
            )
        )
    }

    fun createCancelTimerEvent(iNode: Int, timerName: String) = safeDatabaseAccess {
        _events.add(
            CancelTimerEvent(
                iNode,
                timerName,
                vectorClocks[iNode].copy(),
                getState(iNode)
            )
        )
    }

    fun createNetworkPartitionEvent(
        firstPart: List<Int>,
        secondPart: List<Int>,
        partitionCount: Int
    ) {
        _events.add(NetworkPartitionEvent(firstPart, secondPart, partitionCount))
    }

    fun createNetworkRecoverEvent(partitionCount: Int) {
        _events.add(NetworkRecoveryEvent(partitionCount))
    }
}