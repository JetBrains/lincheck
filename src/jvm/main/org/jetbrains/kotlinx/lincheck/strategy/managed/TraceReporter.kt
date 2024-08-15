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
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.ManagedDeadlockFailure
import org.jetbrains.kotlinx.lincheck.strategy.ObstructionFreedomViolationFailure
import org.jetbrains.kotlinx.lincheck.strategy.TimeoutFailure
import org.jetbrains.kotlinx.lincheck.strategy.ValidationFailure
import kotlin.math.*

@Synchronized // we should avoid concurrent executions to keep `objectNumeration` consistent
internal fun StringBuilder.appendTrace(
    failure: LincheckFailure,
    results: ExecutionResult,
    trace: Trace,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
) {
    val startTraceGraphNode = constructTraceGraph(failure, results, trace, exceptionStackTraces)

    appendShortTrace(startTraceGraphNode, failure)
    appendExceptionsStackTracesBlock(exceptionStackTraces)
    appendDetailedTrace(startTraceGraphNode, failure)
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
    if (failure is ManagedDeadlockFailure || failure is TimeoutFailure) {
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
    if (failure is ManagedDeadlockFailure || failure is TimeoutFailure) {
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
    results: ExecutionResult,
    trace: Trace,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
): List<TraceNode> {
    val resultProvider = ExecutionResultsProvider(results, failure)
    val tracePoints = trace.trace
    val prefixFactory = TraceNodePrefixFactory(failure.scenario.nThreads)
    val scenario = failure.scenario

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
                    prefixProvider = prefixFactory.actorNodePrefix(iThread),
                    iThread = iThread,
                    last = lastNode,
                    callDepth = 0,
                    actorRepresentation = actorRepresentations[iThread][nextActor],
                    resultRepresentation = actorNodeResultRepresentation(resultProvider[iThread, nextActor], failure, exceptionStackTraces)
                )
            }
            actorNodes[iThread][nextActor] = actorNode
        }
        // add the event
        var innerNode: TraceInnerNode = actorNodes[iThread][actorId]!!
        for (call in event.callStackTrace) {
            val callId = call.suspensionId
            // Switch events that happen as a first event of the method are lifted out of the method in the trace
            if (!callNodes.containsKey(callId) && event is SwitchEventTracePoint) break
            val callNode = callNodes.computeIfAbsent(callId) {
                // create a new call node if needed
                val result = traceGraphNodes.createAndAppend { lastNode ->
                    val callDepth = innerNode.callDepth + 1
                    CallNode(prefixFactory.prefixForCallNode(iThread, callDepth), iThread, lastNode, callDepth, call.call)
                }
                // make it a child of the previous node
                innerNode.addInternalEvent(result)
                result
            }
            innerNode = callNode
        }
        val isLastExecutedEvent = eventId == lastExecutedEvents[iThread]
        val node = traceGraphNodes.createAndAppend { lastNode ->
            val callDepth = innerNode.callDepth + 1
            TraceLeafEvent(prefixFactory.prefix(event, callDepth), iThread, lastNode, callDepth, event, isLastExecutedEvent)
        }
        innerNode.addInternalEvent(node)
    }
    // add an ActorResultNode to each actor, because did not know where actor ends before
    for (iThread in actorNodes.indices) {
        for (actorId in actorNodes[iThread].indices) {
            var actorNode = actorNodes[iThread][actorId]
            val actorResult = resultProvider[iThread, actorId]
            // in case of empty trace, we want to show at least the actor nodes themselves;
            // however, no actor nodes will be created by the code above, so we need to create them explicitly here.
            if (actorNode == null && actorResult != null) {
                val lastNode = actorNodes[iThread].getOrNull(actorId - 1)?.lastInternalEvent
                actorNode = ActorNode(
                    prefixProvider = prefixFactory.actorNodePrefix(iThread),
                    iThread = iThread,
                    last = lastNode,
                    callDepth = 0,
                    actorRepresentation = actorRepresentations[iThread][actorId],
                    resultRepresentation = actorNodeResultRepresentation(actorResult, failure, exceptionStackTraces)
                )
                actorNodes[iThread][actorId] = actorNode
                traceGraphNodes += actorNode
            }
            if (actorNode == null)
                continue
            // insert an ActorResultNode between the last actor event and the next event after it
            val lastEvent = actorNode.lastInternalEvent
            val lastEventNext = lastEvent.next
            val result = resultProvider[iThread, actorId]
            val resultRepresentation = result?.let { resultRepresentation(result, exceptionStackTraces) }
            val callDepth = actorNode.callDepth + 1
            val resultNode = ActorResultNode(
                prefixProvider = prefixFactory.actorResultPrefix(iThread, callDepth),
                iThread = iThread,
                last = lastEvent,
                callDepth = callDepth,
                resultRepresentation = resultRepresentation,
                exceptionNumberIfExceptionResult = if (result is ExceptionResult) exceptionStackTraces[result.throwable]?.number else null
            )
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

private fun actorNodeResultRepresentation(result: Result?, failure: LincheckFailure, exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>): String? {
    // We don't mark actors that violated obstruction freedom as hung.
    if (result == null && failure is ObstructionFreedomViolationFailure) return null
    return when (result) {
        null -> "<hung>"
        is ExceptionResult -> {
            val exceptionNumberRepresentation = exceptionStackTraces[result.throwable]?.let { " #${it.number}" } ?: ""
            "$result$exceptionNumberRepresentation"
        }
        is VoidResult -> null // don't print
        else -> result.toString()
    }
}

/**
 * Helper class to provider execution results, including a validation function result
 */
private class ExecutionResultsProvider(result: ExecutionResult?, failure: LincheckFailure) {

    /**
     * A map of type Map<(threadId, actorId) -> Result>
     */
    private val threadNumberToActorResultMap: Map<Pair<Int, Int>, Result?>

    init {
        val results = hashMapOf<Pair<Int, Int>, Result?>()
        if (result != null) {
            results += result.threadsResults
                .flatMapIndexed { tId, actors -> actors.flatMapIndexed { actorId, result ->
                    listOf((tId to actorId) to result)
                }}
                .toMap()
        }
        if (failure is ValidationFailure) {
            results[0 to firstThreadActorCount(failure)] = ExceptionResult.create(failure.exception)
        }
        threadNumberToActorResultMap = results
    }

    operator fun get(iThread: Int, actorId: Int): Result? {
        return threadNumberToActorResultMap[iThread to actorId]
    }

    private fun firstThreadActorCount(failure: ValidationFailure): Int =
        failure.scenario.initExecution.size + failure.scenario.parallelExecution[0].size + failure.scenario.postExecution.size

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
                actors += "${failure.validationFunctionName}()"
            }

            actors
        } else scenario.threads[i].map { it.toString() }
    }
}

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
    private val prefixProvider: PrefixProvider,
    val iThread: Int,
    last: TraceNode?,
    val callDepth: Int // for tree indentation
) {
    protected val prefix get() = prefixProvider.get()
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

    protected fun stateEventRepresentation(iThread: Int, stateRepresentation: String) =
        TraceEventRepresentation(iThread, prefix + "STATE: $stateRepresentation")
}

