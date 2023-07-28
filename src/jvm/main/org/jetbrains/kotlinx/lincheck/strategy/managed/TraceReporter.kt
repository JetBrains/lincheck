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
private fun constructTraceGraph(
    scenario: ExecutionScenario,
    results: ExecutionResult?,
    trace: Trace,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
): TraceNode? {
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
    val indentationFactoryProducer = IndentationFactoryProducer()

    for (eventId in tracePoints.indices) {
        val event = tracePoints[eventId]
        val iThread = event.iThread
        val actorId = event.actorId
        val callStackTrace = eventStackTrace(event)
        // add all actors that started since the last event
        while (lastHandledActor[iThread] < min(actorId, scenario.threads[iThread].lastIndex)) {
            val nextActor = ++lastHandledActor[iThread]
            // create new actor node actor
            val actorNode = traceGraphNodes.createAndAppend { lastNode ->
                ActorNode(
                    iThread = iThread,
                    last = lastNode,
                    callDepth = 0,
                    indentationFactory = indentationFactoryProducer.justSpacesIndentFactory(0),
                    actor = scenario.threads[iThread][nextActor],
                    resultRepresentation = results[iThread, nextActor]
                        ?.let { actorNodeResultRepresentation(it, exceptionStackTraces) }
                )
            }
            actorNodes[iThread][nextActor] = actorNode
        }
        // add the event
        var innerNode: TraceInnerNode = actorNodes[iThread][actorId]!!
        for (call in callStackTrace) {
            val callId = call.identifier
            // Switch events that happen as a first event of the method are lifted out of the method in the trace
            if (!callNodes.containsKey(callId) && event is SwitchEventTracePoint) break
            val callNode = callNodes.computeIfAbsent(callId) {
                // create a new call node if needed
                val result = traceGraphNodes.createAndAppend { lastNode ->
                    val callDepth = innerNode.callDepth + 1
                    val factory = if (event is SpinCycleStartTracePoint) {
                        indentationFactoryProducer.justSpacesIndentFactory(callDepth)
                    } else {
                        indentationFactoryProducer.next(event, callDepth)
                    }
                    CallNode(iThread, lastNode, factory, callDepth, call.call)
                }
                // make it a child of the previous node
                innerNode.addInternalEvent(result)
                result
            }
            innerNode = callNode
        }
        val isLastExecutedEvent = eventId == lastExecutedEvents[iThread]
        val currentCallDepth = innerNode.callDepth + 1
        if (event is SwitchEventTracePoint && event.isSpinLock) {
            // In case of a spin lock, we add comment to the switch node
            val commentNode = traceGraphNodes.createAndAppend { lastNode ->
                TraceLeafCommentEvent(iThread = iThread, last = lastNode, indentationFactory = indentationFactoryProducer.next(event, currentCallDepth), callDepth = currentCallDepth, comment = preSwitchSpinCycleCommentMessage(event.reason))
            }
            innerNode.addInternalEvent(commentNode)
            val switchNode = traceGraphNodes.createAndAppend { lastNode ->
                TraceLeafEvent(iThread, lastNode, indentationFactoryProducer.justSpacesIndentFactory(currentCallDepth), currentCallDepth, event, isLastExecutedEvent)
            }
            innerNode.addInternalEvent(switchNode)
        } else {
            val node = traceGraphNodes.createAndAppend { lastNode ->
                TraceLeafEvent(iThread, lastNode, indentationFactoryProducer.next(event, currentCallDepth), currentCallDepth, event, isLastExecutedEvent)
            }
            innerNode.addInternalEvent(node)
        }
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
                    indentationFactory = indentationFactoryProducer.justSpacesIndentFactory(0),
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
            val resultNode = ActorResultNode(
                iThread = iThread,
                last = lastEvent,
                indentationFactory = indentationFactoryProducer.justSpacesIndentFactory(actorNode.callDepth + 1),
                callDepth = actorNode.callDepth + 1,
                resultRepresentation = resultRepresentation
            )
            actorNode.addInternalEvent(resultNode)
            resultNode.next = lastEventNext
        }
    }
    return traceGraphNodes.firstOrNull()
}

private val SwitchEventTracePoint.isSpinLock: Boolean get() = this.reason == SwitchReason.ACTIVE_LOCK || this.reason == SwitchReason.ACTIVE_RECURSIVE_LOCK
private fun preSwitchSpinCycleCommentMessage(switchReason: SwitchReason): String {
    return when (switchReason) {
        SwitchReason.ACTIVE_LOCK -> "/* The next call would cause infinite spin-cycle, switch to avoid it */"
        SwitchReason.ACTIVE_RECURSIVE_LOCK -> "/* The next call would cause infinite recursion spin-lock, switch to avoid it */"
        else -> error("Not a spin lock switch")
    }
}

private fun eventStackTrace(event: TracePoint): CallStackTrace {
    if (event !is SpinCycleStartTracePoint) {
        return event.callStackTrace
    }
    val stackTrace = event.callStackTrace.take(event.startDepthCall)

    return if (event.isRecursive) {
        // if call is recursive call then the previous event must be some method call -
        // then we lift start point under it
        stackTrace.dropLast(1)
    } else stackTrace
}

