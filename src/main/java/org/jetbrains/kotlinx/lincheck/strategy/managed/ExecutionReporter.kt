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
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.math.min

private const val EXECUTION_INDENTATION = "  "

internal fun StringBuilder.appendExecution(
        scenario: ExecutionScenario,
        results: ExecutionResult?,
        interleaving: List<InterleavingEvent>
) {
    val nThreads = scenario.threads
    // last actor that was appended for each thread
    val lastLoggedActor = IntArray(nThreads) { -1 }
    // what actors started, but did not finish in each thread
    val lastExecutedActors = IntArray(nThreads) { threadId -> interleaving.filter { it.threadId == threadId}.map { it.actorId }.max() ?: -1 }
    // call stack traces of all interesting events for each thread and actor
    val interestingEvents = interestingEventStackTraces(scenario, interleaving, lastExecutedActors)
    // set of identifiers which were appended
    val loggedMethodCalls = mutableSetOf<Int>()
    val execution = mutableListOf<InterleavingEventRepresentation>()
    objectNumeration.clear()
    eventLoop@for (eventId in interleaving.indices) {
        val event = interleaving[eventId]
        val threadId = event.threadId
        val actorId = event.actorId
        // print all actors that started since the last event
        while (lastLoggedActor[threadId] < min(actorId, scenario.parallelExecution[threadId].size)) {
            val lastActor = lastLoggedActor[threadId]
            if (lastActor >= 0 && isInterestingActor(threadId, lastActor, interestingEvents) && results != null)
                execution.add(InterleavingEventRepresentation(threadId, EXECUTION_INDENTATION + "result: ${results.parallelResults[threadId][lastActor]}"))
            val nextActor = ++lastLoggedActor[threadId]
            if (nextActor != scenario.parallelExecution[threadId].size) {
                // print actor
                // if it is not interesting then print with the result in the same line
                val isInterestingActor = isInterestingActor(threadId, nextActor, interestingEvents)
                val actorRepresentation = getActorRepresentation(threadId, nextActor, scenario, results, isInterestingActor)
                execution.add(InterleavingEventRepresentation(threadId, actorRepresentation))
                if (!isInterestingActor) {
                    val stateRepresentation = lastStateRepresentation(threadId, actorId, interleaving, eventId)
                    if (stateRepresentation != null)
                        execution.add(stateInterleavingEventRepresentation(threadId, stateRepresentation))
                }
            }
        }
        // print the event itself
        when (event) {
            is SwitchEvent -> {
                val reason = if (event.reason.toString().isEmpty()) "" else " (reason: ${event.reason})"
                execution.add(InterleavingEventRepresentation(threadId, EXECUTION_INDENTATION + "switch" + reason))
            }
            is FinishEvent -> {
                execution.add(InterleavingEventRepresentation(threadId,  EXECUTION_INDENTATION + "thread is finished"))
            }
            is PassCodeLocationEvent -> {
                if (isInterestingActor(threadId, actorId, interestingEvents)) {
                    val callStackTrace = event.callStackTrace
                    val compressionPoint = callStackTrace.calculateCompressionPoint(interestingEvents[threadId][actorId])
                    if (compressionPoint == callStackTrace.size) {
                        // no compression
                        execution.add(InterleavingEventRepresentation(threadId, EXECUTION_INDENTATION + event.codeLocation.toString()))
                        val stateRepresentation = nextStateRepresentaton(interleaving, eventId)
                        if (stateRepresentation != null)
                            execution.add(stateInterleavingEventRepresentation(threadId, stateRepresentation))
                    } else {
                        val call = callStackTrace[compressionPoint]
                        val callIdentifier = call.identifier
                        if (callIdentifier in loggedMethodCalls)
                            continue@eventLoop // this method call was already logged
                        loggedMethodCalls += callIdentifier
                        execution.add(InterleavingEventRepresentation(
                                threadId,
                                EXECUTION_INDENTATION + call.codeLocation.toString()
                        ))
                        val stateRepresentation = lastCompressedStateRepresentation(interleaving, eventId, callIdentifier, interestingEvents[threadId][actorId])
                        if (stateRepresentation != null)
                            execution.add(stateInterleavingEventRepresentation(threadId, stateRepresentation))
                    }
                }
            }
            is StateRepresentationEvent -> {
                // state representation event are logged immediately after pass code location events instead
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

private class InterleavingEventRepresentation(val threadId: Int, val representation: String)

/**
 * Events that are considered interesting:
 * 1) All switch events
 * 2) Last events in case of incomplete execution (i.e. because of exception)
 * 3) Events in methods that were suspended
 */
private fun interestingEventStackTraces(
        scenario: ExecutionScenario,
        interleavingEvents: List<InterleavingEvent>,
        lastExecutedActors: IntArray
): Array<Array<List<CallStackTrace>>> = Array(scenario.parallelExecution.size) { threadId ->
    val eventsInThread = interleavingEvents.filter { it.threadId == threadId}
    Array(scenario.parallelExecution[threadId].size) { actorId ->
        val interestingCallStackTraces = mutableListOf<CallStackTrace>()
        interestingCallStackTraces.addAll(
                eventsInThread.filter { it.actorId == actorId }.filterIsInstance<SwitchEvent>().map { it.callStackTrace }
        )
        interestingCallStackTraces.addAll(
                eventsInThread
                        .filter { it.actorId == actorId }
                        .filterIsInstance<PassCodeLocationEvent>()
                        .filter { it.callStackTrace.lastOrNull()?.codeLocation?.returnedValue?.value == COROUTINE_SUSPENDED }
                        .map { it.callStackTrace }
        )
        if (actorId == lastExecutedActors[threadId]) {
            when (val lastEvent = eventsInThread.lastOrNull { it !is StateRepresentationEvent }) {
                is PassCodeLocationEvent -> interestingCallStackTraces.add(lastEvent.callStackTrace)
                else -> {}
            }
        }
        interestingCallStackTraces as List<CallStackTrace>
    }
}

/**
 * Convert events that should be printed to the final form of a matrix of strings
 */
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

/**
 * Calculates how to compress call stack trace.
 * Picks the first different position with all interesting event call stack traces.
 * For example, for call stack trace (a -> b -> c) with an interesting event at (a -> b -> d)
 * compression point will be "c" and its index (2) will be returned.
 */
private fun CallStackTrace.calculateCompressionPoint(interestingEvents: List<CallStackTrace>): Int =
        interestingEvents.map { this.firstDifferPosition(it) }.max()!!

private fun CallStackTrace.firstDifferPosition(callStackTrace: CallStackTrace): Int {
    for (position in indices)
        if (position >= callStackTrace.size || this[position].identifier != callStackTrace[position].identifier)
            return position
    return size
}

private fun getActorRepresentation(
        threadId: Int,
        actorId: Int, scenario:
        ExecutionScenario,
        results: ExecutionResult?,
        isInteresting: Boolean
) = StringBuilder().apply {
    append(scenario.parallelExecution[threadId][actorId].toString())
    if (results != null && !isInteresting)
        append(": ${results.parallelResults[threadId][actorId]}")
}.toString()

private fun isInterestingActor(threadId: Int, actorId: Int, interestingEvents: Array<Array<List<CallStackTrace>>>) =
        interestingEvents[threadId][actorId].isNotEmpty()

/**
 * Returns state representation if there was any immediately after [previousEventPosition].
 */
private fun nextStateRepresentaton(interleavingEvents: List<InterleavingEvent>, previousEventPosition: Int): String? {
    if (previousEventPosition + 1 >= interleavingEvents.size) return null
    val nextEvent = interleavingEvents[previousEventPosition + 1]
    if (nextEvent is StateRepresentationEvent)
        return nextEvent.stateRepresentation
    return null
}

/**
 * Finds last state representation that was made in [callIdentifier] call.
 */
private fun lastCompressedStateRepresentation(interleavingEvents: List<InterleavingEvent>, startPosition: Int, callIdentifier: Int, interestingEvents: List<CallStackTrace>): String? {
    var lastStateRepresentation: String? = null
    loop@for (position in startPosition until interleavingEvents.size) {
        when (val event = interleavingEvents[position]) {
            is PassCodeLocationEvent -> {
                val compressionPoint = event.callStackTrace.calculateCompressionPoint(interestingEvents)
                if (compressionPoint == event.callStackTrace.size || event.callStackTrace[compressionPoint].identifier != callIdentifier)
                    break@loop // compressed method ended
            }
            is StateRepresentationEvent -> lastStateRepresentation = event.stateRepresentation
            else -> break@loop
        }
    }
    return lastStateRepresentation
}

/**
 * Find last state representation for a given [actorId] actor.
 */
private fun lastStateRepresentation(threadId: Int, actorId: Int, interleavingEvents: List<InterleavingEvent>, startPosition: Int): String? {
    var lastStateRepresentation: String? = null
    for (position in startPosition until interleavingEvents.size) {
        val event = interleavingEvents[position]
        if (event.threadId != threadId || event.actorId != actorId)
            return lastStateRepresentation
        if (event is StateRepresentationEvent)
            lastStateRepresentation = event.stateRepresentation
    }
    return lastStateRepresentation
}

private fun stateInterleavingEventRepresentation(threadId: Int, stateRepresentation: String) =
        InterleavingEventRepresentation(threadId, EXECUTION_INDENTATION + "STATE: $stateRepresentation")