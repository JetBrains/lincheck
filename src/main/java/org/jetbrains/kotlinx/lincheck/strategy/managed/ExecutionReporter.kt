/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import org.jetbrains.kotlinx.lincheck.printInColumnsCustom

private val EXECUTION_INDENTATION = "  "

internal fun StringBuilder.appendExecution(
        scenario: ExecutionScenario,
        results: ExecutionResult?,
        interleavingEvents: List<InterleavingEvent>
) {
    val nThreads = scenario.threads
    // last actor that was appended for each thread
    val lastLoggedActor = IntArray(nThreads) { -1 }
    // what actors started, but did not finish in each thread
    val lastExecutedActors = IntArray(nThreads) { threadId -> interleavingEvents.filter { it.threadId == threadId}.map { it.actorId }.max() ?: -1 }
    // call stack traces of all interesting events for each thread and actor
    val interestingEvents = interestingEventStackTraces(scenario, interleavingEvents, lastExecutedActors)
    val execution = mutableListOf<InterleavingEventRepresentation>()

    for (eventId in interleavingEvents.indices) {
        val event = interleavingEvents[eventId]
        val threadId = event.threadId
        val actorId = event.actorId
        // print all actors that started since the last event
        while (lastLoggedActor[threadId] < actorId) {
            val lastActor = lastLoggedActor[threadId]
            if (isInterestingActor(threadId, lastActor, interestingEvents) && results != null)
                execution.add(InterleavingEventRepresentation(threadId, EXECUTION_INDENTATION + "result: ${results.parallelResults[threadId][actorId]}"))
            val nextActor = ++lastLoggedActor[threadId]
            if (nextActor != scenario.parallelExecution[threadId].size) {
                // print actor
                // if it is not interesting then print with the result in the same line
                val actorRepresentation = getActorRepresentation(threadId, nextActor, scenario, results, isInterestingActor(threadId, nextActor, interestingEvents))
                execution.add(InterleavingEventRepresentation(threadId, actorRepresentation))
            }
        }
        // print the event itself
        when (event) {
            is SwitchEvent -> {
                val reason = if (event.reason.toString().isEmpty()) "" else " (reason: ${event.reason})"
                execution.add(InterleavingEventRepresentation(threadId, EXECUTION_INDENTATION + "switch" + reason))
            }
            is SuspendSwitchEvent -> {
                execution.add(InterleavingEventRepresentation(threadId, EXECUTION_INDENTATION + "switch (reason: ${event.reason})"))
            }
            is FinishEvent -> {
                execution.add(InterleavingEventRepresentation(threadId,  EXECUTION_INDENTATION + "thread is finished"))
            }
            is PassCodeLocationEvent -> {
                if (isInterestingActor(threadId, actorId, interestingEvents)) {
                    execution.add(InterleavingEventRepresentation(threadId, EXECUTION_INDENTATION +  event.codeLocation.shorten()))
                    if (event.stateRepresentation != null)
                        execution.add(InterleavingEventRepresentation(threadId, EXECUTION_INDENTATION + "STATE: ${event.stateRepresentation}"))
                    execution.add(InterleavingEventRepresentation(threadId, EXECUTION_INDENTATION +  event.codeLocation.shorten()))
                }
            }
        }
    }

    val executionData = splitToColumns(nThreads, execution)

    appendln("= Parallel part execution: =")
    appendln(printInColumnsCustom(executionData) {
        val builder = StringBuilder()
        for (i in it.indices) {
            builder.append(if (i == 0) "| " else " | ")
            builder.append(it[i])
        }
        builder.append(" |")

        builder.toString()
    })
}

/**
 * Removes info about package in a stack trace element representation
 */
private fun StackTraceElement.shorten(): String {
    val stackTraceElement = this.toString().replace('/', '.')
    var wasPoints = 0
    for ((i, c) in stackTraceElement.withIndex().reversed()) {
        if (c == '.') {
            wasPoints++
            if (wasPoints == 3)
                return stackTraceElement.drop(i + 1)
        }
    }
    return stackTraceElement
}

private class InterleavingEventRepresentation(val threadId: Int, val representation: String)

private fun interestingEventStackTraces(
        scenario: ExecutionScenario,
        interleavingEvents: List<InterleavingEvent>,
        lastExecutedActors: IntArray
): Array<Array<List<CallStackTrace>>> = Array(scenario.parallelExecution.size) { threadId ->
    val eventsInThread = interleavingEvents.filter { it.threadId == threadId}
    Array(scenario.parallelExecution[threadId].size) { actorId ->
        val interestingCallStackTraces = mutableListOf<CallStackTrace>()
        interestingCallStackTraces.addAll(
                eventsInThread.filter { it.actorId == actorId}.filterIsInstance(SwitchEvent::class.java).map { it.callStackTrace }
        )
        interestingCallStackTraces.addAll(
                eventsInThread.filter { it.actorId == actorId}.filterIsInstance(SuspendSwitchEvent::class.java).map { it.callStackTrace }
        )

        // last pass code location can also be interesting in case of incomplete execution
        if (actorId == lastExecutedActors[threadId]) {
            when (val lastEvent = eventsInThread.lastOrNull()) {
                is PassCodeLocationEvent -> interestingCallStackTraces.add(lastEvent.callStackTrace)
                else -> {}
            }
        }
        interestingCallStackTraces as List<CallStackTrace>
    }
}

// convert events that should be printed to the final form of a matrix of strings
private fun splitToColumns(nThreads: Int, execution: List<InterleavingEventRepresentation>): List<List<String>> {
    val result = List(nThreads) { mutableListOf<String>() }
    for (message in execution) {
        val columnId = message.threadId
        // write messages in appropriate columns
        result[columnId].add(message.representation)
        val neededSize = result[columnId].size
        for (column in result)
            if (column.size != neededSize)
                column.add("")
    }
    return result
}

private fun CallStackTrace.firstDifferPosition(callStackTrace: CallStackTrace): Int {
    for (position in indices)
        if (this[position].identifier != callStackTrace[position].identifier)
            return position
    return size
}

private fun getActorRepresentation(threadId: Int, actorId: Int, scenario: ExecutionScenario, results: ExecutionResult?, isInteresting: Boolean) =
        StringBuilder().apply {
    append(scenario.parallelExecution[threadId][actorId].toString())
    if (results != null && isInteresting)
        append(": ${results.parallelResults[threadId][actorId]}")
}.toString()

private fun isInterestingActor(threadId: Int, actorId: Int, interestingEvents: Array<Array<List<CallStackTrace>>>) =
        interestingEvents[threadId][actorId].isNotEmpty()
