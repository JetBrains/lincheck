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
import org.jetbrains.kotlinx.lincheck.runner.ExecutionPart
import org.jetbrains.kotlinx.lincheck.strategy.DeadlockOrLivelockFailure
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.strategy.ValidationFailure
import java.util.*
import kotlin.math.*

@Synchronized // we should avoid concurrent executions to keep `objectNumeration` consistent
internal fun StringBuilder.appendTrace(
    failure: LincheckFailure,
    results: ExecutionResult?,
    trace: Trace,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
) {
    // reset objects numeration
    cleanObjectNumeration()

    val startTraceGraphNode = constructTraceGraph(failure, results, trace, exceptionStackTraces)

    appendShortTrace(startTraceGraphNode, failure)
    appendExceptionsStackTracesBlock(exceptionStackTraces)
    appendDetailedTrace(startTraceGraphNode, failure)

    // clear the numeration at the end to avoid memory leaks
    cleanObjectNumeration()
}

/**
 * @param sectionsFirstNodes a list of first nodes in each scenario section
 */
private fun StringBuilder.appendShortTrace(
    sectionsFirstNodes: List<TraceNode>,
    failure: LincheckFailure
) {
    val traceRepresentation = traceGraphToRepresentationList(sectionsFirstNodes, false)
    appendLine(TRACE_TITLE)
    appendTraceRepresentation(failure.scenario, traceRepresentation)
    if (failure is DeadlockOrLivelockFailure) {
        appendLine(ALL_UNFINISHED_THREADS_IN_DEADLOCK_MESSAGE)
    }
    appendLine()
}

/**
 * @param sectionsFirstNodes a list of first nodes in each scenario section
 */
private fun StringBuilder.appendDetailedTrace(
    sectionsFirstNodes: List<TraceNode>,
    failure: LincheckFailure
) {
    appendLine(DETAILED_TRACE_TITLE)
    val traceRepresentationVerbose = traceGraphToRepresentationList(sectionsFirstNodes, true)
    appendTraceRepresentation(failure.scenario, traceRepresentationVerbose)
    if (failure is DeadlockOrLivelockFailure) {
        appendLine(ALL_UNFINISHED_THREADS_IN_DEADLOCK_MESSAGE)
    }
}

private fun StringBuilder.appendTraceRepresentation(
    scenario: ExecutionScenario,
    traceRepresentation: List<List<TraceEventRepresentation>>
) {
    val traceRepresentationSplitted = splitToColumns(scenario.nThreads, traceRepresentation)
    with(ExecutionLayout(scenario.nThreads, traceRepresentationSplitted.map { it.columns })) {
        appendSeparatorLine()
        appendHeader()
        appendSeparatorLine()
        traceRepresentationSplitted.forEach { section ->
            appendColumns(section.columns)
            appendSeparatorLine()
        }
    }
}

/**
 * Convert trace events to the final form of a matrix of strings.
 */
private fun splitToColumns(nThreads: Int, traceRepresentation:  List<List<TraceEventRepresentation>>): List<TableSectionColumnsRepresentation> {
    return traceRepresentation.map { sectionRepresentation ->
        val result = List(nThreads) { mutableListOf<String>() }
        for (event in sectionRepresentation) {
            val columnId = event.iThread
            // write message in an appropriate column
            result[columnId].add(event.representation)
            val neededSize = result[columnId].size
            // establish columns size equals
            for (column in result)
                if (column.size != neededSize)
                    column.add("")
        }
        TableSectionColumnsRepresentation(result)
    }
}

/**
 * Represents a column list representation of any table section (init, parallel, post, validation).
 */
class TableSectionColumnsRepresentation(
    /**
     * List of column representation.
     */
    val columns: List<List<String>>
)

