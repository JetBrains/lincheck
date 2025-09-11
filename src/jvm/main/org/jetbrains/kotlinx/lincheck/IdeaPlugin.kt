@file:JvmName("IdeaPluginKt")
/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck

import sun.nio.ch.lincheck.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.trace.*
import org.jetbrains.kotlinx.lincheck.util.ThreadMap
import org.jetbrains.lincheck.datastructures.verifier.Verifier

const val MINIMAL_PLUGIN_VERSION = "0.19"

// ============== This methods are used by debugger from IDEA plugin to communicate with Lincheck ============== //

/**
 * Invoked from the strategy [ModelCheckingStrategy] when Lincheck finds a bug.
 * The debugger creates a breakpoint on this method, so when it's called,
 * the debugger receives all the information about the failed test.
 * When a failure is found this method is called to provide all required information (trace points, failure type),
 * then [beforeEvent] method is called on each trace point.
 *
 * @param failureType string representation of the failure type (see [LincheckFailure.type]).
 * @param trace failed test trace, where each trace point is represented as a string
 *   (because it's the easiest way to provide some information to the debugger).
 * @param version current Lincheck version.
 * @param minimalPluginVersion minimal compatible plugin version.
 * @param exceptions representation of the exceptions with their stacktrace occurred during the execution.
 * @param executionMode indicates the mode in which lincheck is running current test (see [ManagedStrategy.executionMode])
 */
@Suppress("UNUSED_PARAMETER")
fun testFailed(
    failureType: String,
    trace: Array<String>,
    version: String?,
    minimalPluginVersion: String,
    exceptions: Array<String>,
    executionMode: String
) {}


/**
 * This is a marker method for the plugin to detect the Lincheck test start.
 *
 * The plugin uses this method to disable breakpoints until a failure is found.
 */
fun lincheckVerificationStarted() {}

/**
 * If the debugger needs to replay the execution (due to earlier trace point selection),
 * it replaces the result of this method to `true`.
 */
fun shouldReplayInterleaving(): Boolean {
    return false // should be replaced with `true` to replay the failure
}

/**
 * This method is called on every trace point shown to the user,
 * but before the actual event, such as the read/write/MONITORENTER/MONITOREXIT/, etc.
 * The Debugger creates a breakpoint inside this method and if [eventId] is the selected one,
 * the breakpoint is triggered.
 * Then the debugger performs step-out action, so we appear in the user's code.
 * That's why this method **must** be called from a user-code, not from a nested function.
 *
 * @param eventId id of this trace point. Consistent with `trace`, provided in [testFailed] method.
 * @param type type of this event, just for debugging.
 */
@Suppress("UNUSED_PARAMETER")
fun beforeEvent(eventId: Int, type: String) {
    val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor() ?: return
    val strategy = threadDescriptor.eventTracker as? ManagedStrategy ?: return
    visualize(strategy)
}


