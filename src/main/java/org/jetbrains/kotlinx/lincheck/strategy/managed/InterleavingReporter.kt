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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import org.jetbrains.kotlinx.lincheck.printInColumnsCustom
import kotlin.math.min

private const val INTERLEAVING_INDENTATION = "  "

internal fun StringBuilder.appendInterleaving(
    scenario: ExecutionScenario,
    results: ExecutionResult?,
    interleavingEvents: List<InterleavingEvent>
) {
    // clear object numeration that is used by `CodePoint.toString` for better representation
    objectNumeration.clear()
    val graphStart = constructInterleavingGraph(scenario, results, interleavingEvents)
    val interleaving = interleavingGraphToRepresentation(graphStart)
    val interleavingRepresentation = splitToColumns(scenario.threads, interleaving)
    appendln("Parallel part interleaving:")
    append(printInColumnsCustom(interleavingRepresentation) {
        StringBuilder().apply {
            for (i in it.indices) {
                append(if (i == 0) "| " else " | ")
                append(it[i])
            }
            append(" |")
        }.toString()
    })
}

/**
 * Convert interleaving events to the final form of a matrix of strings
 */
private fun splitToColumns(nThreads: Int, interleaving: List<InterleavingEventRepresentation>): List<List<String>> {
    val result = List(nThreads) { mutableListOf<String>() }
    for (message in interleaving) {
        val columnId = message.iThread
        // write message in an appropriate column
        result[columnId].add(message.representation)
        val neededSize = result[columnId].size
        for (column in result)
            if (column.size != neededSize)
                column.add("")
    }
    return result
}

/**
 * Construct an interleaving graph based on provided [interleavingEvents].
 * Returns the node corresponding to the first interleaving event.
 * An interleaving graph consists of two types of edges:
 * `next` edges form a single-directed list in which the order of events is the same as in [interleavingEvents].
 * `internalEvents` edges form a directed forest.
 * The root of each tree is an actor.
 * Its children are events and method invocations.
 */
private fun constructInterleavingGraph(scenario: ExecutionScenario, results: ExecutionResult?, interleavingEvents: List<InterleavingEvent>): InterleavingNode? {
    // last events that were executed for each thread. It is either thread finish events or events before crash
    val lastExecutedEvents = IntArray(scenario.threads) { iThread ->
        interleavingEvents.mapIndexed { i, e -> Pair(i, e) }.lastOrNull { it.second.iThread == iThread }?.first ?: -1
    }
    // last actor that was handled for each thread
    val lastHandledActor = IntArray(scenario.threads) { -1 }
    // actor nodes for each actor in each thread
    val actorNodes = Array(scenario.threads) { Array<ActorNode?>(scenario.parallelExecution[it].size) { null } }
    // call nodes for each method call
    val callNodes = mutableMapOf<Int, CallNode>()
    // all interleaving nodes in order corresponding to interleavingEvents
    val interleavingNodes = mutableListOf<InterleavingNode>()

    for (eventId in interleavingEvents.indices) {
        val event = interleavingEvents[eventId]
        val iThread = event.iThread
        val actorId = event.actorId
        // add all actors that started since the last event
        while (lastHandledActor[iThread] < min(actorId, scenario.parallelExecution[iThread].lastIndex)) {
            val nextActor = ++lastHandledActor[iThread]
            // create new actor node actor
            val actorNode = interleavingNodes.allocate { ActorNode(iThread, it, scenario.parallelExecution[iThread][nextActor], results[iThread, nextActor]) }
            actorNodes[iThread][nextActor] = actorNode
            interleavingNodes.add(actorNode)
        }
        // add the event
        when (event) {
            // simpler code for FinishEvent, because it does not have actorId or callStackTrace
            is FinishEvent -> interleavingNodes.allocate { InterleavingLeafEvent(iThread, it, event) }
            else -> {
                var innerNode: InterleavingInnerNode = actorNodes[iThread][actorId]!!
                for (call in event.callStackTrace) {
                    val callId = call.identifier
                    val callNode = callNodes.computeIfAbsent(callId) {
                        // create a new call node if needed
                        val result = interleavingNodes.allocate { CallNode(iThread, it, call.call) }
                        // make it a child of the previous node
                        innerNode.addInternalEvent(result)
                        result
                    }
                    innerNode = callNode
                }
                val isLastExecutedEvent = eventId == lastExecutedEvents[iThread]
                val node = interleavingNodes.allocate { InterleavingLeafEvent(iThread, it, event, isLastExecutedEvent) }
                innerNode.addInternalEvent(node)
            }
        }
    }
    // add an ActorResultNode to each actor, because did not know where actor ends before
    for (iThread in actorNodes.indices)
        for (actorId in actorNodes[iThread].indices) {
            actorNodes[iThread][actorId]?.let {
                // insert an ActorResultNode between the last actor event and the next event after it
                val lastEvent = it.lastInternalEvent
                val lastEventNext = lastEvent.next
                val resultNode = ActorResultNode(iThread, lastEvent, results[iThread, actorId])
                it.addInternalEvent(resultNode)
                resultNode.next = lastEventNext
            }
        }
    return interleavingNodes.firstOrNull()
}

private operator fun ExecutionResult?.get(iThread: Int, actorId: Int): Result? =
    if (this == null) null else this.parallelResults[iThread][actorId]

/**
 * Create a new interleaving node and add it to the end of the list.
 */
