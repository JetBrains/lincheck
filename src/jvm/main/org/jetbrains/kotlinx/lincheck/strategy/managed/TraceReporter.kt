/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.DeadlockWithDumpFailure
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import java.util.*
import kotlin.math.*

@Synchronized // we should avoid concurrent executions to keep `objectNumeration` consistent
internal fun StringBuilder.appendTrace(
    failure: LincheckFailure,
    results: ExecutionResult?,
    trace: Trace,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
) {
    val startTraceGraphNode = constructTraceGraph(failure.scenario, results, trace, exceptionStackTraces)

    appendShortTrace(startTraceGraphNode, failure)
    appendExceptionsStackTracesBlock(exceptionStackTraces)
    appendDetailedTrace(startTraceGraphNode, failure)

    objectNumeration.clear() // clear the numeration at the end to avoid memory leaks
}

private fun StringBuilder.appendShortTrace(
    startTraceGraphNode: TraceNode?,
    failure: LincheckFailure
) {
    val traceRepresentation = traceGraphToRepresentationList(startTraceGraphNode, false)
    appendLine(TRACE_TITLE)
    appendTraceRepresentation(failure.scenario, traceRepresentation)
    if (failure is DeadlockWithDumpFailure) {
        appendLine(ALL_UNFINISHED_THREADS_IN_DEADLOCK_MESSAGE)
    }
    appendLine()
}

private fun StringBuilder.appendDetailedTrace(
    startTraceGraphNode: TraceNode?,
    failure: LincheckFailure
) {
    appendLine(DETAILED_TRACE_TITLE)
    val traceRepresentationVerbose = traceGraphToRepresentationList(startTraceGraphNode, true)
    appendTraceRepresentation(failure.scenario, traceRepresentationVerbose)
    if (failure is DeadlockWithDumpFailure) {
        appendLine(ALL_UNFINISHED_THREADS_IN_DEADLOCK_MESSAGE)
    }
}

private fun StringBuilder.appendTraceRepresentation(
    scenario: ExecutionScenario,
    traceRepresentation: List<TraceEventRepresentation>
) {
    val traceRepresentationSplitted = splitToColumns(scenario.nThreads, traceRepresentation)
    with(ExecutionLayout(listOf(), traceRepresentationSplitted, listOf())) {
        appendSeparatorLine()
        appendHeader()
        appendSeparatorLine()
        appendColumns(traceRepresentationSplitted)
        appendSeparatorLine()
    }
}

/**
 * Convert trace events to the final form of a matrix of strings.
 */
private fun splitToColumns(nThreads: Int, traceRepresentation: List<TraceEventRepresentation>): List<List<String>> {
    val result = List(nThreads) { mutableListOf<String>() }
    for (event in traceRepresentation) {
        val columnId = event.iThread
        // write message in an appropriate column
        result[columnId].add(event.representation)
        val neededSize = result[columnId].size
        // establish columns size equals
        for (column in result)
            if (column.size != neededSize)
                column.add("")
    }
    return result
}

/**
 * Constructs a trace graph based on the provided [trace].
 * Returns the node corresponding to the starting trace event.
 *
 * A trace graph consists of two types of edges:
 * `next` edges form a single-directed list in which the order of events is the same as in [trace].
 * `internalEvents` edges form a directed forest.
 */