/**
 * This method receives all information about the test object instance to visualize.
 * The Debugger creates a breakpoint inside this method and uses its parameters to create the diagram.
 *
 * We pass Maps as Arrays due to difficulties with passing objects (java.util.Map)
 * to the debugger (class version, etc.).
 *
 * @param testInstance tested data structure if present.
 *   If there is no data structure instance (e.g., in the case of general-purpose model checking),
 *   then null is passed.
 * @param objectToNumberMap an array structured like [Object, objectNumber, Object, objectNumber, ...].
 *   Represents a `Map<Any /* Object */, Int>`.
 * @param continuationToLincheckThreadIdMap an array structured like [CancellableContinuation, threadId, ...].
 *   Represents a `Map<Any /* CancellableContinuation */, Int>`.
 * @param threadToLincheckThreadIdMap an array structured like [Thread, threadId, Thread, threadId, ...].
 *   Represents a `Map<Any /* Thread */, Int>`.
 */
@Suppress("UNUSED_PARAMETER")
fun visualizeInstance(
    testInstance: Any?,
    objectToNumberMap: Array<Any>,
    continuationToLincheckThreadIdMap: Array<Any>,
    threadToLincheckThreadIdMap: Array<Any>,
    // TODO: add thread names array parameter
) {}

/**
 * The Debugger creates a breakpoint on this method call to know when the thread is switched.
 * The following "step over" call expects that the next suspension point is in the same thread.
 * So we have to track if a thread is changed by Lincheck to interrupt stepping,
 * otherwise the debugger skips all breakpoints in the thread desired by Lincheck.
 */
fun onThreadSwitchesOrActorFinishes() {}

// ======================================================================================================== //

internal fun runPluginReplay(
    failure: LincheckFailure,
    replayStrategy: ManagedStrategy,
    invocations: Int,
    verifier: Verifier,
) {
    if (failure is TimeoutFailure) return // cannot replay timeout failure
    val replayedFailure = replayStrategy.runIteration(invocations, verifier)
    check(replayedFailure != null)
    replayStrategy.runReplayIfPluginEnabled(replayedFailure)
}

/**
 * If the plugin enabled and the failure has a trace, passes information about
 * the trace and the failure to the Plugin and run re-run execution to debug it.
 */
internal fun ManagedStrategy.runReplayIfPluginEnabled(failure: LincheckFailure) {
    if (inIdeaPluginReplayMode && failure.trace != null) {
        //Print the failure to the console
        System.err.println(failure)
        // Extract trace representation in the appropriate view.
        val trace = constructTraceForPlugin(failure, failure.trace)
        // Collect and analyze the exceptions thrown.
        val (exceptionsRepresentation, internalBugOccurred) = collectExceptionsForPlugin(failure)
        // If an internal bug occurred - print it on the console, no need to debug it.
        if (internalBugOccurred) return
        // Provide all information about the failed test to the debugger.
        testFailed(
            failureType = failure.type,
            trace = trace,
            version = lincheckVersion,
            minimalPluginVersion = MINIMAL_PLUGIN_VERSION,
            exceptions = exceptionsRepresentation,
            executionMode = executionMode.id
        )
        // Replay execution while it's needed.
        do {
            doReplay()
        } while (shouldReplayInterleaving())
    }
}

/**
 * Transforms failure trace to the array of string to pass it to the debugger.
 * (due to difficulties with passing objects like List and TracePoint, as class versions may vary)
 *
 * Each trace point is transformed into the line of the following form:
 * `type;iThread;callDepth;shouldBeExpanded;eventId;representation;stackTraceElement;codeLocationId;relatedTypes;isStatic`.
 *
 *
 *   stackTraceElement is "className:methodName:fileName:lineNumber" or "null" string if it is not applicable
 *   codeLocationId is strictly growing abstract id of location, and it must grow in syntactic order to
 *                  be able to order events occurred at same line in the same file. It is `-1` if it is not
 *                  applicable and stackTranceElement is "null".
 *   relatedTypes for methodCall is "[returnType,arg1,arg2]" for read and write points "[type]".
 *   isStatic is true for static function calls.
 *
 * Later, when [testFailed] breakpoint is triggered debugger parses these lines back to trace points.
 *
 * To help the plugin to create an execution view, we provide a type for each trace point.
 * Below are the codes of trace point types.
 *
 * | Value                          | Code |
 * |--------------------------------|------|
 * | REGULAR                        | 0    |
 * | ACTOR                          | 1    |
 * | RESULT                         | 2    |
 * | SWITCH                         | 3    |
 * | SPIN_CYCLE_START               | 4    |
 * | SPIN_CYCLE_SWITCH              | 5    |
 * | OBSTRUCTION_FREEDOM_VIOLATION  | 6    |
 * | LOCAL_READ                     | 7    |
 * | LOCAL_WRITE                    | 8    |
 * | FIELD_READ                     | 9    |
 * | FIELD_WRITE                    | 10   |
 */
internal fun constructTraceForPlugin(failure: LincheckFailure, trace: Trace): Array<String> {
    val graph = TraceReporter(failure, trace, collectExceptionsOrEmpty(failure)).graph
    val nodeList = graph.flattenNodes(VerboseTraceFlattenPolicy()).reorder()

    return flattenedTraceGraphToCSV(nodeList)
}

internal fun flattenedTraceGraphToCSV(nodeList: SingleThreadedTable<TraceNode>): Array<String> {
    val preExpandedNodeSet = nodeList.extractPreExpandedNodes(ShortTraceFlattenPolicy()).toHashSet()

    return nodeList.flatMap { section ->
        section.mapNotNull { node ->
            when (node) {
                is EventNode -> {
                    val event = node.tracePoint
                    val eventId = event.eventId
                    val representation = event.toStringImpl(withLocation = false)
                    val (location, locationId) = if (event is CodeLocationTracePoint) {
                        val ste = event.stackTraceElement
                        "${ste.className}:${ste.methodName}:${ste.fileName}:${ste.lineNumber}" to event.codeLocation
                    } else {
                        "null" to -1
                    }
                    val type = when {
                        event is ReadTracePoint && event.isLocal ->
                            TracePointType.LOCAL_READ
                        event is WriteTracePoint && event.isLocal ->
                            TracePointType.LOCAL_WRITE
                        event is ReadTracePoint && !event.isLocal ->
                            TracePointType.FIELD_READ
                        event is WriteTracePoint && !event.isLocal ->
                            TracePointType.FIELD_WRITE
                        event is SpinCycleStartTracePoint ->
                            TracePointType.SPIN_CYCLE_START
                        event is SwitchEventTracePoint && event.reason is SwitchReason.ActiveLock ->
                            TracePointType.SPIN_CYCLE_SWITCH
                        event is ObstructionFreedomViolationExecutionAbortTracePoint ->
                            TracePointType.OBSTRUCTION_FREEDOM_VIOLATION
                        event is SwitchEventTracePoint ->
                            TracePointType.SWITCH
                        else ->
                            TracePointType.REGULAR
                    }
                    val relatedTypes = getRelatedTypeList(event)
                    "${type.ordinal};${node.iThread};${node.callDepth};${preExpandedNodeSet.contains(node)};${eventId};${representation};${location};${locationId};[${relatedTypes.joinToString(",")}];false"
                }

                is CallNode -> if (node.tracePoint.isRootCall) {
                    val beforeEventId = -1
                    val representation = node.tracePoint.toStringImpl(withLocation = false)
                    val type = TracePointType.ACTOR
                    "${type.ordinal};${node.iThread};${node.callDepth};${preExpandedNodeSet.contains(node)};${beforeEventId};${representation};null;-1;[];false"
                } else {
                    val beforeEventId = node.tracePoint.eventId
                    val representation = node.tracePoint.toStringImpl(withLocation = false)
                    val ste = node.tracePoint.stackTraceElement
                    val location = "${ste.className}:${ste.methodName}:${ste.fileName}:${ste.lineNumber}"
                    val type = TracePointType.REGULAR
                    val relatedTypes = getRelatedTypeList(node.tracePoint)
                    "${type.ordinal};${node.iThread};${node.callDepth};${preExpandedNodeSet.contains(node)};${beforeEventId};${representation};${location};${node.tracePoint.codeLocation};[${relatedTypes.joinToString(",")}];${node.tracePoint.isStatic}"
                }

                is ResultNode -> {
                    val beforeEventId = -1
                    val type = TracePointType.RESULT
                    val representation = node.actorResult.resultRepresentation
                    val exceptionNumber = (node.actorResult as? ReturnedValueResult.ExceptionResult)?.exceptionNumber ?: -1
                    "${type.ordinal};${node.iThread};${node.callDepth};${preExpandedNodeSet.contains(node)};${beforeEventId};${representation};${exceptionNumber};null;-1;[];false"
                }
                else -> null
            }
        }
    }.toTypedArray()
}


private enum class TracePointType {
    REGULAR,
    ACTOR,
    RESULT,
    SWITCH,
    SPIN_CYCLE_START,
    SPIN_CYCLE_SWITCH,
    OBSTRUCTION_FREEDOM_VIOLATION,
    LOCAL_READ,
    LOCAL_WRITE,
    FIELD_READ,
    FIELD_WRITE,
}

private fun getRelatedTypeList(tracePoint: TracePoint): List<String> = when (tracePoint) {
    is ReadTracePoint -> listOf(tracePoint.valueType)
    is WriteTracePoint -> listOf(tracePoint.valueType)
    is MethodCallTracePoint -> {
        val returnType = if (tracePoint.returnedValue !is ReturnedValueResult.ValueResult) ""
        else (tracePoint.returnedValue as ReturnedValueResult.ValueResult).valueType
        listOf(returnType) + (tracePoint.parameterTypes ?: emptyList())
    }
    else -> emptyList<String>()
}

/**
 * We provide information about the failure type to the Plugin, but
 * due to difficulties with passing objects like LincheckFailure (as class versions may vary),
 * we use its string representation.
 * The Plugin uses this information to show the failure type to a user.
 */
private val LincheckFailure.type: String
    get() = when (this) {
        is IncorrectResultsFailure -> "INCORRECT_RESULTS"
        is ObstructionFreedomViolationFailure -> "OBSTRUCTION_FREEDOM_VIOLATION"
        is UnexpectedExceptionFailure -> "UNEXPECTED_EXCEPTION"
        is ValidationFailure -> "VALIDATION_FAILURE"
        is ManagedDeadlockFailure, is TimeoutFailure -> "DEADLOCK"
    }

/**
 * Processes the exceptions thrown during the execution.
 * @return exceptions string representation to pass to the plugin with a flag,
 *   indicating if an internal bug was the cause of the failure, or not.
 */
private fun collectExceptionsForPlugin(failure: LincheckFailure): ExceptionProcessingResult {
    val results: ExecutionResult = when (failure) {
        is IncorrectResultsFailure -> failure.results
        is ValidationFailure -> {
            return ExceptionProcessingResult(arrayOf(failure.exception.text), isInternalBugOccurred = false)
        }
        else -> {
            return ExceptionProcessingResult(emptyArray(), isInternalBugOccurred = false)
        }
    }
    return when (val exceptionsProcessingResult = collectExceptionStackTraces(results)) {
        // If some exception was thrown from the Lincheck itself, we'll ask for bug reporting
        is InternalLincheckBugResult ->
            ExceptionProcessingResult(arrayOf(exceptionsProcessingResult.exception.text), isInternalBugOccurred = true)
        // Otherwise collect all the exceptions
        is ExceptionStackTracesResult -> {
            exceptionsProcessingResult.exceptionStackTraces.entries
                .sortedBy { (_, numberAndStackTrace) -> numberAndStackTrace.number }
                .map { (exception, numberAndStackTrace) ->
                    val header = exception::class.java.canonicalName + ": " + exception.message
                    header + numberAndStackTrace.stackTrace.joinToString("") { "\n\tat $it" }
                }
                .let { ExceptionProcessingResult(it.toTypedArray(), isInternalBugOccurred = false) }
        }
    }
}

internal fun collectExceptionsOrEmpty(failure: LincheckFailure?): Map<Throwable, ExceptionNumberAndStacktrace> {
    if (failure is ValidationFailure) {
        return mapOf(failure.exception to ExceptionNumberAndStacktrace(1, failure.exception.stackTrace.toList()))
    }
    val results = (failure as? IncorrectResultsFailure)?.results ?: return emptyMap()
    return when (val result = collectExceptionStackTraces(results)) {
        is ExceptionStackTracesResult -> result.exceptionStackTraces
        is InternalLincheckBugResult -> emptyMap()
    }
}

/**
 * Result of creating string representations of exceptions
 * thrown during the execution before passing them to the plugin.
 *
 * @param exceptionsRepresentation string representation of all the exceptions
 * @param isInternalBugOccurred a flag indicating that the exception is caused by a bug in the Lincheck.
 */
@Suppress("ArrayInDataClass")
private data class ExceptionProcessingResult(
    val exceptionsRepresentation: Array<String>,
    val isInternalBugOccurred: Boolean
)

/**
 * Collects all the necessary data to pass to the debugger plugin and calls [visualizeInstance].
 *
 * @param strategy The managed strategy used to obtain data to be passed into the debugger plugin.
 *   Used to collect the data about the test instance, object numbers, threads, and continuations.
 */
private fun visualize(strategy: ManagedStrategy) = runCatching {
    // state visualization is only applied in data structures testing mode
    if (strategy.executionMode != ExecutionMode.DATA_STRUCTURES) return@runCatching

    val runner = strategy.runner
    val allThreads = strategy.getRegisteredThreads()
    val lincheckThreads = (runner as? ExecutionScenarioRunner)?.scenarioThreads.orEmpty()
    val testObject = (runner as? ExecutionScenarioRunner)?.testInstance
    visualizeInstance(testObject,
        objectToNumberMap = strategy.createObjectToNumberMapAsArray(testObject),
        continuationToLincheckThreadIdMap = createContinuationToThreadIdMap(lincheckThreads.toTypedArray()),
        threadToLincheckThreadIdMap = createThreadToLincheckThreadIdMap(allThreads),
    )
}

/**
 * This method is called from the trace-debugger on each debugger session
 * pause to recalculate objects numeration and later visualize it.
 */
private fun visualizeTrace(): Array<Any>? = runCatching {
    val strategyObject = ThreadDescriptor.getCurrentThreadDescriptor()?.eventTracker
        ?: return null
    val strategy = strategyObject as ModelCheckingStrategy
    val runner = strategy.runner as? ExecutionScenarioRunner
    val testObject = runner?.testInstance
    return strategy.createObjectToNumberMapAsArray(testObject)
}.getOrNull()

/**
 * Creates an array [Object, objectNumber, Object, objectNumber, ...].
 * It represents a `Map<Any, Int>`, but due to difficulties with passing objects (Map)
 * to debugger, we represent it as an Array.
 *
 * The Debugger uses this information to enumerate objects.
 */
@Suppress("UNUSED")
private fun ManagedStrategy.createObjectToNumberMapAsArray(testObject: Any?): Array<Any> {
    val resultArray = arrayListOf<Any>()
    // val numbersMap = if (testObject != null) enumerateReachableObjects(testObject) else enumerateAllObjects()
    val numbersMap = enumerateObjects()
    numbersMap.forEach { (any, objectNumber) ->
        resultArray.add(any)
        resultArray.add(objectNumber)
    }
    return resultArray.toTypedArray()
}

/**
 * Creates an array [Thread, threadId, Thread, threadId, ...].
 * It represents a `Map<Thread, ThreadId>`, but due to difficulties with passing objects (Map)
 * to debugger, we represent it as an Array.
 *
 * The Debugger uses this information to enumerate threads.
 */
private fun createThreadToLincheckThreadIdMap(threadMap: ThreadMap<Thread>): Array<Any> {
    val array = arrayListOf<Any>()
    for (entry in threadMap) {
        array.add(entry.value)
        array.add(entry.key)
    }
    return array.toTypedArray()
}

/**
 * Creates an array [CancellableContinuation, threadId, CancellableContinuation, threadId, ...].
 * It represents a `Map<CancellableContinuation, ThreadId>`, but due to difficulties with passing objects (Map)
 * to debugger, we represent it as an Array.
 *
 * The Debugger uses this information to enumerate continuations.
 */
private fun createContinuationToThreadIdMap(threads: Array<TestThread>): Array<Any> {
    val array = arrayListOf<Any>()
    for (thread in threads) {
        array.add(thread.suspendedContinuation ?: continue)
        array.add(thread.threadId)
    }
    return array.toTypedArray()
}