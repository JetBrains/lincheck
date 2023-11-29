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

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.CancellationResult.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.FixedActiveThreadsExecutor.TestThread
import org.jetbrains.kotlinx.lincheck.runner.UseClocks.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy
import org.jetbrains.kotlinx.lincheck.strategy.managed.SwitchReason
import org.objectweb.asm.*
import java.lang.reflect.*
import java.util.Objects
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

    val runnerHashAtomic = AtomicInteger(-1)

    // helps to distinguish this runner threads from others
    val runnerHash: Int
        get() = runnerHashAtomic.get()

    // TODO: this is very bad idea to have non-stable (changeable) hash code, REFACTOR IT!!!
    override fun hashCode(): Int {
        return runnerHash
    }

    // should be closed in `close()`
    private lateinit var executor : FixedActiveThreadsExecutor

    // TODO: a hacky way to quickly fix problem with exceptions and deadlocks in init/post part
    private var sequentialPartThread: TestThread? = null
    private lateinit var sequentialPartExecutor: ExecutorService

    private fun setupExecutor() {
        runnerHashAtomic.set(Objects.hash(this, Random.nextInt()))
        sequentialPartThread = null
        val factory = ThreadFactory { runnable ->
            check(sequentialPartThread == null)
            sequentialPartThread = TestThread(scenario.threads, runnerHash, runnable)
            sequentialPartThread
        }
        sequentialPartExecutor = Executors.newSingleThreadExecutor(factory)
        executor = FixedActiveThreadsExecutor(scenario.threads, runnerHash)
    }

    init {
        setupExecutor()
    }

    private fun resetExecutor() {
        sequentialPartExecutor.shutdown()
        // sequentialPartThread?.stop()
        executor.close()
        setupExecutor()
    }

    private lateinit var testInstance: Any
    private lateinit var testThreadExecutions: Array<TestThreadExecution>

    private var suspensionPointResults = List(scenario.threads) { t ->
        MutableList<Result>(scenario.parallelExecution[t].size) { NoResult }
    }

    private val completions = List(scenario.threads) { t ->
        List(scenario.parallelExecution[t].size) { actorId -> Completion(t, actorId) }
    }

    // These completion statuses are updated atomically on resumptions and cancellations.
    // Due to prompt cancellation, resumption and cancellation can happen concurrently,
    // so that we need to synchronize them somehow. In order to update `completedOrSuspendedThreads`
    // consistently, we atomically change the status from `null` to `RESUMED` or `CANCELLED` and
    // update the counter on failure -- thus, synchronizing the threads.
    private lateinit var completionStatuses: List<AtomicReferenceArray<CompletionStatus>>
    private fun trySetResumedStatus(iThread: Int, actorId: Int) = completionStatuses[iThread].compareAndSet(actorId, null, CompletionStatus.RESUMED)
    private fun trySetCancelledStatus(iThread: Int, actorId: Int) = completionStatuses[iThread].compareAndSet(actorId, null, CompletionStatus.CANCELLED)

    private val uninitializedThreads = AtomicInteger(scenario.threads) // for threads synchronization
    private var spinningTimeBeforeYield = 1000 // # of loop cycles
    private var yieldInvokedInOnStart = false

    override fun initialize() {
        super.initialize()
        testThreadExecutions = Array(scenario.threads) { t ->
            TestThreadExecutionGenerator.create(this, t, scenario.parallelExecution[t], completions[t], scenario.hasSuspendableActors())
        }
        testThreadExecutions.forEach { it.allThreadExecutions = testThreadExecutions }
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
                onResumeCoroutine(iThread, actorId)
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
                        onResumeCoroutine(iThread, actorId)
                    }
                }
            }
        }
    }

    private fun reset() {
        sequentialPartExecutor.submit {
            testInstance = testClass.newInstance()
        }.get()
        testThreadExecutions.forEachIndexed { t, ex ->
            ex.testInstance = testInstance
            val threads = scenario.threads
            val actors = scenario.parallelExecution[t].size
            ex.useClocks = if (useClocks == ALWAYS) true else Random.nextBoolean()
            ex.curClock = 0
            ex.clocks = Array(actors) { emptyClockArray(threads) }
            ex.results = arrayOfNulls(actors)
        }
        suspensionPointResults.forEach { it.fill(NoResult) }
        completedOrSuspendedThreads.set(0)
        completions.forEach { it.forEach { it.resWithCont.set(null) } }
        completionStatuses = List(scenario.threads) { t ->
            AtomicReferenceArray<CompletionStatus>(scenario.parallelExecution[t].size)
        }
        uninitializedThreads.set(scenario.threads)
        // update `spinningTimeBeforeYield` adaptively
        if (yieldInvokedInOnStart) {
            spinningTimeBeforeYield = (spinningTimeBeforeYield + 1) / 2
            yieldInvokedInOnStart = false
        } else {
            spinningTimeBeforeYield = (spinningTimeBeforeYield * 2).coerceAtMost(MAX_SPINNING_TIME_BEFORE_YIELD)
        }
        // reset stored continuations
        executor.threads.forEach { it.cont = null }
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
            if (actor.cancelOnSuspension && cont !== null
                && cancelByLincheck(cont, actor.promptCancellation) != CANCELLATION_FAILED) {
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
        // If the suspended method call has a follow-up part after this suspension point,
        // then wait for the resuming thread to write a result of this suspension point
        // as well as the continuation to be executed by this thread;
        // wait for the final result of the method call otherwise.
        val completion = completions[iThread][actorId]
        var i = 1
        var blocked = false
        while (!isCoroutineResumed(iThread, actorId)) {
            // Check whether the scenario is completed and the current suspended operation cannot be resumed.
            if (completedOrSuspendedThreads.get() == scenario.threads || blocked) {
                suspensionPointResults[iThread][actorId] = NoResult
                return Suspended
            }
            if (strategy is ManagedStrategy) {
                strategy.switchCurrentThread(iThread, SwitchReason.STRATEGY_SWITCH, mustSwitch = true)
                blocked = strategy.isBlocked()
                continue
            }
            if (i++ % spinningTimeBeforeYield == 0)
                Thread.yield()
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

    override fun afterCoroutineCancel(iThread: Int, promptCancellation: Boolean, result: CancellationResult) {}

    override fun onResumeCoroutine(iResumedThread: Int, iResumedActor: Int) {}

    // We cannot use `completionStatuses` here since
    // they are set _before_ the result is published.
    override fun isCoroutineResumed(iThread: Int, actorId: Int) =
        suspensionPointResults[iThread][actorId] != NoResult || completions[iThread][actorId].resWithCont.get() != null

    override fun run(): InvocationResult {
        reset()
        val processFailure: (InvocationResult) -> Unit = { failure ->
            if (failure is DeadlockInvocationResult) {
                resetExecutor()
            }
        }
        val (initResults, afterInitStateRepresentation) = runInitPart { failure ->
            processFailure(failure)
            return failure
        }
        beforeParallelPart()
        val (parallelResultsWithClock, afterParallelStateRepresentation) = runParallelPart { failure ->
            processFailure(failure)
            return failure
        }
        afterParallelPart()
        val (postResults, afterPostStateRepresentation) = runPostPart { failure ->
            processFailure(failure)
            return failure
        }
        val results = ExecutionResult(
            initResults, afterInitStateRepresentation,
            parallelResultsWithClock, afterParallelStateRepresentation,
            postResults, afterPostStateRepresentation
        )
        return CompletedInvocationResult(results)
    }

    private inline fun runInitPart(onFailure: (InvocationResult) -> Unit): Pair<List<Result>, String?> {
        var results: List<Result>? = null
        var stateRepresentation: String? = null
        var failure: InvocationResult? = null
        try {
            sequentialPartExecutor.submit {
                results = scenario.initExecution.mapIndexed { i, initActor ->
                    val result = if (failure != null) {
                        NoResult
                    } else {
                        executeActor(testInstance, initActor)
                    }
                    executeValidationFunctions(testInstance, validationFunctions) { functionName, exception ->
                        val s = ExecutionScenario(
                            scenario.initExecution.subList(0, i + 1),
                            emptyList(),
                            emptyList()
                        )
                        failure = ValidationFailureInvocationResult(s, functionName, exception)
                    }
                    if (failure == null) result else NoResult
                }
                stateRepresentation = constructStateRepresentation()
            }.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            val threadDump = collectThreadDump(this)
            failure = DeadlockInvocationResult(threadDump)
        } catch (e: ExecutionException) {
            failure = UnexpectedExceptionInvocationResult(e.cause!!)
        }
        if (failure != null)
            onFailure(failure!!)
        return (results!! to stateRepresentation)
    }

    private inline fun runParallelPart(onFailure: (InvocationResult) -> Unit): Pair<List<List<ResultWithClock>>, String?> {
        try {
            executor.submitAndAwait(testThreadExecutions, timeoutMs)
        } catch (e: TimeoutException) {
            val threadDump = collectThreadDump(this)
            val failure = DeadlockInvocationResult(threadDump)
            onFailure(failure)
        } catch (e: ExecutionException) {
            val failure = UnexpectedExceptionInvocationResult(e.cause!!)
            onFailure(failure)
        }
        val parallelResultsWithClock = testThreadExecutions.map { ex ->
            ex.results.zip(ex.clocks).map { ResultWithClock(it.first, HBClock(it.second)) }
        }
        executeValidationFunctions(testInstance, validationFunctions) { functionName, exception ->
            val s = ExecutionScenario(
                scenario.initExecution,
                scenario.parallelExecution,
                emptyList()
            )
            val failure = ValidationFailureInvocationResult(s, functionName, exception)
            onFailure(failure)
        }
        var stateRepresentation: String? = null
        sequentialPartExecutor.submit {
            stateRepresentation = constructStateRepresentation()
        }.get(timeoutMs, TimeUnit.MILLISECONDS)
        return (parallelResultsWithClock to stateRepresentation)
    }

    private inline fun runPostPart(onFailure: (InvocationResult) -> Unit): Pair<List<Result>, String?> {
        var results: List<Result>? = null
        var stateRepresentation: String? = null
        var failure: InvocationResult? = null
        val dummyCompletion = Continuation<Any?>(EmptyCoroutineContext) {}
        var postPartSuspended = false
        try {
            sequentialPartExecutor.submit {
                results = scenario.postExecution.mapIndexed { i, postActor ->
                    // no actors are executed after suspension of a post part
                    // or after failure is found
                    val result = if (postPartSuspended || failure != null) {
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
                        failure = ValidationFailureInvocationResult(s, functionName, exception)
                    }
                    if (failure == null) result else NoResult
                }
                stateRepresentation = constructStateRepresentation()
            }.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            val threadDump = collectThreadDump(this)
            failure = DeadlockInvocationResult(threadDump)
        } catch (e: ExecutionException) {
            failure = UnexpectedExceptionInvocationResult(e.cause!!)
        }
        if (failure != null)
            onFailure(failure!!)
        return (results!! to stateRepresentation)
    }

    override fun onStart(iThread: Int) {
        super.onStart(iThread)
        uninitializedThreads.decrementAndGet() // this thread has finished initialization
        // wait for other threads to start
        var i = 1
        while (uninitializedThreads.get() != 0) {
            if (i % spinningTimeBeforeYield == 0) {
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
}

internal enum class UseClocks { ALWAYS, RANDOM }

internal enum class CompletionStatus { CANCELLED, RESUMED }

private const val MAX_SPINNING_TIME_BEFORE_YIELD = 2_000_000