internal class TraceLeafEvent(
    prefix: PrefixProvider,
    iThread: Int,
    last: TraceNode?,
    callDepth: Int,
    internal val event: TracePoint,
    private val lastExecutedEvent: Boolean = false
) : TraceNode(prefix, iThread, last, callDepth) {

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
        val representation = prefix + event.toString()
        traceRepresentation.add(TraceEventRepresentation(iThread, representation))
        return next
    }
}

internal abstract class TraceInnerNode(prefixProvider: PrefixProvider, iThread: Int, last: TraceNode?, callDepth: Int) :
    TraceNode(prefixProvider, iThread, last, callDepth) {
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
    prefixProvider: PrefixProvider,
    iThread: Int,
    last: TraceNode?,
    callDepth: Int,
    internal val call: MethodCallTracePoint
) : TraceInnerNode(prefixProvider, iThread, last, callDepth) {
    // suspended method contents should be reported
    override fun shouldBeExpanded(verboseTrace: Boolean): Boolean {
        return call.wasSuspended || super.shouldBeExpanded(verboseTrace)
    }

    override fun addRepresentationTo(
        traceRepresentation: MutableList<TraceEventRepresentation>,
        verboseTrace: Boolean
    ): TraceNode? =
        if (!shouldBeExpanded(verboseTrace)) {
            traceRepresentation.add(TraceEventRepresentation(iThread, prefix + "$call"))
            lastState?.let { traceRepresentation.add(stateEventRepresentation(iThread, it)) }
            lastInternalEvent.next
        } else {
            traceRepresentation.add(TraceEventRepresentation(iThread, prefix + "$call"))
            next
        }
}

