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

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.DeadlockWithDumpFailure
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.strategy.ObstructionFreedomViolationFailure
import org.jetbrains.kotlinx.lincheck.strategy.UnexpectedExceptionFailure
import org.jetbrains.kotlinx.lincheck.strategy.ValidationFailure
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.constructTraceGraph

object LincheckFailureJsonConverter {

    private val json = Json {
        prettyPrint = true
        explicitNulls = false
    }

    @Synchronized
    fun toJson(failure: LincheckFailure): String {
        val results: ExecutionResult? = (failure as? IncorrectResultsFailure)?.results
        // If a result is present - collect exceptions stack traces to print them
        val exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace> = results?.let {
            when (val exceptionsProcessingResult = collectExceptionStackTraces(results)) {
                // If some exception was thrown from the Lincheck itself, we ask for bug reporting
                is InternalLincheckBugResult -> return haltOnInternalBugException(exceptionsProcessingResult.exception)
                is ExceptionStackTracesResult -> exceptionsProcessingResult.exceptionStackTraces
            }
        } ?: emptyMap()

        val jsonObject: FailureReport = when (failure) {
            is IncorrectResultsFailure -> incorrectResultsJsonFailure(failure, exceptionStackTraces)
            is DeadlockWithDumpFailure -> deadlockResultsToJsonFailure(failure, exceptionStackTraces)
            is UnexpectedExceptionFailure -> unexpectedExceptionResultToJsonFailure(failure, exceptionStackTraces)
            is ObstructionFreedomViolationFailure -> obstructionFreedomViolationFailureToJson(
                failure,
                exceptionStackTraces
            )

            is ValidationFailure -> when (failure.exception) {
                is LincheckInternalBugException -> return haltOnInternalBugException(failure.exception)
                else -> validationFailureToJson(failure, exceptionStackTraces)
            }
        }

        return json.encodeToString(jsonObject)
    }

    private fun validationFailureToJson(
        failure: ValidationFailure,
        exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
    ): ValidationFunctionExceptionJsonReport {
        return ValidationFunctionExceptionJsonReport(
            scenario = failure.scenario.toJson(),
            validationFunctionName = failure.functionName,
            validationFunctionException = exceptionToString(failure.exception, failure.exception.stackTrace.toList()),
            trace = failure.trace?.let { traceToJson(failure, null, it, exceptionStackTraces) },
            exceptionStackTraces = exceptionStackTraces.toJsonMap(),
        )
    }

    private fun obstructionFreedomViolationFailureToJson(
        failure: ObstructionFreedomViolationFailure,
        exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
    ): ObstructionFreedomViolationJsonReport {
        return ObstructionFreedomViolationJsonReport(
            reason = failure.reason,
            scenario = failure.scenario.toJson(),
            exceptionStackTraces = exceptionStackTraces.toJsonMap(),
            trace = failure.trace?.let { traceToJson(failure, null, it, exceptionStackTraces) }
        )
    }

    private fun unexpectedExceptionResultToJsonFailure(
        failure: UnexpectedExceptionFailure,
        exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
    ): UnexpectedExceptionJsonResult {
        return UnexpectedExceptionJsonResult(
            scenario = failure.scenario.toJson(),
            exceptionStackTraces = exceptionStackTraces.toJsonMap(),
            trace = failure.trace?.let { traceToJson(failure, null, it, exceptionStackTraces) },
            unexpectedException = exceptionToString(failure.exception, failure.exception.stackTrace.toList())
        )
    }


    private fun deadlockResultsToJsonFailure(
        failure: DeadlockWithDumpFailure,
        exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
    ): DeadlockResultsFailureJsonReport {

        return DeadlockResultsFailureJsonReport(
            scenario = failure.scenario.toJson(),
            exceptionStackTraces = exceptionStackTraces.toJsonMap(),
            trace = failure.trace?.let { traceToJson(failure, null, it, exceptionStackTraces) },
        )
    }

    private fun incorrectResultsJsonFailure(
        failure: IncorrectResultsFailure,
        exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
    ): IncorrectResultsFailureJsonReport {
        return IncorrectResultsFailureJsonReport(
            scenario = failure.scenario.toJson(),
            results = executionResultToJson(failure.scenario, failure.results, exceptionStackTraces),
            trace = failure.trace?.let { traceToJson(failure, failure.results, it, exceptionStackTraces) },
            exceptionStackTraces = exceptionStackTraces.toJsonMap()
        )
    }