/**
 * Constructs a trace graph based on the provided [trace].
 * Trace is divided into several sections with init, parallel, post and validation parts.
 *
 * A trace graph consists of two types of edges:
 * `next` edges form a single-directed list in which the order of events is the same as in [trace].
 * `internalEvents` edges form a directed forest.
 *
 * @return a list of nodes corresponding to the starting trace event in each section.
 */
internal fun constructTraceGraph(
    failure: LincheckFailure,
    results: ExecutionResult?,
    trace: Trace,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
): List<TraceNode> {
    val scenario = failure.scenario
    val tracePoints = trace.trace
    // last events that were executed for each thread. It is either thread finish events or events before crash
    val lastExecutedEvents = IntArray(scenario.nThreads) { iThread ->
        tracePoints.mapIndexed { i, e -> Pair(i, e) }.lastOrNull { it.second.iThread == iThread }?.first ?: -1
    }
    // last actor that was handled for each thread
    val lastHandledActor = IntArray(scenario.nThreads) { -1 }
    val isValidationFunctionFailure = failure is ValidationFailure
    val actorNodes = Array(scenario.nThreads) { i ->
        val actorsCount = scenario.threads[i].size + if (i == 0 && failure is ValidationFailure) 1 else 0
        Array<ActorNode?>(actorsCount) { null }
    }
    val actorRepresentations = createActorRepresentation(scenario, failure)
    // call nodes for each method call
    val callNodes = mutableMapOf<Int, CallNode>()
    // all trace nodes in order corresponding to `tracePoints`
    val traceGraphNodesSections = arrayListOf<MutableList<TraceNode>>()
    var traceGraphNodes = arrayListOf<TraceNode>()

    for (eventId in tracePoints.indices) {
        val event = tracePoints[eventId]
        if (event is SectionDelimiterTracePoint) {
            if (event.executionPart == ExecutionPart.VALIDATION) {
                // we don't need validation function trace if the cause of the failure is not a validation function failure
                if (!isValidationFunctionFailure) break
            }
            if (traceGraphNodes.isNotEmpty()) {
                traceGraphNodesSections += traceGraphNodes
                traceGraphNodes = arrayListOf()
            }
            continue
        }
        val iThread = event.iThread
        val actorId = event.actorId
        // add all actors that started since the last event
        while (lastHandledActor[iThread] < min(actorId, actorNodes[iThread].lastIndex)) {
            val nextActor = ++lastHandledActor[iThread]
            // create new actor node actor
            val actorNode = traceGraphNodes.createAndAppend { lastNode ->
                ActorNode(
                    iThread = iThread,
                    last = lastNode,
                    callDepth = 0,
                    actorRepresentation = actorRepresentations[iThread][nextActor],
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
                    actorRepresentation = actorRepresentations[iThread][actorId],
                    resultRepresentation = actorNodeResultRepresentation(actorResult, exceptionStackTraces)
                )
                actorNodes[iThread][actorId] = actorNode
                traceGraphNodes += actorNode
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
    // add last section
    if (traceGraphNodes.isNotEmpty()) {
        traceGraphNodesSections += traceGraphNodes
    }

    return traceGraphNodesSections.map { it.first() }
}

/**
 * Creates united actors representation, including invoked actors and validation functions.
 * In output construction, we treat validation function call like a regular actor for unification.
 */
private fun createActorRepresentation(
    scenario: ExecutionScenario,
    failure: LincheckFailure
): Array<List<String>> {
    return Array(scenario.nThreads) { i ->
        if (i == 0) {
            val actors = scenario.threads[i].map { it.toString() }.toMutableList()

            if (failure is ValidationFailure) {
                actors += "${failure.validationFunctionName}(): ${failure.exception::class.simpleName}"
            }

            actors
        } else scenario.threads[i].map { it.toString() }
    }
}

private operator fun ExecutionResult?.get(iThread: Int, actorId: Int): Result? =
    this?.threadsResults?.get(iThread)?.get(actorId)

/**
 * Create a new trace node and add it to the end of the list.
 */
private fun <T : TraceNode> MutableList<TraceNode>.createAndAppend(constructor: (lastNode: TraceNode?) -> T): T =
    constructor(lastOrNull()).also { add(it) }

/**
 * @param sectionsFirstNodes a list of first nodes in each scenario section
 */
private fun traceGraphToRepresentationList(
    sectionsFirstNodes: List<TraceNode>,
    verboseTrace: Boolean
): List<List<TraceEventRepresentation>> =
    sectionsFirstNodes.map { firstNodeInSection ->
        buildList {
            var curNode: TraceNode? = firstNodeInSection
            while (curNode != null) {
                curNode = curNode.addRepresentationTo(this, verboseTrace)
            }
        }
    }

internal sealed class TraceNode(
    val iThread: Int,
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

internal class TraceLeafEvent(
    iThread: Int,
    last: TraceNode?,
    callDepth: Int,
    internal val event: TracePoint,
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

internal abstract class TraceInnerNode(iThread: Int, last: TraceNode?, callDepth: Int) :
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

internal class CallNode(
    iThread: Int,
    last: TraceNode?,
    callDepth: Int,
    internal val call: MethodCallTracePoint
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

internal class ActorNode(
    iThread: Int,
    last: TraceNode?,
    callDepth: Int,
    internal val actorRepresentation: String,
    private val resultRepresentation: String?
) : TraceInnerNode(iThread, last, callDepth) {
    override fun addRepresentationTo(
        traceRepresentation: MutableList<TraceEventRepresentation>,
        verboseTrace: Boolean
    ): TraceNode? {
        val actorRepresentation = actorRepresentation + if (resultRepresentation != null) ": $resultRepresentation" else ""
        traceRepresentation.add(TraceEventRepresentation(iThread, actorRepresentation))
        return if (!shouldBeExpanded(verboseTrace)) {
            lastState?.let { traceRepresentation.add(stateEventRepresentation(iThread, it)) }
            lastInternalEvent.next
        } else {
            next
        }
    }
}

internal class ActorResultNode(
    iThread: Int,
    last: TraceNode?,
    callDepth: Int,
    internal val resultRepresentation: String?
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

internal class TraceEventRepresentation(val iThread: Int, val representation: String)

internal fun getObjectName(obj: Any?): String =
    if (obj != null) {
        if (obj.javaClass.isAnonymousClass) {
            obj.javaClass.simpleNameForAnonymous
        } else {
            obj.javaClass.simpleName + "#" + getObjectNumber(obj.javaClass, obj)
        }
    } else {
        "null"
    }

private val Class<*>.simpleNameForAnonymous: String get() {
    // Split by the package separator and return the result if this is not an inner class.
    val withoutPackage = name.substringAfterLast('.')
    if (!withoutPackage.contains("$")) return withoutPackage
    // Extract the last named inner class followed by any "$<number>" patterns using regex.
    val regex = """(.*\$)?([^\$.\d]+(\$\d+)*)""".toRegex()
    val matchResult = regex.matchEntire(withoutPackage)
    return matchResult?.groups?.get(2)?.value ?: withoutPackage
}

// Should be called only during `appendTrace` invocation
internal fun getObjectNumber(clazz: Class<Any>, obj: Any): Int = objectNumeration
    .computeIfAbsent(clazz) { IdentityHashMap() }
    .computeIfAbsent(obj) { 1 + objectNumeration[clazz]!!.size }

private val objectNumeration = Collections.synchronizedMap(WeakHashMap<Class<Any>, MutableMap<Any, Int>>())

const val TRACE_TITLE = "The following interleaving leads to the error:"
const val DETAILED_TRACE_TITLE = "Detailed trace:"
private const val ALL_UNFINISHED_THREADS_IN_DEADLOCK_MESSAGE = "All unfinished threads are in deadlock"

internal fun cleanObjectNumeration() {
    objectNumeration.clear()
}