internal class ActorNode(
    prefixProvider: PrefixProvider,
    iThread: Int,
    last: TraceNode?,
    callDepth: Int,
    internal val actorRepresentation: String,
    private val resultRepresentation: String?
) : TraceInnerNode(prefixProvider, iThread, last, callDepth) {
    override fun addRepresentationTo(
        traceRepresentation: MutableList<TraceEventRepresentation>,
        verboseTrace: Boolean
    ): TraceNode? {
        val actorRepresentation = prefix + actorRepresentation + if (resultRepresentation != null) ": $resultRepresentation" else ""
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
    prefixProvider: PrefixProvider,
    iThread: Int,
    last: TraceNode?,
    callDepth: Int,
    internal val resultRepresentation: String?,
    /**
     * This value presents only if an exception was the actor result.
     */
    internal val exceptionNumberIfExceptionResult: Int?
) : TraceNode(prefixProvider, iThread, last, callDepth) {
    override val lastState: String? = null
    override val lastInternalEvent: TraceNode = this
    override fun shouldBeExpanded(verboseTrace: Boolean): Boolean = false

    override fun addRepresentationTo(
        traceRepresentation: MutableList<TraceEventRepresentation>,
        verboseTrace: Boolean
    ): TraceNode? {
        if (resultRepresentation != null)
            traceRepresentation.add(TraceEventRepresentation(iThread, prefix + "result: $resultRepresentation"))
        return next
    }
}

/**
 * Provides the prefix output for the [TraceNode].
 * @see TraceNodePrefixFactory
 */
internal fun interface PrefixProvider {
    fun get(): String
}

/**
 * When we create the trace representation, it may need to add two additional spaces before each line is we have a
 * spin cycle starting in call depth 1.
 *
 * This factory encapsulates the logic of creating [PrefixProvider] for different call depths with
 * spin cycle arrows or without.
 *
 * At the beginning of the trace processing, we can't know definitely should we add
 * extra spaces at the beginning of the each line or not.
 * That's why we return [PrefixProvider] closure that have a reference on this factory field,
 * so when trace nodes are composing output, we definitely know should we add extra spaces or not.
 *
 * Example when extra spaces needed:
 * |   one(): <hung>                                                                                                                          |                                                                                                                                          |
 * |     meaninglessActions2() at RecursiveTwoThreadsSpinLockTest.one(RecursiveSpinLockTest.kt:221)                                           |                                                                                                                                          |
 * |     /* The following events repeat infinitely: */                                                                                        |                                                                                                                                          |
 * | ┌╶> meaninglessActions1() at RecursiveTwoThreadsSpinLockTest.one(RecursiveSpinLockTest.kt:222)                                           |                                                                                                                                          |
 * | |     sharedState2.compareAndSet(false,true): false at RecursiveTwoThreadsSpinLockTest.meaninglessActions1(RecursiveSpinLockTest.kt:242) |                                                                                                                                          |
 * | └╶╶╶╶ switch (reason: active lock detected)                                                                                              |                                                                                                                                          |
 * |
 *
 * Example when no extra spaces needed:
 * | cas2_0(0, 0, 2, 1, 0, 3): <hung>                                                                                                                                             |                                                                                                                                                                            |
 * |   array.cas2(0,0,2,1,0,3,0) at BrokenCas2RecursiveLiveLockTest.cas2_0(RecursiveSpinLockTest.kt:271)                                                                          |                                                                                                                                                                            |
 * |     AtomicArrayWithCAS2$Descriptor.apply$default(Descriptor#3,false,0,false,5,null) at AtomicArrayWithCAS2.cas2(RecursiveSpinLockTest.kt:323)                                |                                                                                                                                                                            |
 * |       Descriptor#3.apply(true,0,false) at AtomicArrayWithCAS2$Descriptor.apply$default(RecursiveSpinLockTest.kt:349)                                                         |                                                                                                                                                                            |
 * |         AtomicArrayWithCAS2$Descriptor.installOrHelp$default(Descriptor#3,true,0,false,4,null) at AtomicArrayWithCAS2$Descriptor.apply(RecursiveSpinLockTest.kt:356)         |                                                                                                                                                                            |
 * |           Descriptor#3.installOrHelp(true,0,false) at AtomicArrayWithCAS2$Descriptor.installOrHelp$default(RecursiveSpinLockTest.kt:368)                                     |                                                                                                                                                                            |
 * |             BrokenCas2RecursiveLiveLockTest#1.array.gate0.READ: 1 at AtomicArrayWithCAS2$Descriptor.installOrHelp(RecursiveSpinLockTest.kt:390)                              |                                                                                                                                                                            |
 * |             /* The following events repeat infinitely: */                                                                                                                    |                                                                                                                                                                            |
 * |         ┌╶> Descriptor#2.apply(false,0,true) at AtomicArrayWithCAS2$Descriptor.installOrHelp(RecursiveSpinLockTest.kt:391)                                                   |                                                                                                                                                                            |
 * |         |     status.READ: SUCCESS at AtomicArrayWithCAS2$Descriptor.apply(RecursiveSpinLockTest.kt:351)                                                                     |                                                                                                                                                                            |
 * |         |     status.compareAndSet(SUCCESS,SUCCESS): true at AtomicArrayWithCAS2$Descriptor.apply(RecursiveSpinLockTest.kt:352)                                              |                                                                                                                                                                            |
 * |         |     installOrHelp(true,0,true) at AtomicArrayWithCAS2$Descriptor.apply(RecursiveSpinLockTest.kt:353)                                                               |                                                                                                                                                                            |
 * |         |       BrokenCas2RecursiveLiveLockTest#1.array.array.READ: AtomicReferenceArray#1 at AtomicArrayWithCAS2$Descriptor.installOrHelp(RecursiveSpinLockTest.kt:373)     |                                                                                                                                                                            |
 * |         |       AtomicReferenceArray#1[0].get(): Descriptor#2 at AtomicArrayWithCAS2$Descriptor.installOrHelp(RecursiveSpinLockTest.kt:373)                                  |                                                                                                                                                                            |
 * |         └╶╶╶╶╶╶ switch (reason: active lock detected)
 *
 */
private class TraceNodePrefixFactory(nThreads: Int) {

    /**
     * Indicates should we add extra spaces to all the thread lines or not.
     */
    private val extraIndentPerThread = BooleanArray(nThreads) { false }

    /**
     * Tells if the next node is the first node of the spin cycle.
     */
    private var nextNodeIsSpinCycleStart = false

    /**
     * Tells if we're processing spin cycle nodes now.
     */
    private var inSpinCycle = false

    /**
     * Call depth of the first node in the current spin cycle.
     */
    private var arrowDepth: Int = -1

    fun actorNodePrefix(iThread: Int) = PrefixProvider { extraPrefixIfNeeded(iThread) }

    fun actorResultPrefix(iThread: Int, callDepth: Int) =
        PrefixProvider { extraPrefixIfNeeded(iThread) + TRACE_INDENTATION.repeat(callDepth) }

    fun prefix(event: TracePoint, callDepth: Int): PrefixProvider {
        val isCycleEnd = inSpinCycle && (event is ObstructionFreedomViolationExecutionAbortTracePoint || event is SwitchEventTracePoint)
        return prefixForNode(event.iThread, callDepth, isCycleEnd).also {
            nextNodeIsSpinCycleStart = event is SpinCycleStartTracePoint
            if (isCycleEnd) {
                inSpinCycle = false
            }
        }
    }

    fun prefixForCallNode(iThread: Int, callDepth: Int): PrefixProvider {
        return prefixForNode(iThread, callDepth, false)
    }

    private fun prefixForNode(iThread: Int, callDepth: Int, isCycleEnd: Boolean): PrefixProvider {
        if (nextNodeIsSpinCycleStart) {
            inSpinCycle = true
            nextNodeIsSpinCycleStart = false
            val extraPrefixRequired = callDepth == 1
            if (extraPrefixRequired) {
                extraIndentPerThread[iThread] = true
            }
            arrowDepth = callDepth
            val arrowDepth = arrowDepth
            return PrefixProvider {
                val extraPrefix = if (arrowDepth == 1) 0 else extraPrefixLength(iThread)
                TRACE_INDENTATION.repeat(max(0, arrowDepth - 2 + extraPrefix)) + "┌╶> "
            }
        }
        if (isCycleEnd) {
            val arrowDepth = arrowDepth
            return PrefixProvider {
                val extraPrefix = if (arrowDepth == 1) 0 else extraPrefixLength(iThread)
                TRACE_INDENTATION.repeat(max(0, arrowDepth - 2 + extraPrefix)) + "└╶" + "╶╶".repeat(max(0, callDepth - arrowDepth)) + "╶ "
            }
        }
        if (inSpinCycle) {
            val arrowDepth = arrowDepth
            return PrefixProvider {
                val extraPrefix = if (arrowDepth == 1) 0 else extraPrefixLength(iThread)
                TRACE_INDENTATION.repeat(max(0, arrowDepth - 2 + extraPrefix)) + "| " + TRACE_INDENTATION.repeat(callDepth - arrowDepth + 1)
            }
        }
        return PrefixProvider { extraPrefixIfNeeded(iThread) + TRACE_INDENTATION.repeat(callDepth) }
    }

    private fun extraPrefixIfNeeded(iThread: Int): String = if (extraIndentPerThread[iThread]) "  " else ""
    private fun extraPrefixLength(iThread: Int): Int = if (extraIndentPerThread[iThread]) 1 else 0
}

private const val TRACE_INDENTATION = "  "


internal class TraceEventRepresentation(val iThread: Int, val representation: String)

const val TRACE_TITLE = "The following interleaving leads to the error:"
const val DETAILED_TRACE_TITLE = "Detailed trace:"
private const val ALL_UNFINISHED_THREADS_IN_DEADLOCK_MESSAGE = "All unfinished threads are in deadlock"
