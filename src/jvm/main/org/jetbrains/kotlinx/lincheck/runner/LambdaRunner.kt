/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.runner

import org.jetbrains.kotlinx.lincheck.NoResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ResultWithClock
import org.jetbrains.kotlinx.lincheck.execution.emptyClock
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy
import org.jetbrains.kotlinx.lincheck.toLinCheckResult
import org.jetbrains.kotlinx.lincheck.util.threadMapOf
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

internal class LambdaRunner(
    private val timeoutMs: Long, // for deadlock or livelock detection
    val block: Runnable
) : AbstractActiveThreadPoolRunner() {

    private val testName =
        runCatching { block.javaClass.simpleName }.getOrElse { "lambda" }

    override val executor = ActiveThreadPoolExecutor(testName, 1)

    override fun runInvocation(): InvocationResult {
        var timeout = timeoutMs * 1_000_000
        val wrapper = LambdaWrapper(strategy, block)
        try {
            setEventTracker()
            val tasks = threadMapOf(0 to wrapper)
            try {
                timeout -= executor.submitAndAwait(tasks, timeout)
            } finally {
                timeout -= strategy.awaitUserThreads(timeout)
            }
            return CompletedInvocationResult(collectExecutionResults(wrapper))
        } catch (_: TimeoutException) {
            return RunnerTimeoutInvocationResult(wrapper)
        } catch (e: ExecutionException) {
            return UnexpectedExceptionInvocationResult(e.cause!!, collectExecutionResults(wrapper))
        } finally {
            resetEventTracker()
        }
    }

    private class LambdaWrapper(val strategy: Strategy, val block: Runnable) : Runnable {
        var result: kotlin.Result<Unit>? = null

        override fun run() {
            result = kotlin.runCatching {
                onStart()
                try {
                    block.run()
                } finally {
                    onFinish()
                }
            }
        }

        private fun onStart() {
            if (strategy !is ManagedStrategy) return
            strategy.beforePart(ExecutionPart.PARALLEL)
            strategy.beforeThreadStart()
        }

        private fun onFinish() {
            if (strategy !is ManagedStrategy) return
            strategy.afterThreadFinish()
        }
    }

    // TODO: currently we have to use `ExecutionResult`,
    //   even though in case of `LambdaRunner` the result can be simplified
    private fun collectExecutionResults(wrapper: LambdaWrapper) = ExecutionResult(
        parallelResultsWithClock = listOf(listOf(
            ResultWithClock(wrapper.result?.toLinCheckResult() ?: NoResult, emptyClock(1))
        )),
        initResults = listOf(),
        postResults = listOf(),
        afterInitStateRepresentation = null,
        afterParallelStateRepresentation = null,
        afterPostStateRepresentation = null,
    )

    private fun RunnerTimeoutInvocationResult(wrapper: LambdaWrapper): RunnerTimeoutInvocationResult {
        val threadDump = collectThreadDump()
        return RunnerTimeoutInvocationResult(threadDump, collectExecutionResults(wrapper))
    }
}