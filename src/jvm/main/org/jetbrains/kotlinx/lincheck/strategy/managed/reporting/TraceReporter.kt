/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed.reporting

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.ExecutionPart
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.ObstructionFreedomViolationFailure
import org.jetbrains.kotlinx.lincheck.strategy.ValidationFailure
import kotlin.math.*

@Synchronized // we should avoid concurrent executions to keep `objectNumeration` consistent
internal fun StringBuilder.appendTrace(
    failure: LincheckFailure,
    results: ExecutionResult,
    trace: Trace,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>,
) {
    // Optimize trace
    
    // Turn trace into graph
    
    // Flatten graph
    
    // To string
    
//    val trace = compressTrace(trace.deepCopy())
    TraceStringBuilder(this, failure, results, trace, exceptionStackTraces).appendTrace()
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
    nThreads: Int,
    failure: LincheckFailure,
    results: ExecutionResult,
    trace: Trace,
    exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
): List<TraceNodeOld> {
    val tracePoints = trace.deepCopy().trace
    compressTrace(tracePoints)
    removeNestedThreadStartPoints(tracePoints)
    val scenario = failure.scenario
    val prefixFactory = TraceNodePrefixFactory(nThreads)
    val resultProvider = ExecutionResultsProvider(results, failure)

    // Last events that were executed for each thread.
    // It is either thread finish events or events before the crash.
    val lastExecutedEvents = IntArray(nThreads) { iThread ->
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
    // custom threads are handled separately
    val nCustomThreads = (nThreads - scenario.nThreads).coerceAtLeast(0)
    val customThreadActors = MutableList<ActorNode?>(nCustomThreads) { null }
    // call nodes for each method call
    val callNodes = mutableMapOf<Int, CallNode>()
    // all trace nodes in order corresponding to `tracePoints`
    val traceGraphNodesSections = arrayListOf<MutableList<TraceNodeOld>>()
    var traceGraphNodes = arrayListOf<TraceNodeOld>()

    val isGeneralPurposeMC = isGeneralPurposeModelCheckingScenario(scenario)

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

        if (iThread < scenario.nThreads) {
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
                        resultRepresentation = actorNodeResultRepresentation(
                            result = resultProvider[iThread, nextActor],
                            failure = failure,
                            exceptionStackTraces = exceptionStackTraces,
                        )
                    )
                }
                actorNodes[iThread][nextActor] = actorNode
            }
        }

        // custom threads are handled separately
        val iCustomThread = iThread - scenario.nThreads
        if (iThread >= scenario.nThreads && customThreadActors[iCustomThread] == null) {
            customThreadActors[iCustomThread] = traceGraphNodes.createAndAppend { lastNode ->
                ActorNode(
                    prefixProvider = prefixFactory.actorNodePrefix(iCustomThread),
                    iThread = iThread,
                    last = lastNode,
                    callDepth = 0,
                    actorRepresentation = "run()",
                    resultRepresentation = null,
                )
            }
        }

        // add the event
        var innerNode: TraceInnerNode = when {
            iThread < scenario.nThreads -> actorNodes[iThread][actorId]!!
            // custom threads are handled separately
            else -> customThreadActors[iCustomThread]!!
        }

        for (call in event.callStackTrace) {
            // Switch events that happen as a first event of the method are lifted out of the method in the trace
            if (!callNodes.containsKey(call.id) && event is SwitchEventTracePoint) break
            val callNode = callNodes.computeIfAbsent(call.id) {
                // create a new call node if needed
                val result = traceGraphNodes.createAndAppend { lastNode ->
                    val callDepth = innerNode.callDepth + 1
                    // TODO: please refactor me
                    var prefixCallDepth = callDepth
                    if (isGeneralPurposeMC && iThread == 0) {
                        prefixCallDepth -= 1
                    }
                    val prefix = prefixFactory.prefixForCallNode(iThread, prefixCallDepth)
                    CallNode(prefix, iThread, lastNode, callDepth, call.tracePoint)
                }
                // make it a child of the previous node
                innerNode.addInternalEvent(result)
                result
            }
            innerNode = callNode
        }
        val isLastExecutedEvent = (eventId == lastExecutedEvents[iThread])
        val node = traceGraphNodes.createAndAppend { lastNode ->
            val callDepth = innerNode.callDepth + 1
            // TODO: please refactor me
            var prefixCallDepth = callDepth
            if (isGeneralPurposeMC && iThread == 0) {
                prefixCallDepth -= 1
            }
            val prefix = prefixFactory.prefix(event, prefixCallDepth)
            TraceLeafEvent(prefix, iThread, lastNode, callDepth, event, isLastExecutedEvent)
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
                    resultRepresentation = actorNodeResultRepresentation(
                        result = actorResult,
                        failure = failure,
                        exceptionStackTraces = exceptionStackTraces,
                    )
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

    // custom threads are handled separately
    for (iCustomThread in customThreadActors.indices) {
        val iThread = scenario.nThreads + iCustomThread
        var actorNode = customThreadActors[iCustomThread]
        if (actorNode == null)
            continue
        val lastEvent = actorNode.lastInternalEvent
        val lastEventNext = lastEvent.next
        // TODO: a hacky-way to detect if the thread was aborted due to a detected live-lock;
        //   in the future we need a better way to pass the results of the custom threads,
        //   but currently it is not possible and would require large refactoring of the related code
        val isHung = (
            lastEvent is TraceLeafEvent &&
            lastEvent.event is SwitchEventTracePoint &&
            lastEvent.event.reason is SwitchReason.ActiveLock
        )
        val result = if (isHung) null else VoidResult
        if (result === null)
            continue
        val resultRepresentation = resultRepresentation(result, exceptionStackTraces)
        val callDepth = actorNode.callDepth + 1
        val resultNode = ActorResultNode(
            prefixProvider = prefixFactory.actorResultPrefix(iThread, callDepth),
            iThread = iThread,
            last = lastEvent,
            callDepth = callDepth,
            resultRepresentation = resultRepresentation,
            exceptionNumberIfExceptionResult = null
        )
        actorNode.addInternalEvent(resultNode)
        resultNode.next = lastEventNext
    }

    // add last section
    if (traceGraphNodes.isNotEmpty()) {
        traceGraphNodesSections += traceGraphNodes
    }

    return traceGraphNodesSections.map { it.first() }
}

