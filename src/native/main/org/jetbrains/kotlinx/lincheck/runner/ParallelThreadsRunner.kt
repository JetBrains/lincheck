/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.runner

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import kotlin.coroutines.*
import kotlin.random.*

/**
 * This runner executes parallel scenario part in different threads.
 * Supports running scenarios with `suspend` functions.
 *
 * It is pretty useful for stress testing or if you do not care about context switch expenses.
 */
internal actual open class ParallelThreadsRunner actual constructor(
    strategy: Strategy,
    testClass: TestClass,
    validationFunctions: List<ValidationFunction>,
    stateRepresentationFunction: StateRepresentationFunction?,
    private val timeoutMs: Long,
    private val useClocks: UseClocks,
    private val initThreadFunction: (() -> Unit)?,
    private val finishThreadFunction: (() -> Unit)?) : Runner(strategy, testClass, validationFunctions, stateRepresentationFunction) {
    private val runnerHash = this.hashCode() // helps to distinguish this runner threads from others

    private lateinit var testInstance: Any
    private val executor = FixedActiveThreadsExecutor(scenario.threads, runnerHash) // should be closed in `close()`

    private lateinit var testThreadExecutions: Array<TestThreadExecution>

    override fun initialize() {
        super.initialize()
        testThreadExecutions = Array(scenario.threads) { t ->
            TestThreadExecution(this, t, scenario.parallelExecution[t])
        }
        testThreadExecutions.forEach { it.allThreadExecutions = testThreadExecutions }
    }

    private fun reset() {
        testInstance = testClass.createInstance()
        testThreadExecutions.forEachIndexed { t, ex ->
            ex.testInstance = testInstance
            val threads = scenario.threads
            val actors = scenario.parallelExecution[t].size
            ex.useClocks = if (useClocks == UseClocks.ALWAYS) true else Random.nextBoolean()
            ex.curClock = 0
            ex.clocks = Array(actors) { emptyClockArray(threads) }
            ex.results = arrayOfNulls(actors)
        }
        completedOrSuspendedThreads.set(0)
    }

    override fun constructStateRepresentation() =
        stateRepresentationFunction?.function?.invoke(testInstance) as String?

    override fun run(): InvocationResult {
        reset()
        val initResults = scenario.initExecution.mapIndexed { i, initActor ->
            executeActor(testInstance, initActor).also {
                executeValidationFunctions(testInstance, validationFunctions) { functionName, exception ->
                    val s = ExecutionScenario(
                        scenario.initExecution.subList(0, i + 1),
                        emptyList(),
                        emptyList()
                    )
                    return ValidationFailureInvocationResult(s, functionName, exception)
                }
            }
        }
        val afterInitStateRepresentation = constructStateRepresentation()
        try {
            executor.submitAndAwait(testThreadExecutions, timeoutMs)
        } catch (e: LincheckTimeoutException) {
            val threadDump = collectThreadDump(this)
            return DeadlockInvocationResult(threadDump)
        } catch (e: LincheckExecutionException) {
            return UnexpectedExceptionInvocationResult(e.cause!!)
        }
        val parallelResultsWithClock = testThreadExecutions.map { ex ->
            ex.results.zip(ex.clocks).map { ResultWithClock(it.first!!, HBClock(it.second)) }
        }
        executeValidationFunctions(testInstance, validationFunctions) { functionName, exception ->
            val s = ExecutionScenario(
                scenario.initExecution,
                scenario.parallelExecution,
                emptyList()
            )
            return ValidationFailureInvocationResult(s, functionName, exception)
        }
        val afterParallelStateRepresentation = constructStateRepresentation()
        val dummyCompletion = Continuation<Any?>(EmptyCoroutineContext) {}
        var postPartSuspended = false
        val postResults = scenario.postExecution.mapIndexed { i, postActor ->
            // no actors are executed after suspension of a post part
            val result = if (postPartSuspended) {
                NoResult
            } else {
                // post part may contain suspendable actors if there aren't any in the parallel part, invoke with dummy continuation
                executeActor(testInstance, postActor, dummyCompletion).also {
                    postPartSuspended = it.wasSuspended
                }
            }
            executeValidationFunctions(testInstance, validationFunctions) { functionName, exception ->
                val s = ExecutionScenario(
                    scenario.initExecution,
                    scenario.parallelExecution,
                    scenario.postExecution.subList(0, i + 1)
                )
                return ValidationFailureInvocationResult(s, functionName, exception)
            }
            result
        }
        val afterPostStateRepresentation = constructStateRepresentation()
        val results = ExecutionResult(
            initResults, afterInitStateRepresentation,
            parallelResultsWithClock, afterParallelStateRepresentation,
            postResults, afterPostStateRepresentation
        )
        return CompletedInvocationResult(results)
    }

    override fun close() {
        super.close()
        executor.close()
    }
}