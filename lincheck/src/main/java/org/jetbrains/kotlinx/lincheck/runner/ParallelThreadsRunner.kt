/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.runner

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import java.lang.Exception
import java.util.concurrent.*
import java.util.concurrent.Executors.newFixedThreadPool
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

private typealias SuspensionPointResultWithContinuation = AtomicReference<Pair<kotlin.Result<Any?>, Continuation<Any?>>>

/**
 * This runner executes parallel scenario part in different threads.
 * Supports running scenarios with `suspend` functions.
 *
 * It is pretty useful for stress testing or if you do not care about context switches.
 */
open class ParallelThreadsRunner(
    scenario: ExecutionScenario,
    strategy: Strategy,
    testClass: Class<*>,
    waits: List<IntArray>?
) : Runner(scenario, strategy, testClass) {
    private lateinit var testInstance: Any
    private val executor = newFixedThreadPool(scenario.threads, ParallelThreadsRunner::TestThread)

    private val completions = List(scenario.threads) { threadId ->
        List(scenario.parallelExecution[threadId].size) { Completion(threadId) }
    }

    private val testThreadExecutions = List(scenario.threads) { t ->
        TestThreadExecutionGenerator.create(this, t, scenario.parallelExecution[t], completions[t], false, scenario.hasSuspendableActors())
            .also { if (waits != null) it.waits = waits[t] }
    }

    private var suspensionPointResults = MutableList<Result>(scenario.threads) { NoResult }

    /**
     * Passed as continuation to invoke the suspendable actor from [threadId].
     *
     * If the suspendable actor has follow-up then it's continuation is intercepted after resumption
     * by [ParallelThreadRunnerInterceptor] stored in [context]
     * and [Completion] instance will hold the resumption result and reference to the unintercepted continuation in [resWithCont].
     *
     * [resumeWith] is invoked when the coroutine running this actor completes with result or exception.
     */
    protected inner class Completion(private val threadId: Int) : Continuation<Any?> {
        val resWithCont = SuspensionPointResultWithContinuation(null)

        override val context: CoroutineContext
            get() = ParallelThreadRunnerInterceptor(resWithCont)

        override fun resumeWith(result: kotlin.Result<Any?>) {
            // decrement completed or suspended threads only if continuation was not intercepted,
            // otherwise it was already decremented before writing resWithCont
            resWithCont.get() ?: completedOrSuspendedThreads.decrementAndGet()
            // write method's final result
            suspensionPointResults[threadId] = createLinCheckResult(result, wasSuspended = true)
        }
    }

    /**
     * When suspended actor is resumed by another thread
     * [ParallelThreadRunnerInterceptor.interceptContinuation] is called to intercept it's continuation.
     * Intercepted continuation just writes the result of the suspension point and reference to the unintercepted continuation
     * so that the calling thread could resume this continuation by itself.
     */
    private inner class ParallelThreadRunnerInterceptor(
        private var resWithCont: SuspensionPointResultWithContinuation
    ) : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
        override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
            return Continuation(EmptyCoroutineContext) { result ->
                completedOrSuspendedThreads.decrementAndGet()
                resWithCont.set(result to continuation as Continuation<Any?>)
            }
        }
    }

    private fun isThreadCompleted(threadId: Int, actorId: Int) = actorId == scenario.parallelExecution[threadId].size - 1

    private fun reset() {
        testInstance = testClass.newInstance()
        testThreadExecutions.forEach { ex -> ex.testInstance = testInstance }
        suspensionPointResults = MutableList(scenario.threads) { NoResult }
        completedOrSuspendedThreads.set(0)
        completions.forEach { it.forEach { it.resWithCont.set(null) } }
    }

    /**
     * Processes the result obtained after the corresponding actor invocation.
     * If the actor has been suspended then the corresponding thread waits in a busy-wait loop
     * for either being resumed by another thread or for the moment when all threads either
     * completed their execution or suspended with no chance to be resumed.
     * Otherwise if the invoked actor completed without suspension, then it just writes it's final result.
     */
    @Suppress("unused")
    fun processInvocationResult(res: Any?, threadId: Int, actorId: Int): Result {
        val finalResult: Result
        val completion = completions[threadId][actorId]
        if (res === COROUTINE_SUSPENDED) {
            completedOrSuspendedThreads.incrementAndGet()
            // Thread was suspended -> if suspended method call has follow-up after this suspension point
            // then wait for result of this suspension point
            // and continuation written by resuming thread to be executed by this thread,
            // or final result written by the resuming thread
            while (completion.resWithCont.get() === null && suspensionPointResults[threadId] === NoResult) {
                if (completedOrSuspendedThreads.get() == scenario.threads) {
                    // all threads were suspended or completed
                    suspensionPointResults[threadId] = NoResult
                    return Suspended
                }
            }
            if (suspensionPointResults[threadId] !== NoResult) {
                // Result of the suspension point equals to the final method call result
                // completion.resumeWith was called in the resuming thread, final result is written
                finalResult = suspensionPointResults[threadId]
            } else {
                // Suspended thread got result of the suspension point and continuation to resume
                val resumedValue = completion.resWithCont.get().first
                val cont = completion.resWithCont.get().second
                cont.resumeWith(resumedValue)
                finalResult = suspensionPointResults[threadId]
            }
        } else {
            finalResult = createLinCheckResult(res)
        }
        if (isThreadCompleted(threadId, actorId)) completedOrSuspendedThreads.incrementAndGet()
        suspensionPointResults[threadId] = NoResult
        return finalResult
    }

    override fun run(): ExecutionResult? {
        reset()
        val initResults = scenario.initExecution.map { initActor -> executeActor(testInstance, initActor) }
        val parallelResults = executor.invokeAll(testThreadExecutions).map { f ->
            try {
                f.get().toMutableList()
            } catch (e: Exception) { // Kotlin can't catch multiple exceptions :(((
                e.catch(InterruptedException::class.java, ExecutionException::class.java) {
                    throw IllegalStateException(e)
                }
            }
        }
        val dummyCompletion = Continuation<Any?>(EmptyCoroutineContext) {}
        var postPartSuspended = false
        val postResults = scenario.postExecution.map { postActor ->
            // no actors are executed after suspension of a post part
            if (postPartSuspended) {
                NoResult
            } else {
                // post part may contain suspendable actors if there aren't any in the parallel part, invoke with dummy continuation
                executeActor(testInstance, postActor, dummyCompletion).also { postPartSuspended = it.wasSuspended }
            }
        }
        return ExecutionResult(initResults, parallelResults, postResults)
    }

    override fun onStart(iThread: Int) {
        super.onStart(iThread)
        (Thread.currentThread() as TestThread).iThread = iThread
    }

    override fun close() {
        super.close()
        executor.shutdown()
    }

     // For [TestThreadExecution] instances
    class TestThread(r: Runnable) : Thread(r) {
        var iThread: Int = 0
    }
}