/**
 * When `thread() { ... }` is called it is represented as
 * ```
 * thread creation line: Thread#2 at A.fun(location)
 *     Thread#2.start()
 * ```
 * this function gets rid of the second line.
 * But only if it has been created with `thread(start = true)`
 */
private fun removeNestedThreadStartPoints(trace: List<TracePoint>) = trace
    .filter { it is ThreadStartTracePoint }
    .forEach { tracePoint -> 
        val threadCreationCall = tracePoint.callStackTrace.dropLast(1).lastOrNull()
        if(threadCreationCall?.tracePoint?.isThreadCreation() == true) {
            tracePoint.callStackTrace = tracePoint.callStackTrace.dropLast(1)
        }
    }

private fun compressTrace(trace: List<TracePoint>) {
    removeSyntheticFieldAccessTracePoints(trace)
    HashSet<Int>().let { removed ->
        trace.apply { forEach { it.callStackTrace = compressCallStackTrace(it.callStackTrace, removed) } }
    }
}

/**
 * Remove access$get and access$set, which is used when a lambda argument accesses a private field for example.
 * This is different from fun$access, which is addressed in [compressCallStackTrace].
 */
private fun removeSyntheticFieldAccessTracePoints(trace: List<TracePoint>) {
    trace
        .filter { it is ReadTracePoint || it is WriteTracePoint }
        .forEach { point ->
            val lastCall = point.callStackTrace.lastOrNull() ?: return@forEach
            if (isSyntheticFieldAccess(lastCall.tracePoint.methodName)) {
                if (point is ReadTracePoint) point.stackTraceElement = lastCall.tracePoint.stackTraceElement
                if (point is WriteTracePoint) point.stackTraceElement = lastCall.tracePoint.stackTraceElement
                point.callStackTrace = point.callStackTrace.dropLast(1)
            }
        }
}

private fun isSyntheticFieldAccess(methodName: String): Boolean = 
    methodName.contains("access\$get") || methodName.contains("access\$set")