/**
 * Indentation factory that encapsulates logic of indents creation
 */
private sealed class TraceNodeIndentationFactory(
    protected val callDepth: Int
) {
    abstract fun makeIndent(): String
}

/**
 * Crates [TraceNodeIndentationFactory] instances and implements the logic of arrows drawing between spin cycle
 * start and switch after it.
 *
 * When [SpinCycleStartTracePoint] is met is records the depth of its call to return indents factories
 * that draw arrow parts. After [SwitchEventTracePoint] it turn to default mode and no arrows will be added to
 * subsequent trace nodes until new [SpinCycleStartTracePoint] and vice versa.
 *
 * An arrow is drawn from its end to start, in a backward direction.
 * Example:
 * ┌—>  b()                                 // This part is drawn by CallDepthWithArrowEndIndentationFactory
 * |    a()                                 // This part is drawn by CallDepthWithArrowBodyIndentationFactory
 * └————— /* recursive spin lock */         // This part is drawn by CallDepthWithArrowStartIndentationFactory
 *
 * If we're not drawing any arrow now, CallDepthIndentationFactory is used.
 */
private class IndentationFactoryProducer {

    /**
     * In one specific case, we may have not enough space for arrow: when spin cycle starts
     * from the very first execution of an actor.
     * Arrow end required 4 symbols, but in that case we have only 2, between call node text and table border.
     * To work around this problem, we maintain [extraPrefixIndents] field to add extra prefix spaces if
     * we need additional space.
     */
    private var extraPrefixIndents: Int = 0

    /**
     * Current arrow depth. `null` if no arrow is required to draw now.
     */
    private var arrowDepth: Int? = null

    /**
     * We place an arrow end near the first spin cycle event, while [SpinCycleStartTracePoint] is always the previous one.
     * We maintain information about whether the previous event was [SpinCycleStartTracePoint] to determine should
     * we start drawing an arrow from the next event.
     */
    private var nextEventIsArrowEnd: Boolean = false
    fun next(event: TracePoint, callDepth: Int): TraceNodeIndentationFactory {
        return when (event) {
            is SpinCycleStartTracePoint -> {
                // Parallel arrows are not allowed
                check(arrowDepth == null)
                arrowDepth = callDepth
                // In the lack of space for arrow, we add extra spaces. See extraPrefixIndents documentation.
                if (arrowDepth == 1) {
                    extraPrefixIndents = 1
                }
                // Record that we should start to add arrow parts from the next event
                nextEventIsArrowEnd = true
                // For the comment event return default factory without arrows
                CallDepthIndentationFactory(callDepth)
            }

            is SwitchEventTracePoint -> {
                if (event.reason != SwitchReason.ACTIVE_RECURSIVE_LOCK && event.reason != SwitchReason.ACTIVE_LOCK) {
                    return CallDepthIndentationFactory(callDepth)
                }
                // If the spin lock switch event is present - there must be an arrow from the beginning if the cycle
                val arrow = arrowDepth ?: error("Incorrect trace state")
                // No arrow is needed further as cycle is over
                arrowDepth = null
                CallDepthWithArrowStartIndentationFactory(callDepth, arrow)
            }
            is ObstructionFreedomViolationExecutionAbortTracePoint -> {
                // If the spin lock switch event is present - there must be an arrow from the beginning if the cycle
                val arrow = arrowDepth ?: return CallDepthIndentationFactory(callDepth) // not an active lock
                // No arrow is needed further as cycle is over
                arrowDepth = null
                CallDepthWithArrowStartIndentationFactory(callDepth, arrow)
            }

            else -> {
                // If the previous event was SpinCycleStartTracePoint, we should start drawing arrow
                if (nextEventIsArrowEnd) {
                    nextEventIsArrowEnd = false
                    return CallDepthWithArrowEndIndentationFactory(callDepth)
                }
                // If arrow is present draw its body, otherwise just spaces
                arrowDepth?.let { CallDepthWithArrowBodyIndentationFactory(callDepth, it) }
                    ?: CallDepthIndentationFactory(callDepth)
            }

        }
    }

    fun justSpacesIndentFactory(callDepth: Int) = CallDepthIndentationFactory(callDepth)

    /**
     * Draws the beginning of an arrow after some spaces.
     */
    private inner class CallDepthWithArrowStartIndentationFactory(
        callDepth: Int,
        private val arrowDepth: Int
    ) : TraceNodeIndentationFactory(callDepth) {
        override fun makeIndent(): String =
            TRACE_INDENTATION.repeat(max(0, arrowDepth - 2 + extraPrefixIndents)) + "└╶" + "╶╶".repeat(max(0, callDepth - arrowDepth + 1))
    }

