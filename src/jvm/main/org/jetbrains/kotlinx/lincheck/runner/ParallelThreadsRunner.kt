/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.runner

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.CancellationResult.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.ExecutionPart.*
import org.jetbrains.kotlinx.lincheck.runner.UseClocks.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingStrategy
import org.jetbrains.lincheck.jvm.agent.LincheckJavaAgent
import org.jetbrains.lincheck.util.SpinnerGroup
import org.jetbrains.lincheck.util.ensure
import org.jetbrains.lincheck.util.runInsideIgnoredSection
import org.jetbrains.lincheck.util.runOutsideIgnoredSection
import sun.nio.ch.lincheck.*
import java.lang.reflect.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*
import kotlin.random.*

private typealias SuspensionPointResultWithContinuation = AtomicReference<Pair<kotlin.Result<Any?>, Continuation<Any?>>>

/**
 * This runner executes parallel scenario part in different threads.
 * Supports running scenarios with `suspend` functions.
 *
 * It is pretty useful for stress testing or if you do not care about context switch expenses.
 */
internal open class ParallelThreadsRunner(
    strategy: Strategy,
    testClass: Class<*>,
    validationFunction: Actor?,
    stateRepresentationFunction: Method?,
    private val timeoutMs: Long, // for deadlock or livelock detection
    private val useClocks: UseClocks // specifies whether `HBClock`-s should always be used or with some probability
) : Runner(strategy, testClass, validationFunction, stateRepresentationFunction) {
    private val testName = testClass.simpleName
    internal val executor = FixedActiveThreadsExecutor(testName, scenario.nThreads) // should be closed in `close()`

    private val spinners = SpinnerGroup(executor.threads.size)

    internal lateinit var testInstance: Any

    private var suspensionPointResults = List(scenario.nThreads) { t ->
        MutableList<Result>(scenario.threads[t].size) { NoResult }
    }

    private val completions = List(scenario.nThreads) { t ->
        List(scenario.threads[t].size) { actorId -> Completion(t, actorId) }
    }

    // These completion statuses are updated atomically on resumptions and cancellations.
    // Due to prompt cancellation, resumption and cancellation can happen concurrently,
    // so that we need to synchronize them somehow. In order to update `completedOrSuspendedThreads`
    // consistently, we atomically change the status from `null` to `RESUMED` or `CANCELLED` and
    // update the counter on failure -- thus, synchronizing the threads.
    private lateinit var completionStatuses: List<AtomicReferenceArray<CompletionStatus>>
    private fun trySetResumedStatus(iThread: Int, actorId: Int) = completionStatuses[iThread].compareAndSet(actorId, null, CompletionStatus.RESUMED)
    private fun trySetCancelledStatus(iThread: Int, actorId: Int) = completionStatuses[iThread].compareAndSet(actorId, null, CompletionStatus.CANCELLED)

    private val uninitializedThreads = AtomicInteger(scenario.nThreads) // for threads synchronization

    private val initialPartExecution: TestThreadExecution? = createInitialPartExecution()
    private val parallelPartExecutions: Array<TestThreadExecution> = createParallelPartExecutions()
    private val postPartExecution: TestThreadExecution? = createPostPartExecution()
    private val validationPartExecution: TestThreadExecution? = createValidationPartExecution(validationFunction)

    private val testThreadExecutions: List<TestThreadExecution> = listOfNotNull(
        initialPartExecution,
        *parallelPartExecutions,
        postPartExecution
    )

    init {
        resetState()
    }

    /**
     * Passed as continuation to invoke the suspendable actor from [iThread].
     *
     * If the suspendable actor has follow-up then it's continuation is intercepted after resumption
     * by [ParallelThreadRunnerInterceptor] stored in [context]
     * and [Completion] instance will hold the resumption result and reference to the unintercepted continuation in [resWithCont].
     *
     * [resumeWith] is invoked when the coroutine running this actor completes with result or exception.
     */
    protected inner class Completion(private val iThread: Int, private val actorId: Int) : Continuation<Any?> {
        val resWithCont = SuspensionPointResultWithContinuation(null)

        override var context = ParallelThreadRunnerInterceptor(resWithCont) + StoreExceptionHandler() + Job()

        // We need to run this code in an ignored section,
        // as it is called in the testing code but should not be analyzed.
        override fun resumeWith(result: kotlin.Result<Any?>) = runInsideIgnoredSection {
            // decrement completed or suspended threads only if the operation was not cancelled and
            // the continuation was not intercepted; it was already decremented before writing `resWithCont` otherwise
            if (!result.cancelledByLincheck()) {
                if (resWithCont.get() === null) {
                    completedOrSuspendedThreads.decrementAndGet()
                    if (!trySetResumedStatus(iThread, actorId)) {
                        // already cancelled via prompt cancellation, increment the counter back
                        completedOrSuspendedThreads.incrementAndGet()
                    }
                }
                // write function's final result
                suspensionPointResults[iThread][actorId] = createLincheckResult(result)
            }
        }

        fun reset() {
            resWithCont.set(null)
            context = ParallelThreadRunnerInterceptor(resWithCont) + StoreExceptionHandler() + Job()
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

            // We need to run this code in an ignored section,
            // as it is called in the testing code but should not be analyzed.
            override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
                runInsideIgnoredSection {
                    return Continuation(StoreExceptionHandler() + Job()) { result ->
                        runInsideIgnoredSection {
                            // decrement completed or suspended threads only if the operation was not cancelled
                            if (!result.cancelledByLincheck()) {
                                completedOrSuspendedThreads.decrementAndGet()
                                if (!trySetResumedStatus(iThread, actorId)) {
                                    // already cancelled via prompt cancellation, increment the counter back
                                    completedOrSuspendedThreads.incrementAndGet()
                                }
                                @Suppress("UNCHECKED_CAST")
                                resWithCont.set(result to continuation as Continuation<Any?>)
                            }
                        }
                    }
                }
        }
    }

    private fun resetState() {
        suspensionPointResults.forEach { it.fill(NoResult) }
        completedOrSuspendedThreads.set(0)
        completions.forEach {
            it.forEach { completion ->
                // As we're using the same instances of Completion during multiply invocations,
                // it's context may collect some data,
                // which lead to non-determinism in a subsequent invocation.
                // To avoid this, we reset its context.
                completion.reset()
            }
        }
        completionStatuses = List(scenario.nThreads) { t ->
            AtomicReferenceArray<CompletionStatus>(scenario.parallelExecution[t].size)
        }
        uninitializedThreads.set(scenario.nThreads)
        // reset stored continuations
        executor.threads.forEach { it.suspendedContinuation = null }
        // reset thread executions
        testThreadExecutions.forEach { it.reset() }
        validationPartExecution?.results?.fill(null)
        resetEventTracker()
    }

    private var ensuredTestInstanceIsTransformed = false

    private fun createTestInstance() {
        testInstance = testClass.newDefaultInstance()
        if (strategy is ModelCheckingStrategy) {
            // In the model checking mode, we need to ensure
            // that all the necessary classes and instrumented
            // after creating a test instance.
            if (!ensuredTestInstanceIsTransformed) {
                LincheckJavaAgent.ensureObjectIsTransformed(testInstance)
                ensuredTestInstanceIsTransformed = true
            }
        }
        testThreadExecutions.forEach { it.testInstance = testInstance }
        validationPartExecution?.let { it.testInstance = testInstance }
    }

    /**
     * Processes the result obtained after the corresponding actor invocation.
     * If the actor has been suspended then the corresponding thread waits in a busy-wait loop
     * for either being resumed by another thread or for the moment when all threads either
     * completed their execution or suspended with no chance to be resumed.
     * Otherwise if the invoked actor completed without suspension, then it just writes it's final result.
     */
    @Suppress("unused")
    fun processInvocationResult(res: Any?, iThread: Int, actorId: Int): Result = runInsideIgnoredSection {
        val finalResult = if (res !== COROUTINE_SUSPENDED) {
            createLincheckResult(res)
        } else {
            val thread = Thread.currentThread() as TestThread
            val actor = scenario.parallelExecution[iThread][actorId]
            val cont = thread.suspendedContinuation.also { thread.suspendedContinuation = null }
            if (actor.cancelOnSuspension && cont !== null &&
                cancelByLincheck(cont as CancellableContinuation<*>, actor.promptCancellation) != CANCELLATION_FAILED
            ) {
                if (!trySetCancelledStatus(iThread, actorId)) {
                    // already resumed, increment `completedOrSuspendedThreads` back
                    completedOrSuspendedThreads.incrementAndGet()
                }
                Cancelled
            } else {
                waitAndInvokeFollowUp(thread, actorId)
            }
        }
        val isLastActor = actorId == scenario.parallelExecution[iThread].size - 1
        if (isLastActor && finalResult !== Suspended)
            completedOrSuspendedThreads.incrementAndGet()
        suspensionPointResults[iThread][actorId] = NoResult
        return finalResult
    }

    override fun afterCoroutineCancelled(iThread: Int) {}

    // We need to run this code in an ignored section,
    // as it is called in the testing code but should not be analyzed.
    private fun waitAndInvokeFollowUp(thread: TestThread, actorId: Int): Result = runInsideIgnoredSection {
        val threadId = thread.threadId
        // Coroutine is suspended. Call method so that strategy can learn it.
        afterCoroutineSuspended(threadId)
        // If the suspended method call has a follow-up part after this suspension point,
        // then wait for the resuming thread to write a result of this suspension point
        // as well as the continuation to be executed by this thread;
        // wait for the final result of the method call otherwise.
        val completion = completions[threadId][actorId]
        // Check if the coroutine is already resumed and if not, enter the spin loop.
        if (!isCoroutineResumed(threadId, actorId)) {
            spinners[threadId].spinWaitUntil {
                // Check whether the scenario is completed and the current suspended operation cannot be resumed.
                if (currentExecutionPart == POST || isParallelExecutionCompleted) {
                    suspensionPointResults[threadId][actorId] = NoResult
                    return Suspended
                }
                // Wait until coroutine is resumed.
                isCoroutineResumed(threadId, actorId)
            }
        }
        // Coroutine will be resumed. Call method so that strategy can learn it.
        afterCoroutineResumed(threadId)
        // Check whether the result of the suspension point with the continuation has been stored
        // by the resuming thread, and invoke the follow-up part in this case
        val suspendResultToContinuation = completion.resWithCont.get()
        if (suspendResultToContinuation !== null) {
            // Suspended thread got result of the suspension point and continuation to resume
            val (resumedValue, continuation) = suspendResultToContinuation
            // Erase the current result
            completion.resWithCont.set(null)
            // We must exit the ignored section to keep tracking events in the resumption.
            runOutsideIgnoredSection {
                // Resume the execution of the coroutine.
                continuation.resumeWith(resumedValue)
            }
        }
        // If we've suspended again - then clean the completion status and rerun all the logic of this method to
        // wait for resumption.
        if (suspensionPointResults[threadId][actorId] == NoResult) {
            completionStatuses[threadId].set(actorId, null)
            return waitAndInvokeFollowUp(thread, actorId)
        }
        return suspensionPointResults[threadId][actorId]
    }

    /**
     * This method is used for communication between `ParallelThreadsRunner` and `ManagedStrategy` via overriding,
     * so that runner does not know about managed strategy details.
     */
    internal open fun <T> cancelByLincheck(
        cont: CancellableContinuation<T>,
        promptCancellation: Boolean
    ): CancellationResult =
        cont.cancelByLincheck(promptCancellation)

    override fun afterCoroutineSuspended(iThread: Int) {
        completedOrSuspendedThreads.incrementAndGet()
    }

    override fun afterCoroutineResumed(iThread: Int) {}

    // We cannot use `completionStatuses` here since
    // they are set _before_ the result is published.
    override fun isCoroutineResumed(iThread: Int, actorId: Int) =
        suspensionPointResults[iThread][actorId] != NoResult || completions[iThread][actorId].resWithCont.get() != null

    override fun run(): InvocationResult {
        var timeout = timeoutMs * 1_000_000
        try {
            // Create a new testing class instance.
            createTestInstance()
            // Set the event tracker.
            setEventTracker()
            // Execute the initial part.
            initialPartExecution?.let {
                beforePart(INIT)
                timeout -= executor.submitAndAwait(arrayOf(it), timeout)
            }
            onThreadSwitchesOrActorFinishes()
            val afterInitStateRepresentation = constructStateRepresentation()
            // Execute the parallel part.
            beforePart(PARALLEL)
            timeout -= executor.submitAndAwait(parallelPartExecutions, timeout)
            // Wait for all user threads to finish at the end of the parallel part
            timeout -= strategy.awaitUserThreads(timeout)
            val afterParallelStateRepresentation: String? = constructStateRepresentation()
            onThreadSwitchesOrActorFinishes()
            // Execute the post part.
            postPartExecution?.let {
                beforePart(POST)
                timeout -= executor.submitAndAwait(arrayOf(it), timeout)
            }
            val afterPostStateRepresentation = constructStateRepresentation()
            // Execute validation functions
            validationPartExecution?.let { validationPart ->
                beforePart(VALIDATION)
                executor.submitAndAwait(arrayOf(validationPart), timeout)
                val validationResult = validationPart.results.single()
                if (validationResult is ExceptionResult) {
                    return ValidationFailureInvocationResult(scenario, validationResult.throwable, collectExecutionResults())
                }
            }
            // Combine the results and convert them for the standard class loader (if they are of non-primitive types).
            // We do not want the transformed code to be reachable outside of the runner and strategy classes.
            return CompletedInvocationResult(collectExecutionResults(afterInitStateRepresentation, afterParallelStateRepresentation, afterPostStateRepresentation))
        } catch (e: TimeoutException) {
            return RunnerTimeoutInvocationResult()
        } catch (e: ExecutionException) {
            // In case when invocation raised an exception,
            // we need to wait for all user threads to finish before continuing.
            // In case if waiting for threads termination abrupt with the timeout,
            // we return `RunnerTimeoutInvocationResult`.
            // Otherwise, we return `UnexpectedExceptionInvocationResult` with the original exception.
            runCatching { strategy.awaitUserThreads(timeout) }.onFailure { exception ->
                if (exception is TimeoutException) return RunnerTimeoutInvocationResult()
            }
            return UnexpectedExceptionInvocationResult(e.cause!!, collectExecutionResults())
        } finally {
            resetState()
        }
    }

    /**
     * This method is called when we have some execution result other than [CompletedInvocationResult].
     */
    fun collectExecutionResults(): ExecutionResult {
        return collectExecutionResults(null, null, null)
    }

    private fun collectExecutionResults(
        afterInitStateRepresentation: String?,
        afterParallelStateRepresentation: String?,
        afterPostStateRepresentation: String?
    ) = ExecutionResult(
        initResults = initialPartExecution?.results?.toList().orEmpty(),
        parallelResultsWithClock = parallelPartExecutions.map { execution ->
            execution.results.zip(execution.clocks).map {
                ResultWithClock(it.first, HBClock(it.second.clone()))
            }
        },
        postResults = postPartExecution?.results?.toList().orEmpty(),
        afterInitStateRepresentation = afterInitStateRepresentation,
        afterParallelStateRepresentation = afterParallelStateRepresentation,
        afterPostStateRepresentation = afterPostStateRepresentation
    )

    private fun setEventTracker() {
        if (strategy !is ManagedStrategy) return
        executor.threads.forEachIndexed { i, thread ->
            var descriptor = ThreadDescriptor.getThreadDescriptor(thread)
            if (descriptor == null) {
                descriptor = ThreadDescriptor(thread)
                ThreadDescriptor.setThreadDescriptor(thread, descriptor)
            }
            descriptor.eventTracker = strategy
            strategy.registerThread(thread, descriptor)
                .ensure { threadId -> threadId == i }
        }
    }

    private fun resetEventTracker() {
        if (strategy !is ManagedStrategy) return
        for (thread in executor.threads) {
            val descriptor = ThreadDescriptor.getThreadDescriptor(thread)
                ?: continue
            descriptor.eventTracker = null
        }
    }

    private fun RunnerTimeoutInvocationResult(): RunnerTimeoutInvocationResult {
        val threadDump = collectThreadDump(this)
        return RunnerTimeoutInvocationResult(threadDump, collectExecutionResults())
    }

    private fun createInitialPartExecution() =
        if (scenario.initExecution.isNotEmpty()) {
            TestThreadExecutionGenerator.create(this, INIT_THREAD_ID,
                scenario.initExecution,
                emptyList(),
                false
            ).apply {
                initialize(
                    nActors = scenario.initExecution.size,
                    nThreads = 1,
                )
                allThreadExecutions = arrayOf(this)
            }
        } else {
            null
        }

    private fun createPostPartExecution() =
        if (scenario.postExecution.isNotEmpty()) {
            val dummyCompletion = Continuation<Any?>(EmptyCoroutineContext) {}
            TestThreadExecutionGenerator.create(this, POST_THREAD_ID,
                scenario.postExecution,
                Array(scenario.postExecution.size) { dummyCompletion }.toList(),
                scenario.hasSuspendableActors
            ).apply {
                initialize(
                    nActors = scenario.postExecution.size,
                    nThreads = 1,
                )
                allThreadExecutions = arrayOf(this)
            }
        } else {
            null
        }

    private fun createValidationPartExecution(validationFunction: Actor?): TestThreadExecution? {
        if (validationFunction == null) return null
        return TestThreadExecutionGenerator.create(this, VALIDATION_THREAD_ID, listOf(validationFunction), emptyList(), false)
            .also { it.initialize(nActors = 1, nThreads = 1) }
    }

    private fun createParallelPartExecutions(): Array<TestThreadExecution> = Array(scenario.nThreads) { iThread ->
        TestThreadExecutionGenerator.create(this, iThread,
            scenario.parallelExecution[iThread],
            completions[iThread],
            scenario.hasSuspendableActors
        )
    }.apply { forEachIndexed { iThread, execution ->
        execution.initialize(
            nActors = scenario.parallelExecution[iThread].size,
            nThreads = scenario.nThreads
        )
        execution.allThreadExecutions = this
    }}

    private fun TestThreadExecution.initialize(nActors: Int, nThreads: Int) {
        results = arrayOfNulls(nActors)
        clocks = Array(nActors) { emptyClockArray(nThreads) }
    }

    private fun TestThreadExecution.reset() {
        val runner = this@ParallelThreadsRunner
        results.fill(null)
        useClocks = if (runner.useClocks == ALWAYS) true else Random.nextBoolean()
        clocks.forEach { it.fill(0) }
        curClock = 0
    }

    override fun onThreadStart(iThread: Int) {
        if (currentExecutionPart !== PARALLEL) return
        uninitializedThreads.decrementAndGet() // this thread has finished initialization
        // wait for other threads to start
        spinners[iThread].spinWaitUntil { uninitializedThreads.get() == 0 }
    }

    override fun constructStateRepresentation() =
        stateRepresentationFunction?.invoke(testInstance) as String?

    override fun close() {
        super.close()
        executor.close()
    }

    override fun isCurrentRunnerThread(thread: Thread): Boolean = executor.threads.any { it === thread }

    override fun onThreadFinish(iThread: Int) {}
    
    fun getActorResult(iThread: Int, actorId: Int): Result? {
        if (iThread != 0) return parallelPartExecutions[iThread].results.getOrNull(actorId)
        
        val initSize = initialPartExecution?.results?.size ?: 0
        val parSize =  parallelPartExecutions[0].results.size
        val postSize = postPartExecution?.results?.size ?: 0
        
        if (actorId < initSize) return initialPartExecution?.results?.getOrNull(actorId)
        if (actorId < parSize + initSize) return parallelPartExecutions[iThread].results?.getOrNull(actorId - initSize)
        if (actorId < postSize + parSize + initSize) return postPartExecution?.results?.getOrNull(actorId - parSize - initSize)
        return validationPartExecution?.results?.getOrNull(actorId - postSize - parSize - initSize)
    }
}

internal enum class UseClocks { ALWAYS, RANDOM }

internal enum class CompletionStatus { CANCELLED, RESUMED }