private fun constructTraceGraph(scenario: ExecutionScenario, results: ExecutionResult?, trace: Trace, exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>): TraceNode? {
    val tracePoints = trace.trace
    // last events that were executed for each thread. It is either thread finish events or events before crash
    val lastExecutedEvents = IntArray(scenario.nThreads) { iThread ->
        tracePoints.mapIndexed { i, e -> Pair(i, e) }.lastOrNull { it.second.iThread == iThread }?.first ?: -1
    }
    // last actor that was handled for each thread
    val lastHandledActor = IntArray(scenario.nThreads) { -1 }
    // actor nodes for each actor in each thread
    val actorNodes = Array(scenario.nThreads) { i ->
        Array<ActorNode?>(scenario.threads[i].size) { null }
    }
    // call nodes for each method call
    val callNodes = mutableMapOf<Int, CallNode>()
    // all trace nodes in order corresponding to `tracePoints`
    val traceGraphNodes = mutableListOf<TraceNode>()

    for (eventId in tracePoints.indices) {
        val event = tracePoints[eventId]
        val iThread = event.iThread
        val actorId = event.actorId
        // add all actors that started since the last event
        while (lastHandledActor[iThread] < min(actorId, scenario.threads[iThread].lastIndex)) {
            val nextActor = ++lastHandledActor[iThread]
            // create new actor node actor
            val actorNode = traceGraphNodes.createAndAppend { lastNode ->
                ActorNode(
                    iThread = iThread,
                    last = lastNode,
                    callDepth = 0,
                    actor = scenario.threads[iThread][nextActor],
                    resultRepresentation = results[iThread, nextActor]
                        ?.let { actorNodeResultRepresentation(it, exceptionStackTraces) }
                )
            }
            actorNodes[iThread][nextActor] = actorNode
        }
        // add the event
        var innerNode: TraceInnerNode = actorNodes[iThread][actorId]!!
        for (call in event.callStackTrace) {
            val callId = call.identifier
            // Switch events that happen as a first event of the method are lifted out of the method in the trace
            if (!callNodes.containsKey(callId) && event is SwitchEventTracePoint) break
            val callNode = callNodes.computeIfAbsent(callId) {
                // create a new call node if needed
                val result = traceGraphNodes.createAndAppend { lastNode ->
                    CallNode(iThread, lastNode, innerNode.callDepth + 1, call.call)
                }
                // make it a child of the previous node
                innerNode.addInternalEvent(result)
                result
            }
            innerNode = callNode
        }
        val isLastExecutedEvent = eventId == lastExecutedEvents[iThread]
        val node = traceGraphNodes.createAndAppend { lastNode ->
            TraceLeafEvent(iThread, lastNode, innerNode.callDepth + 1, event, isLastExecutedEvent)
        }
        innerNode.addInternalEvent(node)
    }
    // add an ActorResultNode to each actor, because did not know where actor ends before
    for (iThread in actorNodes.indices) {
        for (actorId in actorNodes[iThread].indices) {
            var actorNode = actorNodes[iThread][actorId]
            val actorResult = results[iThread, actorId]
            // in case of empty trace, we want to show at least the actor nodes themselves;
            // however, no actor nodes will be created by the code above, so we need to create them explicitly here.
            if (actorNode == null && actorResult != null) {
                val lastNode = actorNodes[iThread].getOrNull(actorId - 1)?.lastInternalEvent
                actorNode = ActorNode(
                    iThread = iThread,
                    last = lastNode,
                    callDepth = 0,
                    actor = scenario.threads[iThread][actorId],
                    resultRepresentation = actorNodeResultRepresentation(actorResult, exceptionStackTraces)
                )
                actorNodes[iThread][actorId] = actorNode
                traceGraphNodes.add(actorNode)
            }
            if (actorNode == null)
                continue
            // insert an ActorResultNode between the last actor event and the next event after it
            val lastEvent = actorNode.lastInternalEvent
            val lastEventNext = lastEvent.next
            val result = results[iThread, actorId]
            val resultRepresentation = result?.let { resultRepresentation(result, exceptionStackTraces) }
            val resultNode = ActorResultNode(iThread, lastEvent, actorNode.callDepth + 1, resultRepresentation)
            actorNode.addInternalEvent(resultNode)
            resultNode.next = lastEventNext
        }
    }
    return traceGraphNodes.firstOrNull()
}

private operator fun ExecutionResult?.get(iThread: Int, actorId: Int): Result? =
    this?.threadsResults?.get(iThread)?.get(actorId)

/**
 * Create a new trace node and add it to the end of the list.
 */
private fun <T : TraceNode> MutableList<TraceNode>.createAndAppend(constructor: (lastNode: TraceNode?) -> T): T =
    constructor(lastOrNull()).also { add(it) }

private fun traceGraphToRepresentationList(
    startNode: TraceNode?,
    verboseTrace: Boolean
): List<TraceEventRepresentation> {
    var curNode: TraceNode? = startNode
    val traceRepresentation = mutableListOf<TraceEventRepresentation>()
    while (curNode != null) {
        curNode = curNode.addRepresentationTo(traceRepresentation, verboseTrace)
    }
    return traceRepresentation
}

private sealed class TraceNode(
    protected val iThread: Int,
    last: TraceNode?,
    val callDepth: Int // for tree indentation
) {
    // `next` edges form an ordered single-directed event list
    var next: TraceNode? = null

    // `lastInternalEvent` helps to skip internal events if an actor or a method call can be compressed
    abstract val lastInternalEvent: TraceNode

    // `lastState` helps to find the last state needed for the compression
    abstract val lastState: String?

    // whether the internal events should be reported
    abstract fun shouldBeExpanded(verboseTrace: Boolean): Boolean

    init {
        last?.let {
            it.next = this
        }
    }

    /**
     * Adds this node representation to the [traceRepresentation] and returns the next node to be processed.
     */
    abstract fun addRepresentationTo(
        traceRepresentation: MutableList<TraceEventRepresentation>,
        verboseTrace: Boolean
    ): TraceNode?
}

