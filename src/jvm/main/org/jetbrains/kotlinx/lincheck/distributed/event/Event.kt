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
import org.jetbrains.kotlinx.lincheck.execution.emptyClockArray

/**
 * Represents the event which happened during the execution of distributed algorithm.
 * [iNode] is the node to which the event belongs or null, if the event doesn't belong to any node.
 * [clock] is the vector clock corresponding to this event
 * [state] is the state of the [iNode] (or null if [iNode] is null)
 */
sealed class Event {
    abstract val iNode: Int?
    abstract val clock: VectorClock
    abstract val state: String?
}

/**
 * Indicates that message [message] with id [id] was sent by node [iNode] to node [receiver].
 */
data class MessageSentEvent<Message>(
    override val iNode: Int,
    val message: Message,
    val receiver: Int,
    val id: Int,
    override val clock: VectorClock,
    override val state: String
) : Event()

/**
 * Indicates that message [message] with id [id] from node [sender]
 * was received by node [iNode].
 */
data class MessageReceivedEvent<Message>(
    override val iNode: Int,
    val message: Message,
    val sender: Int,
    val id: Int,
    override val clock: VectorClock,
    override val state: String
) : Event()

/**
 * Any event stored by user during the execution.
 * See [org.jetbrains.kotlinx.lincheck.distributed.Environment.recordInternalEvent]
 */
data class InternalEvent(
    override val iNode: Int,
    val attachment: Any,
    override val clock: VectorClock,
    override val state: String
) : Event()

/**
 * Indicates that the node [iNode] has crashed.
 */
data class NodeCrashEvent(override val iNode: Int, override val clock: VectorClock, override val state: String) :
    Event()

/**
 * Indicates that node [iNode] has recovered after crash.
 */
data class NodeRecoveryEvent(override val iNode: Int, override val clock: VectorClock, override val state: String) :
    Event()

/**
 * Indicates that operation with actor [actor] has started.
 */
data class OperationStartEvent(
    override val iNode: Int,
    val actor: Actor,
    override val clock: VectorClock,
    override val state: String
) : Event()

/**
 * Indicates that all operations for node [iNode] have been executed.
 * The event is not stored if the node wasn't included in the execution scenario.
 */
data class ScenarioFinishEvent(override val iNode: Int, override val clock: VectorClock, override val state: String) :
    Event()

/**
 * Indicates that [iNode] received the crash notification from [crashedNode].
 * See [org.jetbrains.kotlinx.lincheck.distributed.Node.onNodeUnavailable]
 */
data class CrashNotificationEvent(
    override val iNode: Int,
    val crashedNode: Int,
    override val clock: VectorClock,
    override val state: String
) : Event()

/**
 * Indicates that node [iNode] set the timer with name [timerName].
 * See [org.jetbrains.kotlinx.lincheck.distributed.Environment.setTimer]
 */
data class SetTimerEvent(
    override val iNode: Int,
    val timerName: String,
    override val clock: VectorClock,
    override val state: String
) :
    Event()

/**
 *
 */
//TODO translate таймер сработал
data class TimerTickEvent(
    override val iNode: Int,
    val timerName: String,
    override val clock: VectorClock,
    override val state: String
) :
    Event()

/**
 * Indicates that node [iNode] cancelled the timer with name [timerName].
 * See [org.jetbrains.kotlinx.lincheck.distributed.Environment.cancelTimer]
 */
data class CancelTimerEvent(
    override val iNode: Int,
    val timerName: String,
    override val clock: VectorClock,
    override val state: String
) :
    Event()

/**
 * Indicates that the network partition with id [partitionId] has been created.
 * [firstPart] is the first part of the partition
 * [secondPart] is the second part of the partition
 */
data class NetworkPartitionEvent(
    val firstPart: List<Int>, val secondPart: List<Int>, val partitionId: Int
) :
    Event() {
    override val iNode: Int? = null
    override val clock: VectorClock = VectorClock(emptyClockArray(1), -1)
    override val state: String? = null
}

/**
 * Indicates that partition with id [partitionId] has been removed.
 */
data class NetworkRecoveryEvent(
    val partitionId: Int
) : Event() {
    override val iNode: Int? = null
    override val clock: VectorClock = VectorClock(emptyClockArray(1), -1)
    override val state: String? = null
}