    private fun Map<Throwable, ExceptionNumberAndStacktrace>.toJsonMap(): Map<Int, String>? {
        if (isEmpty()) return null
        return entries.sortedBy { it.value.number }.map { (key, value) ->
            value.number to exceptionToString(key, value.stackTrace)
        }.toMap()
    }

    private fun exceptionToString(
        key: Throwable,
        stackTrace: List<StackTraceElement>
    ) = key.toString() + "\n" + stackTrace.joinToString("\n")

    private fun traceToJson(
        failure: LincheckFailure,
        results: ExecutionResult?,
        trace: Trace,
        exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>
    ): TraceJson {
        val startTraceGraphNode = constructTraceGraph(failure.scenario, results, trace, exceptionStackTraces)

        return TraceJson(
            shortTrace = traceGraphToRepresentationJsonList(startTraceGraphNode, false),
            detailedTrace = traceGraphToRepresentationJsonList(startTraceGraphNode, true)
        )
    }

    private fun traceGraphToRepresentationJsonList(
        startNode: TraceNode?,
        verboseTrace: Boolean
    ): List<InterleavingTraceJsonNode> {
        var curNode: TraceNode? = startNode
        val traceRepresentation = mutableListOf<TraceEventRepresentation>()
        while (curNode != null) {
            curNode = curNode.addRepresentationTo(traceRepresentation, verboseTrace)
        }
        return traceRepresentation.map { InterleavingTraceJsonNode(it.iThread, it.representation) }
    }

    @Serializable

    data class TraceJson(
        val shortTrace: List<InterleavingTraceJsonNode>?,
        val detailedTrace: List<InterleavingTraceJsonNode>?,
    )

    private fun haltOnInternalBugException(exception: Throwable): String {
        return StringBuilder().apply { appendInternalLincheckBugFailure(exception) }.toString()
    }

    private fun ExecutionScenario.toJson(): ScenarioJson {
        return ScenarioJson(
            initPart = ThreadScenarioPart(0, initExecution.map { it.toString() }),
            parallelPart = parallelExecution.mapIndexed { iThread, threadPart ->
                ThreadScenarioPart(iThread, threadPart.map { it.toString() })
            },
            postPart = ThreadScenarioPart(0, initExecution.map { it.toString() }),
        )
    }


    @Serializable
    data class ExecutionResultsJson(
        val hints: List<String>?,
        val initPartExecution: ExecutionTraceJsonNode?,
        val afterInitPartStateRepresentation: String?,
        val parallelPartExecution: List<ExecutionTraceJsonNode>?,
        val afterParallelPartStateRepresentation: String?,
        val postPartExecution: ExecutionTraceJsonNode?,
        val afterPostPartStateRepresentation: String?,
    )

    private fun executionResultToJson(
        scenario: ExecutionScenario,
        executionResult: ExecutionResult,
        exceptionStackTraces: Map<Throwable, ExceptionNumberAndStacktrace>,
    ): ExecutionResultsJson {
        requireEqualSize(scenario.parallelExecution, executionResult.parallelResults) {
            "Different numbers of threads and matching results found"
        }
        requireEqualSize(scenario.initExecution, executionResult.initResults) {
            "Different numbers of actors and matching results found"
        }
        requireEqualSize(scenario.postExecution, executionResult.postResults) {
            "Different numbers of actors and matching results found"
        }
        for (i in scenario.parallelExecution.indices) {
            requireEqualSize(scenario.parallelExecution[i], executionResult.parallelResults[i]) {
                "Different numbers of actors and matching results found"
            }
        }
        val initPart = scenario.initExecution.zip(executionResult.initResults) { actor, result ->
            ActorWithResult(actor, result, exceptionStackTraces).toString()
        }.let { if (it.isEmpty()) null else ExecutionTraceJsonNode(0, it) }
        val postPart = scenario.postExecution.zip(executionResult.postResults) { actor, result ->
            ActorWithResult(actor, result, exceptionStackTraces).toString()
        }.let { if (it.isEmpty()) null else ExecutionTraceJsonNode(0, it) }
        var hasClocks = false
        val parallelPart = scenario.parallelExecution.mapIndexed { iThread, actors ->
            actors.zip(executionResult.parallelResultsWithClock[iThread]) { actor, resultWithClock ->
                if (!resultWithClock.clockOnStart.empty)
                    hasClocks = true
                ActorWithResult(
                    actor,
                    resultWithClock.result,
                    exceptionStackTraces,
                    clock = resultWithClock.clockOnStart
                ).toString()
            }.let { if (it.isEmpty()) null else ExecutionTraceJsonNode(iThread, it) }
        }.filterNotNull()
        val hints = mutableListOf<String>()
        if (scenario.initExecution.isNotEmpty() || scenario.postExecution.isNotEmpty()) {
            hints.add(
                """
                Operations in Init part happen before Parallel part, operations in Parallel part happen before Post part, 
            """.trimIndent()
            )
        }
        if (hasClocks) {
            hints.add(
                """
                Values in "[..]" brackets indicate the number of completed operations
                in each of the parallel threads seen at the beginning of the current operation
            """.trimIndent()
            )
        }
        if (exceptionStackTraces.isNotEmpty()) {
            hints.add(
                """
                The number next to an exception name helps you find its stack trace provided after the interleaving section
            """.trimIndent()
            )
        }

        return ExecutionResultsJson(
            hints = hints.nullIfEmpty(),
            initPartExecution = initPart,
            afterInitPartStateRepresentation = executionResult.afterInitStateRepresentation,
            parallelPartExecution = parallelPart,
            afterParallelPartStateRepresentation = executionResult.afterParallelStateRepresentation,
            postPartExecution = postPart,
            afterPostPartStateRepresentation = executionResult.afterPostStateRepresentation
        )
    }