private fun <T : InterleavingNode> MutableList<InterleavingNode>.allocate(constructor: (lastNode: InterleavingNode?) -> T): T {
    val node = constructor(lastOrNull())
    add(node)
    return node
}

private fun interleavingGraphToRepresentation(firstNode: InterleavingNode?): List<InterleavingEventRepresentation> {
    var currentNode: InterleavingNode? = firstNode
    val interleaving = mutableListOf<InterleavingEventRepresentation>()
    while (currentNode != null) {
        currentNode = currentNode.addRepresentationTo(interleaving)
    }
    return interleaving
}

private sealed class InterleavingNode(protected val iThread: Int, last: InterleavingNode?) {
    // `next` edges form an ordered single-directed event list
    var next: InterleavingNode? = null
    // `lastInternalEvent` helps to skip internal events if an actor or a method call can be compressed
    abstract val lastInternalEvent: InterleavingNode
    // `lastState` helps to find the last state needed for the compression
    abstract val lastState: String?
    // whether the internal events should be reported
    abstract val shouldBeExpanded: Boolean

    init {
        last?.let {
            it.next = this
        }
    }

    /**
     * Adds node representation and returns next node to be processed.
     */
    abstract fun addRepresentationTo(interleaving: MutableList<InterleavingEventRepresentation>): InterleavingNode?
}

private class InterleavingLeafEvent(iThread: Int, last: InterleavingNode?, private val event: InterleavingEvent, lastExecutedEvent: Boolean = false)
    : InterleavingNode(iThread, last) {
    override val lastState: String? = if (event is StateRepresentationEvent) event.stateRepresentation else null
    override val lastInternalEvent: InterleavingNode = this
    override val shouldBeExpanded: Boolean = lastExecutedEvent || event is SwitchEvent // switch events should be reported

    override fun addRepresentationTo(interleaving: MutableList<InterleavingEventRepresentation>): InterleavingNode? {
        val representation = INTERLEAVING_INDENTATION + when(event) {
            is SwitchEvent -> {
                val reason = event.reason.toString()
                "switch" + if (reason.isEmpty()) "" else " (reason: $reason)"
            }
            is PassCodeLocationEvent -> event.codePoint.toString()
            is StateRepresentationEvent -> "STATE: " + event.stateRepresentation
            is FinishEvent -> "thread is finished"
        }
        interleaving.add(InterleavingEventRepresentation(iThread, representation))
        return next
    }
}

private abstract class InterleavingInnerNode(iThread: Int, last: InterleavingNode?) : InterleavingNode(iThread, last) {
    override val lastState: String?
        get() {
            for (event in internalEvents.reversed())
                if (event.lastState != null)
                    return event.lastState
            return null
        }
    override val lastInternalEvent: InterleavingNode
        get() = if (internalEvents.isEmpty()) this else internalEvents.last().lastInternalEvent
    override val shouldBeExpanded: Boolean by lazy { internalEvents.any { it.shouldBeExpanded } }
    private val internalEvents = mutableListOf<InterleavingNode>()

    fun addInternalEvent(node: InterleavingNode) {
        internalEvents.add(node)
    }
}

private class CallNode(iThread: Int, last: InterleavingNode?, private val call: MethodCallCodePoint) : InterleavingInnerNode(iThread, last) {
    // suspended method contents should be reported
    override val shouldBeExpanded: Boolean by lazy { call.wasSuspended || super.shouldBeExpanded }

    override fun addRepresentationTo(interleaving: MutableList<InterleavingEventRepresentation>): InterleavingNode? =
        if (!shouldBeExpanded) {
            interleaving.add(InterleavingEventRepresentation(iThread, INTERLEAVING_INDENTATION + "$call"))
            lastState?.let { interleaving.add(stateEventRepresentation(iThread, it)) }
            lastInternalEvent.next
        } else {
            next
        }
}

private class ActorNode(iThread: Int, last: InterleavingNode?, private val actor: Actor, private val result: Result?) : InterleavingInnerNode(iThread, last) {
    override fun addRepresentationTo(interleaving: MutableList<InterleavingEventRepresentation>): InterleavingNode? =
        if (!shouldBeExpanded) {
            val representation = "$actor" + if (result != null) ": $result" else ""
            interleaving.add(InterleavingEventRepresentation(iThread, representation))
            lastState?.let { interleaving.add(stateEventRepresentation(iThread, it)) }
            lastInternalEvent.next
        } else {
            interleaving.add(InterleavingEventRepresentation(iThread, "$actor"))
            next
        }
}

private class ActorResultNode(iThread: Int, last: InterleavingNode?, private val result: Result?) : InterleavingNode(iThread, last) {
    override val lastState: String? = null
    override val lastInternalEvent: InterleavingNode = this
    override val shouldBeExpanded: Boolean = false

    override fun addRepresentationTo(interleaving: MutableList<InterleavingEventRepresentation>): InterleavingNode? {
        if (result == Cancelled)
            interleaving.add(InterleavingEventRepresentation(iThread, INTERLEAVING_INDENTATION + "CONTINUATION CANCELLED"))
        if (result != null)
            interleaving.add(InterleavingEventRepresentation(iThread, INTERLEAVING_INDENTATION + "result: $result"))
        return next
    }
}

private fun stateEventRepresentation(iThread: Int, stateRepresentation: String) =
    InterleavingEventRepresentation(iThread, INTERLEAVING_INDENTATION + "STATE: $stateRepresentation")

private class InterleavingEventRepresentation(val iThread: Int, val representation: String)