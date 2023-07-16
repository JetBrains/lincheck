///*
// * Lincheck
// *
// * Copyright (C) 2019 - 2023 JetBrains s.r.o.
// *
// * This Source Code Form is subject to the terms of the
// * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
// * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
// */
//
//package org.jetbrains.kotlinx.lincheck
//
//import kotlinx.serialization.Serializable
//import kotlinx.serialization.encodeToString
//import kotlinx.serialization.json.Json
//import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
//import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
//import org.jetbrains.kotlinx.lincheck.strategy.*
//import org.jetbrains.kotlinx.lincheck.strategy.DeadlockWithDumpFailure
//import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
//import org.jetbrains.kotlinx.lincheck.strategy.ObstructionFreedomViolationFailure
//import org.jetbrains.kotlinx.lincheck.strategy.UnexpectedExceptionFailure
//import org.jetbrains.kotlinx.lincheck.strategy.ValidationFailure
//import org.jetbrains.kotlinx.lincheck.strategy.managed.*
//import org.jetbrains.kotlinx.lincheck.strategy.managed.constructTraceGraph
//
//object LincheckFailureJsonConverter {
//
//    @Synchronized
//    fun toJson(failure: LincheckFailure): String {
//        val results: ExecutionResult? = (failure as? IncorrectResultsFailure)?.results
//        // If a result is present - collect exceptions stack traces to print them
//        val exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace> = results?.let {
//            when (val exceptionsProcessingResult = collectExceptionStackTraces(results)) {
//                // If some exception was thrown from the Lincheck itself, we ask for bug reporting
//                is InternalLincheckBugResult -> haltOnInternalBugException(exceptionsProcessingResult.exception)
//                is ExceptionStackTracesResult -> exceptionsProcessingResult.exceptionStackTraces
//            }
//        } ?: emptyMap()
//
//        return when (failure) {
//            is IncorrectResultsFailure -> return incorrectResultsJsonFailure(failure, exceptionStackTraces)
//            is DeadlockWithDumpFailure -> deadlockResultsToJsonFailure(failure)
//            is UnexpectedExceptionFailure -> appendUnexpectedExceptionFailure(failure)
//            is ObstructionFreedomViolationFailure -> appendObstructionFreedomViolationFailure(failure)
//            is ObstructionFreedomViolationFailure -> TODO()
//            is UnexpectedExceptionFailure -> TODO()
//            is ValidationFailure -> TODO()
//        }
//    }
//
//    private fun deadlockResultsToJsonFailure(
//        failure: DeadlockWithDumpFailure,
//        exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
//    ) {
//
//    }
//
//    private fun incorrectResultsJsonFailure(
//        failure: IncorrectResultsFailure,
//        exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
//    ): String {
//        val trace = failure.trace?.let { traceToJson(failure, failure.results, it, exceptionStackTraces) }
//        val res = IncorrectResultsFailureReport(
//            failure.scenario.toJson(),
//            trace?.shortTrace,
//            trace?.detailedTrace,
//            exceptionStackTraces.toJsonMap()
//        )
//
//        return Json.encodeToString(res)
//    }
//
//    private fun Map<Throwable, ExceptionNumberAndStacktrace>.toJsonMap(): Map<Int, String> {
//        return entries.sortedBy { it.value.number }.map { (key, value) ->
//            value.number to key.toString() + "\n" + value.stackTrace.joinToString("\n")
//        }.toMap()
//    }
//
//    private fun traceToJson(
//        failure: LincheckFailure,
//        results: ExecutionResult,
//        trace: Trace,
//        exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
//    ): TraceJsonInfo {
//        val startTraceGraphNode = constructTraceGraph(failure.scenario, results, trace, exceptionStackTraces)
//
//        val shortTrace = traceGraphToRepresentationJsonList(startTraceGraphNode, false)
//        val detailedTrace = traceGraphToRepresentationJsonList(startTraceGraphNode, true)
//
//        return TraceJsonInfo(shortTrace, detailedTrace)
//    }
//
//    fun traceGraphToRepresentationJsonList(
//        startNode: TraceNode?,
//        verboseTrace: Boolean
//    ): List<InterleavingTraceJsonNode> {
//        var curNode: TraceNode? = startNode
//        val traceRepresentation = mutableListOf<TraceEventRepresentation>()
//        while (curNode != null) {
//            curNode = curNode.addRepresentationTo(traceRepresentation, verboseTrace)
//        }
//        return traceRepresentation.map { InterleavingTraceJsonNode(it.iThread, it.representation) }
//    }
//
//    data class TraceJsonInfo(
//        val shortTrace: List<InterleavingTraceJsonNode>?,
//        val detailedTrace: List<InterleavingTraceJsonNode>?,
//    )
//
//    private fun haltOnInternalBugException(exception: Throwable): Nothing {
//        val message = StringBuilder().apply { appendInternalLincheckBugFailure(exception) }.toString()
//        throw IllegalStateException(message)
//    }
//
//    private fun ExecutionScenario.toJson(): ScenarioJson {
//        return ScenarioJson(
//            initPart = ThreadScenarioPart(0, initExecution.map { it.toString() }),
//            parallelPart = parallelExecution.mapIndexed { iThread, threadPart ->
//                ThreadScenarioPart(iThread, threadPart.map { it.toString() })
//            },
//            postPart = ThreadScenarioPart(0, initExecution.map { it.toString() }),
//        )
//    }
//
//    @Serializable
//    sealed class FailureReport(val failureType: String)
//
//    @Serializable
//    data class IncorrectResultsFailureReport(
//        val scenarioJson: ScenarioJson,
//        val shortTrace: List<InterleavingTraceJsonNode>?,
//        val detailedTrace: List<InterleavingTraceJsonNode>?,
//        val exceptions: Map<Int, String>?
//    ) : FailureReport("Incorrect results")
//
//    @Serializable
//    data class InterleavingTraceJsonNode(val iThread: Int, val representation: String)
//
//    @Serializable
//    data class ScenarioJson(
//        private val initPart: ThreadScenarioPart,
//        private val parallelPart: List<ThreadScenarioPart>,
//        private val postPart: ThreadScenarioPart,
//    ) {
//        init {
//            require(initPart.theadId == 0)
//            require(postPart.theadId == 0)
//        }
//    }
//
//    @Serializable
//    data class ThreadScenarioPart(
//        val theadId: Int,
//        val actions: List<String>
//    )
//}
//
//fun LincheckFailure.toJson(): String = LincheckFailureJsonConverter.toJson(this)