    private fun <T> List<T>.nullIfEmpty(): List<T>? = if (isEmpty()) null else this

    @Serializable
    data class InterleavingTraceJsonNode(val iThread: Int, val representation: String)

    @Serializable
    data class ExecutionTraceJsonNode(val iThread: Int, val actions: List<String>)

    @Serializable
    data class ScenarioJson(
        private val initPart: ThreadScenarioPart,
        private val parallelPart: List<ThreadScenarioPart>,
        private val postPart: ThreadScenarioPart,
    ) {
        init {
            require(initPart.theadId == 0)
            require(postPart.theadId == 0)
        }
    }

    @Serializable
    data class ThreadScenarioPart(
        val theadId: Int,
        val actions: List<String>
    )

    @Serializable
    sealed class FailureReport {
        abstract val failureMessage: String
        abstract val exceptionStackTraces: Map<Int, String>?
        abstract val scenario: ScenarioJson
        abstract val trace: TraceJson?
    }

    @Serializable
    data class IncorrectResultsFailureJsonReport(
        override val scenario: ScenarioJson,
        val results: ExecutionResultsJson,
        override val trace: TraceJson?,
        override val exceptionStackTraces: Map<Int, String>?
    ) : FailureReport() {
        override val failureMessage: String = "Incorrect results"
    }

    @Serializable
    data class DeadlockResultsFailureJsonReport(
        override val scenario: ScenarioJson,
        override val exceptionStackTraces: Map<Int, String>?,
        override val trace: TraceJson?,
    ) : FailureReport() {
        override val failureMessage: String = "Execution has hung, all unfinished threads are in deadlock / livelock"
    }

    @Serializable
    data class ObstructionFreedomViolationJsonReport(
        val reason: String,
        override val scenario: ScenarioJson,
        override val exceptionStackTraces: Map<Int, String>?,
        override val trace: TraceJson?
    ) : FailureReport() {
        override val failureMessage: String
            get() = "Obstruction freedom violation. = The algorithm should be non-blocking, but a lock is detected ="
    }

    @Serializable
    data class UnexpectedExceptionJsonResult(
        override val scenario: ScenarioJson,
        override val exceptionStackTraces: Map<Int, String>?,
        override val trace: TraceJson?,
        val unexpectedException: String
    ) : FailureReport() {
        override val failureMessage: String = "Unexpected exception occurred"
    }

    @Serializable
    data class ValidationFunctionExceptionJsonReport(
        override val scenario: ScenarioJson,
        val validationFunctionName: String,
        val validationFunctionException: String,
        override val trace: TraceJson?,
        override val exceptionStackTraces: Map<Int, String>?,
    ) : FailureReport() {
        override val failureMessage: String = "= Validation function $validationFunctionName has failed ="
    }

}

fun LincheckFailure.toJson(): String = LincheckFailureJsonConverter.toJson(this)