private class TraceLeafEvent(
    iThread: Int,
    last: TraceNode?,
    callDepth: Int,
    private val event: TracePoint,
    private val lastExecutedEvent: Boolean = false
) : TraceNode(iThread, last, callDepth) {

    override val lastState: String? =
        if (event is StateRepresentationTracePoint) event.stateRepresentation else null

    override val lastInternalEvent: TraceNode = this

    private val TracePoint.isBlocking: Boolean get() = when (this) {
        is MonitorEnterTracePoint, is WaitTracePoint, is ParkTracePoint -> true
        else -> false
    }

    override fun shouldBeExpanded(verboseTrace: Boolean): Boolean {
        return (lastExecutedEvent && event.isBlocking)
                || event is SwitchEventTracePoint
                || event is ObstructionFreedomViolationExecutionAbortTracePoint
                || verboseTrace
    }

    override fun addRepresentationTo(
        traceRepresentation: MutableList<TraceEventRepresentation>,
        verboseTrace: Boolean
    ): TraceNode? {
        val representation = traceIndentation() + event.toString()
        traceRepresentation.add(TraceEventRepresentation(iThread, representation))
        return next
    }
}

private abstract class TraceInnerNode(iThread: Int, last: TraceNode?, callDepth: Int) :
    TraceNode(iThread, last, callDepth) {
    override val lastState: String?
        get() = internalEvents.map { it.lastState }.lastOrNull { it != null }
    override val lastInternalEvent: TraceNode
        get() = if (internalEvents.isEmpty()) this else internalEvents.last().lastInternalEvent

    override fun shouldBeExpanded(verboseTrace: Boolean) =
        internalEvents.any {
            it.shouldBeExpanded(verboseTrace)
        }

    private val internalEvents = mutableListOf<TraceNode>()

    fun addInternalEvent(node: TraceNode) {
        internalEvents.add(node)
    }
}

private class CallNode(
    iThread: Int,
    last: TraceNode?,
    callDepth: Int,
    private val call: MethodCallTracePoint
) : TraceInnerNode(iThread, last, callDepth) {
    // suspended method contents should be reported
    override fun shouldBeExpanded(verboseTrace: Boolean): Boolean {
        return call.wasSuspended || super.shouldBeExpanded(verboseTrace)
    }

    override fun addRepresentationTo(
        traceRepresentation: MutableList<TraceEventRepresentation>,
        verboseTrace: Boolean
    ): TraceNode? =
        if (!shouldBeExpanded(verboseTrace)) {
            traceRepresentation.add(TraceEventRepresentation(iThread, traceIndentation() + "$call"))
            lastState?.let { traceRepresentation.add(stateEventRepresentation(iThread, it)) }
            lastInternalEvent.next
        } else {
            traceRepresentation.add(TraceEventRepresentation(iThread, traceIndentation() + "$call"))
            next
        }
}

private class ActorNode(
    iThread: Int,
    last: TraceNode?,
    callDepth: Int,
    private val actor: Actor,
    private val resultRepresentation: String?
) : TraceInnerNode(iThread, last, callDepth) {
    override fun addRepresentationTo(
        traceRepresentation: MutableList<TraceEventRepresentation>,
        verboseTrace: Boolean
    ): TraceNode? {
        val actorRepresentation = "$actor" + if (resultRepresentation != null) ": $resultRepresentation" else ""
        traceRepresentation.add(TraceEventRepresentation(iThread, actorRepresentation))
        return if (!shouldBeExpanded(verboseTrace)) {
            lastState?.let { traceRepresentation.add(stateEventRepresentation(iThread, it)) }
            lastInternalEvent.next
        } else {
            next
        }
    }
}

private class ActorResultNode(
    iThread: Int,
    last: TraceNode?,
    callDepth: Int,
    private val resultRepresentation: String?
) : TraceNode(iThread, last, callDepth) {
    override val lastState: String? = null
    override val lastInternalEvent: TraceNode = this
    override fun shouldBeExpanded(verboseTrace: Boolean): Boolean = false

    override fun addRepresentationTo(
        traceRepresentation: MutableList<TraceEventRepresentation>,
        verboseTrace: Boolean
    ): TraceNode? {
        if (resultRepresentation != null)
            traceRepresentation.add(TraceEventRepresentation(iThread, traceIndentation() + "result: $resultRepresentation"))
        return next
    }
}

private const val TRACE_INDENTATION = "  "

private fun TraceNode.traceIndentation() = TRACE_INDENTATION.repeat(callDepth)

private fun TraceNode.stateEventRepresentation(iThread: Int, stateRepresentation: String) =
    TraceEventRepresentation(iThread, traceIndentation() + "STATE: $stateRepresentation")

private class TraceEventRepresentation(val iThread: Int, val representation: String)

// Should be called only during `appendTrace` invocation
internal fun getObjectNumber(clazz: Class<Any>, obj: Any): Int = objectNumeration
    .computeIfAbsent(clazz) { IdentityHashMap() }
    .computeIfAbsent(obj) { 1 + objectNumeration[clazz]!!.size }

private val objectNumeration = WeakHashMap<Class<Any>, MutableMap<Any, Int>>()

const val TRACE_TITLE = "The following interleaving leads to the error:"
const val DETAILED_TRACE_TITLE = "Detailed trace:"
private const val ALL_UNFINISHED_THREADS_IN_DEADLOCK_MESSAGE = "All unfinished threads are in deadlock"
