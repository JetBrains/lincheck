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

import kotlinx.cinterop.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import kotlin.coroutines.*
import kotlin.native.concurrent.*
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
    initThreadFunction: (() -> Unit)?,
    finishThreadFunction: (() -> Unit)?) : Runner(strategy, testClass, validationFunctions, stateRepresentationFunction) {
    private val runnerHash = this.hashCode() // helps to distinguish this runner threads from others

    private val executor = FixedActiveThreadsExecutor(scenario.threads, runnerHash, initThreadFunction, finishThreadFunction).freeze() // should be closed in `close()`

    private val testThreadExecutions = AtomicReference<LincheckAtomicArray<TestThreadExecution>>(LincheckAtomicArray(0))

    init {
        this.ensureNeverFrozen()
    }

    override fun initialize() {
        super.initialize()
        val arr = Array(scenario.threads) { t ->
            TestThreadExecution(t, scenario.parallelExecution[t]).freeze()
        }
        testThreadExecutions.value = arr.toLincheckAtomicArray()
        testThreadExecutions.value.toArray().forEach { it.allThreadExecutions.value = testThreadExecutions.value }
    }

    private fun reset() {
        testThreadExecutions.value.toArray().forEachIndexed { t, ex ->
            val threads = scenario.threads
            val actors = scenario.parallelExecution[t].size
            ex.useClocks.value = if (useClocks == UseClocks.ALWAYS) 1 else (if (Random.nextBoolean()) 1 else 0)
            ex.curClock.value = 0
            ex.clocks.value = Array(actors) { emptyClockArray(threads) }.toLincheckAtomicArray()
            ex.results.value = LincheckAtomicArray(actors)
        }
        completedOrSuspendedThreads.set(0)
    }

    override fun constructStateRepresentation(): String? {
        throw RuntimeException("should not be called")
    }

    fun nativeConstructStateRepresentation(testInstance: Any) =
        stateRepresentationFunction?.function?.invoke(testInstance) as String?

    override fun run(): InvocationResult {
        reset()
        val testInstance = testClass.createInstance()
        testInstance.ensureNeverFrozen()
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
        val afterInitStateRepresentation = nativeConstructStateRepresentation(testInstance)
        try {
            executor.submitAndAwait(testThreadExecutions.value.toArray().map { NativeTestThreadExecution(testInstance, it) }.toTypedArray(), timeoutMs)
        } catch (e: LincheckTimeoutException) {
            val threadDump = collectThreadDump(this)
            return DeadlockInvocationResult(threadDump)
        } catch (e: LincheckExecutionException) {
            return UnexpectedExceptionInvocationResult(e.cause!!)
        }
        val parallelResultsWithClock = testThreadExecutions.value.toArray().map { ex ->
            ex.results.value.toArray().zip(ex.clocks.value.toArray()).map { ResultWithClock(it.first!!, HBClock(it.second)) }
        }
        executeValidationFunctions(testInstance, validationFunctions) { functionName, exception ->
            val s = ExecutionScenario(
                scenario.initExecution,
                scenario.parallelExecution,
                emptyList()
            )
            return ValidationFailureInvocationResult(s, functionName, exception)
        }
        val afterParallelStateRepresentation = nativeConstructStateRepresentation(testInstance)
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
        val afterPostStateRepresentation = nativeConstructStateRepresentation(testInstance)
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