    /**
     * Draws a body stick of an arrow inside space indentations before trace event.
     */
    private inner class CallDepthWithArrowBodyIndentationFactory(
        callDepth: Int,
        private val arrowDepth: Int
    ) : TraceNodeIndentationFactory(callDepth) {
        override fun makeIndent() =
            TRACE_INDENTATION.repeat(arrowDepth - 2 + extraPrefixIndents) + "| " + TRACE_INDENTATION.repeat(callDepth - arrowDepth + 1)
    }

    /**
     * Draws the end of an arrow after some spaces.
     */
    inner class CallDepthWithArrowEndIndentationFactory(callDepth: Int) : TraceNodeIndentationFactory(callDepth) {
        override fun makeIndent() = TRACE_INDENTATION.repeat(callDepth - 2 + extraPrefixIndents) + "┌╶" + "> "
    }

    /**
     * Just adds some spaces before trace event to maintain tree structure.
     */
    inner class CallDepthIndentationFactory(callDepth: Int) : TraceNodeIndentationFactory(callDepth) {
        override fun makeIndent(): String = TRACE_INDENTATION.repeat(callDepth + extraPrefixIndents)
    }

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
    val indentationFactory: TraceNodeIndentationFactory, // for tree indentation
    val callDepth: Int
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
    indentationFactory: TraceNodeIndentationFactory,
    callDepth: Int,
    private val event: TracePoint,
    private val lastExecutedEvent: Boolean = false
) : TraceNode(iThread, last, indentationFactory, callDepth) {

    override val lastState: String? =
        if (event is StateRepresentationTracePoint) event.stateRepresentation else null

    override val lastInternalEvent: TraceNode = this

    private val TracePoint.isBlocking: Boolean
        get() = when (this) {
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
        val representation = indentationFactory.makeIndent() + event.toString()
        traceRepresentation.add(TraceEventRepresentation(iThread, representation))
        return next
    }
}

private class TraceLeafCommentEvent(
    iThread: Int,
    last: TraceNode?,
    indentationFactory: TraceNodeIndentationFactory,
    callDepth: Int,
    private val comment: String
) : TraceNode(iThread, last, indentationFactory, callDepth) {


    override val lastInternalEvent: TraceNode get() = this
    override val lastState: String? get() = null

    override fun shouldBeExpanded(verboseTrace: Boolean): Boolean = true

    override fun addRepresentationTo(
        traceRepresentation: MutableList<TraceEventRepresentation>,
        verboseTrace: Boolean
    ): TraceNode? {
        val representation = indentationFactory.makeIndent() + comment
        traceRepresentation.add(TraceEventRepresentation(iThread, representation))
        return next
    }

}

private abstract class TraceInnerNode(
    iThread: Int,
    last: TraceNode?,
    callDepth: Int,
    indentationFactory: TraceNodeIndentationFactory
) : TraceNode(iThread, last, indentationFactory, callDepth) {
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
    indentationFactory: TraceNodeIndentationFactory,
    callDepth: Int,
    private val call: MethodCallTracePoint
) : TraceInnerNode(iThread, last, callDepth, indentationFactory) {
    // suspended method contents should be reported
    override fun shouldBeExpanded(verboseTrace: Boolean): Boolean {
        return call.wasSuspended || super.shouldBeExpanded(verboseTrace)
    }

    override fun addRepresentationTo(
        traceRepresentation: MutableList<TraceEventRepresentation>,
        verboseTrace: Boolean
    ): TraceNode? =
        if (!shouldBeExpanded(verboseTrace)) {
            traceRepresentation.add(TraceEventRepresentation(iThread, indentationFactory.makeIndent() + "$call"))
            lastState?.let { traceRepresentation.add(stateEventRepresentation(iThread, it)) }
            lastInternalEvent.next
        } else {
            traceRepresentation.add(TraceEventRepresentation(iThread, indentationFactory.makeIndent() + "$call"))
            next
        }
}

private class ActorNode(
    iThread: Int,
    last: TraceNode?,
    indentationFactory: TraceNodeIndentationFactory,
    callDepth: Int,
    private val actor: Actor,
    private val resultRepresentation: String?
) : TraceInnerNode(iThread, last, callDepth, indentationFactory) {
    override fun addRepresentationTo(
        traceRepresentation: MutableList<TraceEventRepresentation>,
        verboseTrace: Boolean
    ): TraceNode? {
        val actorRepresentation =
            indentationFactory.makeIndent() + "$actor" + if (resultRepresentation != null) ": $resultRepresentation" else ""
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
    indentationFactory: TraceNodeIndentationFactory,
    callDepth: Int,
    private val resultRepresentation: String?
) : TraceNode(iThread, last, indentationFactory, callDepth) {
    override val lastState: String? = null
    override val lastInternalEvent: TraceNode = this
    override fun shouldBeExpanded(verboseTrace: Boolean): Boolean = false

    override fun addRepresentationTo(
        traceRepresentation: MutableList<TraceEventRepresentation>,
        verboseTrace: Boolean
    ): TraceNode? {
        if (resultRepresentation != null)
            traceRepresentation.add(
                TraceEventRepresentation(
                    iThread = iThread,
                    representation = indentationFactory.makeIndent() + "result: $resultRepresentation"
                )
            )
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