/**
 * Merges two consecutive calls in the stack trace into one call if they form a compressible pair,
 * see [isCompressiblePair] for details.
 *
 * Since each tracePoint itself contains a [callStackTrace] of its own,
 * we need to recursively traverse each point.
 */
private fun compressCallStackTrace(
    callStackTrace: List<CallStackTraceElement>,
    removed: HashSet<Int>,
    seen: HashSet<Int> = HashSet(),
): List<CallStackTraceElement> {
    val oldStacktrace = callStackTrace.toMutableList()
    val compressedStackTrace = mutableListOf<CallStackTraceElement>()
    while (oldStacktrace.isNotEmpty()) {
        val currentElement = oldStacktrace.removeFirst()
        
        // if element was removed (or seen) by previous iteration continue
        if (removed.contains(currentElement.methodInvocationId)) continue
        if (seen.contains(currentElement.methodInvocationId)) {
            compressedStackTrace.add(currentElement)
            continue
        }
        seen.add(currentElement.methodInvocationId)
        
        // if next element is null, we reached end of list
        val nextElement = oldStacktrace.firstOrNull()
        if (nextElement == null) {
            currentElement.tracePoint.callStackTrace = 
                compressCallStackTrace(currentElement.tracePoint.callStackTrace, removed, seen)
            compressedStackTrace.add(currentElement)
            break
        }
        
        // Check if current and next are compressible
        if (isCompressiblePair(currentElement.tracePoint.methodName, nextElement.tracePoint.methodName)) {
            // Combine fields of next and current, and store in current
            currentElement.tracePoint.methodName = nextElement.tracePoint.methodName
            currentElement.tracePoint.parameters = nextElement.tracePoint.parameters
            currentElement.tracePoint.callStackTrace =
                compressCallStackTrace(currentElement.tracePoint.callStackTrace, removed, seen)

            check(currentElement.tracePoint.returnedValue == nextElement.tracePoint.returnedValue)
            check(currentElement.tracePoint.thrownException == nextElement.tracePoint.thrownException)
            
            // Mark next as removed
            removed.add(nextElement.methodInvocationId)
            compressedStackTrace.add(currentElement)
            continue
        }
        currentElement.tracePoint.callStackTrace = 
            compressCallStackTrace(currentElement.tracePoint.callStackTrace, removed, seen)
        compressedStackTrace.add(currentElement)
    }
    return compressedStackTrace
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

private fun isCompressiblePair(currentName: String, nextName: String): Boolean =
    isDefaultPair(currentName, nextName) || isAccessPair(currentName, nextName)

/**
 * Used by [compressCallStackTrace] to merge `fun$default(...)` calls.
 *
 * Kotlin functions with default values are represented as two nested calls in the stack trace.
 *
 * For example:
 *
 * ```
 * A.calLMe$default(A#1, 3, null, 2, null) at A.operation(A.kt:23)
 *   A.callMe(3, "Hey") at A.callMe$default(A.kt:27)
 * ```
 *
 * will be collapsed into:
 *
 * ```
 * A.callMe(3, "Hey") at A.operation(A.kt:23)
 * ```
 *
 */
private fun isDefaultPair(currentName: String, nextName: String): Boolean =
    currentName == "${nextName}\$default"

/**
 * Used by [compressCallStackTrace] to merge `.access$` calls.
 *
 * The `.access$` methods are generated by the Kotlin compiler to access otherwise inaccessible members
 * (e.g., private) from lambdas, inner classes, etc.
 *
 * For example:
 *
 * ```
 * A.access$callMe() at A.operation(A.kt:N)
 *  A.callMe() at A.access$callMe(A.kt:N)
 * ```
 *
 * will be collapsed into:
 *
 * ```
 * A.callMe() at A.operation(A.kt:N)
 * ```
 *
 */
private fun isAccessPair(currentName: String, nextName: String): Boolean =
    currentName == "access$${nextName}" 

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


internal class TraceEventRepresentation(val iThread: Int, val representation: String)

const val TRACE_TITLE = "The following interleaving leads to the error:"
const val DETAILED_TRACE_TITLE = "Detailed trace:"
