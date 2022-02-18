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
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import java.io.File

/**
 * Defines how to format the events.
 */
interface EventFormatter {
    /**
     * Returns string representation of the events.
     */
    fun format(events: List<Event>): List<String>

    /**
     * Stores the events to file.
     */
    fun storeEventsToFile(failure: LincheckFailure, events: List<Event>, filename: String?) {
        if (filename == null) return
        File(filename).printWriter().use { out ->
            val list = format(events)
            list.take(2000).forEach {
                out.println(it)
            }
        }
    }
}

/**
 * Interface which formats events in human-readable format.
 * The vector clock are not included in the formatting.
 */
class TextEventFormatter(private val addressResolver: NodeAddressResolver<*>) : EventFormatter {
    private fun formatState(event: Event) =
        if (!event.state.isNullOrBlank()) ", state=${event.state}" else ""

    private fun formatEvent(event: Event): String {
        return when (event) {
            is MessageSentEvent<*> -> "Send ${event.message} to ${event.receiver}, id=${event.id}${
                formatState(
                    event
                )
            }"
            is MessageReceivedEvent<*> -> "Received ${event.message} from ${event.sender}, id=${event.id}${
                formatState(
                    event
                )
            }"
            is CancelTimerEvent -> "Cancel timer ${event.timerName}${formatState(event)}"
            is CrashNotificationEvent -> "Received crash notification from ${event.crashedNode}${
                formatState(
                    event
                )
            }"
            is InternalEvent -> "${event.attachment}${formatState(event)}"
            is NetworkPartitionEvent -> "Network partition partitionId=${event.partitionId}, firstPart=${event.firstPart}, secondPart=${event.secondPart}${
                formatState(
                    event
                )
            }"
            is NetworkRecoveryEvent -> "Network partition recovery partitionId=${event.partitionId}${
                formatState(
                    event
                )
            }"
            is NodeCrashEvent -> "Crash${formatState(event)}"
            is NodeRecoveryEvent -> "Recovered${formatState(event)}"
            is OperationStartEvent -> "Start operation ${event.actor}${formatState(event)}"
            is ScenarioFinishEvent -> "Finish scenario${formatState(event)}"
            is SetTimerEvent -> "Set timer ${event.timerName}${formatState(event)}"
            is TimerTickEvent -> "Timer tick, name=$${event.timerName}${formatState(event)}"
        }
    }

    /**
     * Represent to which node the event belongs.
     */
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

