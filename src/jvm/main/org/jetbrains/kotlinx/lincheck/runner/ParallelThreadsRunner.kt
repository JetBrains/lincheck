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
import org.jetbrains.kotlinx.lincheck.runner.FixedActiveThreadsExecutor.*
import org.jetbrains.kotlinx.lincheck.runner.ParallelThreadsRunner.Completion.*
import org.jetbrains.kotlinx.lincheck.runner.UseClocks.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy
import org.objectweb.asm.*
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
    validationFunctions: List<Method>,
    stateRepresentationFunction: Method?,
    private val timeoutMs: Long, // for deadlock or livelock detection
    private val useClocks: UseClocks // specifies whether `HBClock`-s should always be used or with some probability
) : Runner(strategy, testClass, validationFunctions, stateRepresentationFunction) {
    private val runnerHash = this.hashCode() // helps to distinguish this runner threads from others
    private val executor = FixedActiveThreadsExecutor(scenario.nThreads, runnerHash) // should be closed in `close()`

    private lateinit var testInstance: Any

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
    private var yieldInvokedInOnStart = false

    var currentExecutionPart: ExecutionPart? = null
        private set

    private var initialPartExecution: InitTestThreadExecution? = null
    private var parallelPartExecutions: Array<TestThreadExecution> = arrayOf()
    private var postPartExecution: PostTestThreadExecution? = null

    private var testThreadExecutions: List<TestThreadExecution> = listOf()

    override fun initialize() {
        super.initialize()
        initialPartExecution = createInitialPartExecution()
        parallelPartExecutions = createParallelPartExecutions()
        postPartExecution = createPostPartExecution()
        testThreadExecutions = listOfNotNull(
            initialPartExecution,
            *parallelPartExecutions,
            postPartExecution
        )
        reset()
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

        override val context = ParallelThreadRunnerInterceptor(resWithCont) + StoreExceptionHandler() + Job()

        override fun resumeWith(result: kotlin.Result<Any?>) {
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
                suspensionPointResults[iThread][actorId] = createLincheckResult(result, wasSuspended = true)
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
                return Continuation(StoreExceptionHandler() + Job()) { result ->
                    // decrement completed or suspended threads only if the operation was not cancelled
                    if (!result.cancelledByLincheck()) {
                        completedOrSuspendedThreads.decrementAndGet()
                        if (!trySetResumedStatus(iThread, actorId)) {
                            // already cancelled via prompt cancellation, increment the counter back
                            completedOrSuspendedThreads.incrementAndGet()
                        }
                        resWithCont.set(result to continuation as Continuation<Any?>)
                    }
                }
            }
        }
    }

    private fun reset() {
        suspensionPointResults.forEach { it.fill(NoResult) }
        completedOrSuspendedThreads.set(0)
        completions.forEach { it.forEach { it.resWithCont.set(null) } }
        completionStatuses = List(scenario.nThreads) { t ->
            AtomicReferenceArray<CompletionStatus>(scenario.parallelExecution[t].size)
        }
        uninitializedThreads.set(scenario.nThreads)
        // reset stored continuations
        executor.threads.forEach { it.cont = null }
        // reset phase
        currentExecutionPart = null
        // reset thread executions
        testThreadExecutions.forEach { it.reset() }
    }

    private fun createTestInstance() {
        testInstance = testClass.newInstance()
        testThreadExecutions.forEach { it.testInstance = testInstance }
    }

    /**
     * Processes the result obtained after the corresponding actor invocation.
     * If the actor has been suspended then the corresponding thread waits in a busy-wait loop
     * for either being resumed by another thread or for the moment when all threads either
     * completed their execution or suspended with no chance to be resumed.
     * Otherwise if the invoked actor completed without suspension, then it just writes it's final result.
     */
    @Suppress("unused")
    fun processInvocationResult(res: Any?, iThread: Int, actorId: Int): Result {
        val actor = scenario.parallelExecution[iThread][actorId]
        val finalResult = if (res === COROUTINE_SUSPENDED) {
            val t = Thread.currentThread() as TestThread
            val cont = t.cont.also { t.cont = null }
            if (actor.cancelOnSuspension && cont !== null && cancelByLincheck(cont, actor.promptCancellation) != CANCELLATION_FAILED) {
                if (!trySetCancelledStatus(iThread, actorId)) {
                    // already resumed, increment `completedOrSuspendedThreads` back
                    completedOrSuspendedThreads.incrementAndGet()
                }
                Cancelled
            } else waitAndInvokeFollowUp(iThread, actorId)
        } else createLincheckResult(res)
        val isLastActor = actorId == scenario.parallelExecution[iThread].size - 1
        if (isLastActor && finalResult !== Suspended)
            completedOrSuspendedThreads.incrementAndGet()
        suspensionPointResults[iThread][actorId] = NoResult
        return finalResult
    }

    private fun waitAndInvokeFollowUp(iThread: Int, actorId: Int): Result {
        // Coroutine is suspended. Call method so that strategy can learn it.
        afterCoroutineSuspended(iThread)
        // Tf the suspended method call has a follow-up part after this suspension point,
        // then wait for the resuming thread to write a result of this suspension point
        // as well as the continuation to be executed by this thread;
        // wait for the final result of the method call otherwise.
        val completion = completions[iThread][actorId]
        var i = 1
        while (!isCoroutineResumed(iThread, actorId)) {
            // Check whether the scenario is completed and the current suspended operation cannot be resumed.
            if (completedOrSuspendedThreads.get() == scenario.nThreads) {
                suspensionPointResults[iThread][actorId] = NoResult
                return Suspended
            }
            // Do not call `yield()` until necessary. However, if the number of threads
            // exceed the number of cores, it is better to call `yield` immediately to avoid starvation.
            if (i++ % SPINNING_LOOP_ITERATIONS_BEFORE_YIELD == 0 || executor.numberOfThreadsExceedAvailableProcessors){
                Thread.yield()
            }
        }
        // Coroutine will be resumed. Call method so that strategy can learn it.
        afterCoroutineResumed(iThread)
        // Check whether the result of the suspension point with the continuation has been stored
        // by the resuming thread, and invoke the follow-up part in this case
        if (completion.resWithCont.get() !== null) {
            // Suspended thread got result of the suspension point and continuation to resume
            val resumedValue = completion.resWithCont.get().first
            completion.resWithCont.get().second.resumeWith(resumedValue)
        }
        return suspensionPointResults[iThread][actorId]
    }

    /**
     * This method is used for communication between `ParallelThreadsRunner` and `ManagedStrategy` via overriding,
     * so that runner do not know about managed strategy details.
     */
    internal open fun <T> cancelByLincheck(cont: CancellableContinuation<T>, promptCancellation: Boolean): CancellationResult =
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
        try {
            var timeout = timeoutMs * 1_000_000
            // create new tested class instance
            createTestInstance()
            // execute initial part
            initialPartExecution?.run {
                currentExecutionPart = ExecutionPart.INIT
                beforePart(ExecutionPart.INIT)
                timeout -= executor.submitAndAwait(arrayOf(this), timeout)
                validationFailure?.let { return it }
            }
            // execute parallel part
            currentExecutionPart = ExecutionPart.PARALLEL
            beforePart(ExecutionPart.PARALLEL)
            timeout -= executor.submitAndAwait(parallelPartExecutions, timeout)
            // execute post part
            postPartExecution?.run {
                currentExecutionPart = ExecutionPart.POST
                beforePart(ExecutionPart.POST)
                timeout -= executor.submitAndAwait(arrayOf(this), timeout)
                validationFailure?.let { return it }
            }
            // Combine the results and convert them for the standard class loader (if of non-primitive types).
            // We do not want the byte-code transformation to be known outside of runner and strategy classes.
            return CompletedInvocationResult(
                ExecutionResult(
                    initResults = initialPartExecution?.results?.toList().orEmpty(),
                    parallelResultsWithClock = parallelPartExecutions.map { execution ->
                        execution.results.zip(execution.clocks).map {
                            ResultWithClock(it.first, HBClock(it.second.clone()))
                        }
                    },
                    postResults = postPartExecution?.results?.toList().orEmpty(),
                    afterInitStateRepresentation = initialPartExecution?.stateRepresentation,
                    afterParallelStateRepresentation = postPartExecution?.afterParallelStateRepresentation,
                    afterPostStateRepresentation = postPartExecution?.afterPostStateRepresentation
                ).convertForLoader(LinChecker::class.java.classLoader)
            )
        } catch (e: TimeoutException) {
            val threadDump = collectThreadDump(this)
            return DeadlockInvocationResult(threadDump)
        } catch (e: ExecutionException) {
            return UnexpectedExceptionInvocationResult(e.cause!!)
        } finally {
            reset()
        }
    }

    private val hasNonTrivialInitialPart: Boolean =
        scenario.initExecution.isNotEmpty() ||
        validationFunctions.isNotEmpty() ||
        stateRepresentationFunction != null

    private fun createInitialPartExecution() =
        if (hasNonTrivialInitialPart) InitTestThreadExecution() else null

    inner class InitTestThreadExecution : TestThreadExecution(INIT_THREAD_ID) {

        var validationFailure: ValidationFailureInvocationResult? = null

        var stateRepresentation: String? = null

        init {
            initialize(nActors = scenario.initExecution.size, nThreads = 0)
        }

        override fun run() {
            scenario.initExecution.mapIndexed { i, actor ->
                onActorStart(INIT_THREAD_ID)
                results[i] = executeActor(testInstance, actor)
                executeInIgnoredSection {
                    executeValidationFunctions(testInstance, validationFunctions) { functionName, exception ->
                        validationFailure = ValidationFailureInvocationResult(
                            ExecutionScenario(
                                scenario.initExecution.subList(0, i + 1),
                                emptyList(),
                                emptyList()
                            ),
                            functionName,
                            exception
                        )
                    }
                }
            }
            stateRepresentation = constructStateRepresentation()
        }
    }

    private val hasNonTrivialPostPart: Boolean =
        scenario.postExecution.isNotEmpty() ||
        validationFunctions.isNotEmpty() ||
        stateRepresentationFunction != null

    private fun createPostPartExecution() =
        if (hasNonTrivialPostPart) PostTestThreadExecution() else null

    inner class PostTestThreadExecution : TestThreadExecution(POST_THREAD_ID) {

        var validationFailure: ValidationFailureInvocationResult? = null

        var afterParallelStateRepresentation: String? = null
        var afterPostStateRepresentation: String? = null

        init {
            initialize(nActors = scenario.postExecution.size, nThreads = 0)
        }

        override fun run() {
            // (1): execute validation functions and construct state representation after parallel part
            executeInIgnoredSection {
                executeValidationFunctions(testInstance, validationFunctions) { functionName, exception ->
                    validationFailure = ValidationFailureInvocationResult(
                        ExecutionScenario(
                            scenario.initExecution,
                            scenario.parallelExecution,
                            emptyList()
                        ),
                        functionName,
                        exception
                    )
                }
            }
            afterParallelStateRepresentation = constructStateRepresentation()
            if (validationFailure != null)
                return
            // (2): execute post part and validation functions, then construct state representation after post part
            val dummyCompletion = Continuation<Any?>(EmptyCoroutineContext) {}
            var suspended = false
            scenario.postExecution.mapIndexed { i, actor ->
                results[i] = if (suspended) {
                    NoResult
                } else {
                    // post part may contain suspendable actors if there aren't any in the parallel part,
                    // invoke with dummy continuation
                    onActorStart(POST_THREAD_ID)
                    executeActor(testInstance, actor, dummyCompletion).also {
                        suspended = it === Suspended
                    }
                }
                executeInIgnoredSection {
                    executeValidationFunctions(testInstance, validationFunctions) { functionName, exception ->
                        validationFailure = ValidationFailureInvocationResult(
                            ExecutionScenario(
                                scenario.initExecution,
                                scenario.parallelExecution,
                                scenario.postExecution.subList(0, i + 1)
                            ),
                            functionName,
                            exception
                        )
                    }
                }
            }
            afterPostStateRepresentation = constructStateRepresentation()
        }
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

    override fun onStart(iThread: Int) {
        super.onStart(iThread)
        uninitializedThreads.decrementAndGet() // this thread has finished initialization
        // wait for other threads to start
        var i = 1
        while (uninitializedThreads.get() != 0) {
            if (i % SPINNING_LOOP_ITERATIONS_BEFORE_YIELD == 0) {
                yieldInvokedInOnStart = true
                Thread.yield()
            }
            i++
        }
    }

    override fun needsTransformation() = true
    override fun createTransformer(cv: ClassVisitor) = CancellabilitySupportClassTransformer(cv)

    override fun constructStateRepresentation() =
        stateRepresentationFunction?.let{ getMethod(testInstance, it) }?.invoke(testInstance) as String?

    override fun close() {
        super.close()
        executor.close()
    }

    private inline fun executeInIgnoredSection(action: () -> Unit) {
        if (strategy is ManagedStrategy) {
            strategy.enterIgnoredSection()
            action()
            strategy.leaveIgnoredSection()
        }
        else {
            action()
        }
    }
}

internal enum class UseClocks { ALWAYS, RANDOM }

internal enum class CompletionStatus { CANCELLED, RESUMED }

private const val SPINNING_LOOP_ITERATIONS_BEFORE_YIELD = 100_000