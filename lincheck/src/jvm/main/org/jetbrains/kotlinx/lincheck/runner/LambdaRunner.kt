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

import org.jetbrains.kotlinx.lincheck.util.NoResult
import org.jetbrains.kotlinx.lincheck.execution.ResultWithClock
import org.jetbrains.kotlinx.lincheck.execution.emptyClock
import org.jetbrains.kotlinx.lincheck.execution.emptyExecutionResult
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy
import org.jetbrains.kotlinx.lincheck.util.toLincheckResult
import org.jetbrains.kotlinx.lincheck.util.threadMapOf
import sun.nio.ch.lincheck.ThreadDescriptor
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

internal class LambdaRunner<T>(
    private val timeoutMs: Long, // for deadlock or livelock detection
    val block: () -> T
) : AbstractActiveThreadPoolRunner() {

    companion object {
        // Ghetto secondary constructor
        operator fun invoke(timeoutMs: Long, block: Runnable): LambdaRunner<Unit> {
            return LambdaRunner(timeoutMs, { block.run() })
        }
    }

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

    private class LambdaWrapper<T>(val strategy: Strategy, val block: () -> T) : Runnable {
        var result: Result<T>? = null

        override fun run() {
            result = kotlin.runCatching {
                onStart()
                try {
                    block()
                } finally {
                    onFinish()
                }
            }
        }

        private fun onStart() {
            if (strategy !is ManagedStrategy) return
            strategy.beforePart(ExecutionPart.PARALLEL)
            val descriptor = ThreadDescriptor.getCurrentThreadDescriptor()
            if (descriptor != null) {
                strategy.beforeThreadRun(descriptor)
            }
        }

        private fun onFinish() {
            if (strategy !is ManagedStrategy) return
            val descriptor = ThreadDescriptor.getCurrentThreadDescriptor()
            if (descriptor != null) {
                strategy.afterThreadRunReturn(descriptor)
            }
        }
    }

    // TODO: currently we have to use `ExecutionResult`,
    //   even though in case of `LambdaRunner` the result can be simplified
    private fun collectExecutionResults(wrapper: LambdaWrapper<T>) =
        emptyExecutionResult().copy(
            parallelResultsWithClock = listOf(listOf(
                ResultWithClock(wrapper.result?.toLincheckResult() ?: NoResult, emptyClock(1))
            ))
        )

    private fun RunnerTimeoutInvocationResult(wrapper: LambdaWrapper<T>): RunnerTimeoutInvocationResult {
        val threadDump = collectThreadDump()
        return RunnerTimeoutInvocationResult(threadDump, collectExecutionResults(wrapper))
    }
}