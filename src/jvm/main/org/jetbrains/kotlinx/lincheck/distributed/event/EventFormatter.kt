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

import org.jetbrains.kotlinx.lincheck.distributed.NodeAddressResolver

internal interface EventFormatter {
    fun format(events: List<Event>): List<String>
}

internal class TextEventFormatter(private val addressResolver: NodeAddressResolver<*>) : EventFormatter {
    private fun formatClockAndState(event: Event) =
        ", clock=${event.clock}" + if (!event.state.isNullOrBlank()) ", state=${event.state}" else ""

    private fun formatEvent(event: Event): String {
        return when (event) {
            is MessageSentEvent<*> -> "Send ${event.message} to ${event.receiver}, id=${event.id}${
                formatClockAndState(
                    event
                )
            }"
            is MessageReceivedEvent<*> -> "Received ${event.message} from ${event.sender}, id=${event.id}${
                formatClockAndState(
                    event
                )
            }"
            is CancelTimerEvent -> "Cancel timer ${event.timerName}${formatClockAndState(event)}"
            is CrashNotificationEvent -> "Received crash notification from ${event.crashedNode}${
                formatClockAndState(
                    event
                )
            }"
            is InternalEvent -> "${event.attachment}${formatClockAndState(event)}"
            is NetworkPartitionEvent -> "Network partition partitionId=${event.partitionId}, firstPart=${event.firstPart}, secondPart=${event.secondPart}${
                formatClockAndState(
                    event
                )
            }"
            is NetworkRecoveryEvent -> "Network partition recovery partitionId=${event.partitionId}${
                formatClockAndState(
                    event
                )
            }"
            is NodeCrashEvent -> "Crash${formatClockAndState(event)}"
            is NodeRecoveryEvent -> "Recovered${formatClockAndState(event)}"
            is OperationStartEvent -> "Start operation ${event.actor}${formatClockAndState(event)}"
            is ScenarioFinishEvent -> "Finish scenario${formatClockAndState(event)}"
            is SetTimerEvent -> "Set timer ${event.timerName}${formatClockAndState(event)}"
            is TimerTickEvent -> "Timer tick, name=$${event.timerName}${formatClockAndState(event)}"
        }
    }

    private fun formatHeader(iNode: Int?): String {
        if (iNode == null) {
            return "[-]: "
        }
        return if (addressResolver.isMultipleType) {
            "[$iNode, ${addressResolver[iNode].simpleName}]: "
        } else {
            "[$iNode]: "
        }
    }

    override fun format(events: List<Event>): List<String> = events.map { e -> formatHeader(e.iNode) + formatEvent(e) }
}