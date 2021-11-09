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


/**
 * Event for a node.
 */
sealed class Event {
    abstract val iNode: Int
    abstract val clock : VectorClock
    abstract val state : String
}

/**
 *
 */
data class MessageSentEvent<Message>(
    override val iNode: Int,
    val message: Message,
    val receiver: Int,
    val id: Int,
    override val clock: VectorClock,
    override val state: String
) : Event()

data class MessageReceivedEvent<Message>(
    override val iNode: Int,
    val message: Message,
    val sender: Int,
    val id: Int,
    override val clock: VectorClock,
    override val state: String
) : Event()

data class InternalEvent(
    override val iNode: Int,
    val attachment: Any,
    override val clock: VectorClock,
    override val state: String
) : Event()

data class NodeCrashEvent(override val iNode: Int, override val clock: VectorClock, override val state: String) : Event()

data class NodeRecoveryEvent(override val iNode: Int, override val clock: VectorClock, override val state: String) : Event()

data class OperationStartEvent(
    override val iNode: Int,
    val actor: Actor,
    override val clock: VectorClock,
    override val state: String
) : Event()

data class ScenarioFinishEvent(override val iNode: Int, override val clock: VectorClock, override val state: String) : Event()

data class CrashNotificationEvent(
    override val iNode: Int,
    val crashedNode: Int,
    override val clock: VectorClock,
    override val state: String
) : Event()

data class SetTimerEvent(override val iNode: Int, val timerName: String, override val clock: VectorClock, override val state: String) :
    Event()

data class TimerTickEvent(override val iNode: Int, val timerName: String, override val clock: VectorClock, override val state: String) :
    Event()

data class CancelTimerEvent(override val iNode: Int, val timerName: String, override val clock: VectorClock, override val state: String) :
    Event()

data class NetworkPartitionEvent(override val iNode: Int, val partitions: List<Set<Int>>, val partitionCount: Int,
                                 override val clock: VectorClock, override val state: String) :
    Event()

data class NetworkRecoveryEvent(override val iNode: Int, val partitionCount: Int, override val clock: VectorClock, override val state: String) : Event()
