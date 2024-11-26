/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.beforeEvent as ideaPluginBeforeEvent
import org.jetbrains.kotlinx.lincheck.CancellationResult.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.runner.ExecutionPart.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.jetbrains.kotlinx.lincheck.util.*
import sun.nio.ch.lincheck.*
import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicFieldUpdaterNames.getAtomicFieldUpdaterName
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicReferenceMethodType.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.FieldSearchHelper.findFinalFieldWithOwner
import org.jetbrains.kotlinx.lincheck.strategy.managed.ObjectLabelFactory.adornedStringRepresentation
import org.jetbrains.kotlinx.lincheck.strategy.managed.ObjectLabelFactory.cleanObjectNumeration
import org.jetbrains.kotlinx.lincheck.strategy.managed.UnsafeName.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.VarHandleMethodType.*
import java.lang.reflect.*
import java.util.concurrent.TimeoutException
import java.util.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * This is an abstraction for all managed strategies, which encapsulated
 * the required byte-code transformation and [running][Runner] logic and provides
 * a high-level level interface to implement the strategy logic.
 *
 * It is worth noting that here we also solve all the transformation
 * and class loading problems.
 */
abstract class ManagedStrategy(
    private val testClass: Class<*>,
    scenario: ExecutionScenario,
    private val validationFunction: Actor?,
    private val stateRepresentationFunction: Method?,
    private val testCfg: ManagedCTestConfiguration
) : Strategy(scenario), EventTracker {
    // The number of parallel threads.
    protected val nThreads: Int = scenario.nThreads

    // Runner for scenario invocations,
    // can be replaced with a new one for trace construction.
    override var runner = createRunner()
    
    // == EXECUTION CONTROL FIELDS ==

    protected val threadScheduler = ManagedThreadScheduler()

    // Which threads are suspended?
    private val isSuspended = mutableThreadMapOf<Boolean>()

    // Current actor id for each thread.
    protected val currentActorId = mutableThreadMapOf<Int>()

    // Detector of loops or hangs (i.e. active locks).
    internal val loopDetector: LoopDetector = LoopDetector(testCfg.hangingDetectionThreshold)

    // Tracker of objects' allocations and object graph topology.
    protected abstract val objectTracker: ObjectTracker
    // Tracker of the monitors' operations.
    protected abstract val monitorTracker: MonitorTracker
    // Tracker of the thread parking.
    protected abstract val parkingTracker: ParkingTracker

    // InvocationResult that was observed by the strategy during the execution (e.g., a deadlock).
    @Volatile
    protected var suddenInvocationResult: InvocationResult? = null

    // == TRACE CONSTRUCTION FIELDS ==

    // Whether an additional information requires for the trace construction should be collected.
    protected var collectTrace = false

    // Collector of all events in the execution such as thread switches.
    private var traceCollector: TraceCollector? = null // null when `collectTrace` is false

    // Stores the global number of stack trace elements.
    private var callStackTraceElementId = 0

    // Stores the currently executing methods call stack for each thread.
    private val callStackTrace = mutableThreadMapOf<MutableList<CallStackTraceElement>>()

    // In case of suspension, the call stack of the corresponding `suspend`
    // methods is stored here, so that the same method call identifiers are
    // used on resumption, and the trace point before and after the suspension
    // correspond to the same method call in the trace.
    // NOTE: the call stack is stored in the reverse order,
    // i.e., the first element is the top stack trace element.
    private val suspendedFunctionsStack = mutableThreadMapOf<MutableList<CallStackTraceElement>>()

    // Last read trace point, occurred in the current thread.
    // We store it as we initialize read value after the point is created so we have to store
    // the trace point somewhere to obtain it later.
    private var lastReadTracePoint = mutableThreadMapOf<ReadTracePoint?>()

    // Random instances with fixed seeds to replace random calls in instrumented code.
    private var randoms = mutableThreadMapOf<Random>()

    // User-specified guarantees on specific function, which can be considered as atomic or ignored.
    private val userDefinedGuarantees: List<ManagedStrategyGuarantee>? = testCfg.guarantees.ifEmpty { null }

    // Utility class for the plugin integration to provide ids for each trace point
    private var eventIdProvider = EventIdProvider()

    /**
     * Current method call context (static or instance).
     * Initialized and used only in the trace collecting stage.
     */
    private var callStackContextPerThread = mutableThreadMapOf<ArrayList<CallContext>>()

    /**
     * In case when the plugin is enabled, we also enable [eventIdStrictOrderingCheck] property and check
     * that event ids provided to the [beforeEvent] method
     * and corresponding trace points are sequentially ordered.
     * But we do not add a [MethodCallTracePoint] for the coroutine resumption.
     * So this field just tracks if the last [beforeMethodCall] invocation was actually a coroutine resumption.
     * In this case, we just skip the next [beforeEvent] call.
     *
     * So this is a hack to make the plugin integration working without refactoring too much code.
     *
     * In more detail: when the resumption is called, a lot of stuff is happening under the hood.
     * In particular, a suspend fun is called with the given completion object.
     * [MethodCallTransformer] instruments this call as it instruments other method calls,
     * however, in case of resumption this suspend fun call should not create new trace point.
     * [MethodCallTransformer] does not know about it, it always injects beforeEvent call regardless:
     *
     * ```
     * invokeStatic(Injections::beforeMethodCall)
     * invokeBeforeEventIfPluginEnabled("method call $methodName", setMethodEventId = true)
     * ```
     *
     * Therefore, to "skip" this beforeEvent call following resumption call to suspend fun,
     * we use this `skipNextBeforeEvent` flag.
     *
     * A better approach would be to refactor a code, and instead just assign eventId-s directly to trace points.
     * Methods like `beforeMethodCall` then can return `eventId` of the created trace point,
     * or something like `-1` in case when no trace point is created.
     * Then subsequent beforeEvent call can just take this `eventId` from the stack.
     *
     * TODO: refactor this --- we should have a more reliable way
     *   to communicate coroutine resumption event to the plugin.
     */
    private var skipNextBeforeEvent = false

    override fun close() {
        super.close()
        // clear object numeration at the end to avoid memory leaks
        cleanObjectNumeration()
    }

    private fun createRunner(): ManagedStrategyRunner =
        ManagedStrategyRunner(
            managedStrategy = this,
            testClass = testClass,
            validationFunction = validationFunction,
            stateRepresentationMethod = stateRepresentationFunction,
            timeoutMs = getTimeOutMs(this, testCfg.timeoutMs),
            useClocks = UseClocks.ALWAYS
        )

    // == STRATEGY INTERFACE METHODS ==

    /**
     * This method is invoked before every thread context switch.
     * @param iThread current thread that is about to be switched
     * @param mustSwitch whether the switch is not caused by strategy and is a must-do (e.g, because of monitor wait)
     */
    protected open fun onNewSwitch(iThread: Int, mustSwitch: Boolean) {}

    /**
     * Returns whether thread should switch at the switch point.
     * @param iThread the current thread
     */
    protected abstract fun shouldSwitch(iThread: Int): Boolean

    /**
     * Choose a thread to switch from thread [iThread].
     * @return id the chosen thread
     */
    protected abstract fun chooseThread(iThread: Int): Int

    /**
     * Resets all internal data to the initial state and initializes current invocation to be run.
     */
    protected open fun initializeInvocation() {
        traceCollector = if (collectTrace) TraceCollector() else null
        suddenInvocationResult = null
        loopDetector.initialize()
        objectTracker.reset()
        monitorTracker.reset()
        parkingTracker.reset()
        resetThreads()
    }

    /**
     * Runs the current invocation.
     */
    override fun runInvocation(): InvocationResult {
        while (true) {
            initializeInvocation()
            val result = runner.run()
            // In case the runner detects a deadlock, some threads can still manipulate the current strategy,
            // so we're not interested in suddenInvocationResult in this case
            // and immediately return RunnerTimeoutInvocationResult.
            if (result is RunnerTimeoutInvocationResult) {
                return result
            }
            // If strategy has not detected a sudden invocation result,
            // then return, otherwise process the sudden result.
            val suddenResult = suddenInvocationResult ?: return result
            // Unexpected `ThreadAbortedError` should be thrown.
            check(result is UnexpectedExceptionInvocationResult)
            // Check if an invocation replay is required
            val isReplayRequired = (suddenResult is SpinCycleFoundAndReplayRequired)
            if (isReplayRequired) {
                enableSpinCycleReplay()
                continue
            }
            // Otherwise return the sudden result
            return suddenResult
        }
    }

    protected open fun enableSpinCycleReplay() {}

    // == BASIC STRATEGY METHODS ==

    override fun beforePart(part: ExecutionPart) = runInIgnoredSection {
        traceCollector?.passCodeLocation(SectionDelimiterTracePoint(part))
        val nextThread = when (part) {
            INIT        -> 0
            PARALLEL    -> chooseThread(0)
            POST        -> 0
            VALIDATION  -> 0
        }
        loopDetector.beforePart(nextThread)
        threadScheduler.scheduleThread(nextThread)
    }

    /**
     * Re-runs the last invocation to collect its trace.
     */
    override fun tryCollectTrace(result: InvocationResult): Trace? {
        val detectedByStrategy = suddenInvocationResult != null
        val canCollectTrace = when {
            detectedByStrategy -> true // ObstructionFreedomViolationInvocationResult or UnexpectedExceptionInvocationResult
            result is CompletedInvocationResult -> true
            result is ValidationFailureInvocationResult -> true
            else -> false
        }
        if (!canCollectTrace) {
            // Interleaving events can be collected almost always,
            // except for the strange cases such as Runner's timeout or exceptions in LinCheck.
            return null
        }

        collectTrace = true
        loopDetector.enableReplayMode(
            failDueToDeadlockInTheEnd =
                result is ManagedDeadlockInvocationResult ||
                result is ObstructionFreedomViolationInvocationResult
        )
        cleanObjectNumeration()

        runner.close()
        runner = createRunner()

        val loggedResults = runInvocation()
        // In case the runner detects a deadlock, some threads can still be in an active state,
        // simultaneously adding events to the TraceCollector, which leads to an inconsistent trace.
        // Therefore, if the runner detects deadlock, we don't even try to collect trace.
        if (loggedResults is RunnerTimeoutInvocationResult) return null
        val sameResultTypes = loggedResults.javaClass == result.javaClass
        val sameResults = (
            loggedResults !is CompletedInvocationResult ||
            result !is CompletedInvocationResult ||
            loggedResults.results == result.results
        )
        check(sameResultTypes && sameResults) {
            StringBuilder().apply {
                appendLine("Non-determinism found. Probably caused by non-deterministic code (WeakHashMap, Object.hashCode, etc).")
                appendLine("== Reporting the first execution without execution trace ==")
                appendLine(result.toLincheckFailure(scenario, null))
                appendLine("== Reporting the second execution ==")
                appendLine(loggedResults.toLincheckFailure(scenario, Trace(traceCollector!!.trace)).toString())
            }.toString()
        }

        return Trace(traceCollector!!.trace)
    }

    private fun failDueToDeadlock(): Nothing {
        val result = ManagedDeadlockInvocationResult(runner.collectExecutionResults())
        abortWithSuddenInvocationResult(result)
    }

    private fun failDueToLivelock(lazyMessage: () -> String): Nothing {
        val result = ObstructionFreedomViolationInvocationResult(lazyMessage(), runner.collectExecutionResults())
        abortWithSuddenInvocationResult(result)
    }

    private fun failIfObstructionFreedomIsRequired(lazyMessage: () -> String) {
        if (testCfg.checkObstructionFreedom && !currentActorIsBlocking && !concurrentActorCausesBlocking) {
            failDueToLivelock(lazyMessage)
        }
    }

    private val currentActorIsBlocking: Boolean get() {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        val actorId = currentActorId[currentThreadId] ?: -1
        // Handle the case when the first actor has not yet started,
        // see https://github.com/JetBrains/lincheck/pull/277
        if (actorId < 0) return false
        return scenario.threads[currentThreadId][actorId].blocking
    }

    private val concurrentActorCausesBlocking: Boolean get() {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        val currentActiveActorIds = currentActorId.values.mapIndexed { iThread, actorId ->
            if (iThread != currentThreadId && actorId >= 0 && !threadScheduler.isFinished(iThread)) {
                scenario.threads[iThread][actorId]
            } else null
        }.filterNotNull()
        return currentActiveActorIds.any { it.causesBlocking }
    }


    // == EXECUTION CONTROL METHODS ==

    /**
     * Create a new switch point, where a thread context switch can occur.
     * @param iThread the current thread
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of the point in code.
     */
    private fun newSwitchPoint(iThread: Int, codeLocation: Int, tracePoint: TracePoint?) {
        // re-throw abort error if the thread was aborted
        if (threadScheduler.isAborted(iThread)) {
            threadScheduler.abortCurrentThread()
        }
        // check we are in the right thread
        check(iThread == threadScheduler.scheduledThreadId)
        // check if we need to switch
        val shouldSwitch = when {
            /*
             * When replaying executions, it's important to repeat the same thread switches
             * recorded in the loop detector history during the last execution.
             * For example, suppose that interleaving say us to switch
             * from thread 1 to thread 2 at execution position 200.
             * But after execution 10, a spin cycle with period 2 occurred,
             * so we will switch from the spin cycle.
             * When we leave this cycle due to the switch for the first time,
             * interleaving execution counter may be near 200 and the strategy switch will happen soon.
             * But on the replay run, we will switch from thread 1 early, after 12 operations,
             * but no strategy switch will be performed for the next 200-12 operations.
             * This leads to the results of another execution, compared to the original failure results.
             * To avoid this bug when we're replaying some executions,
             * we have to follow only loop detector's history during the last execution.
             * In the considered example, we will retain that we will switch soon after
             * the spin cycle in thread 1, so no bug will appear.
             */
            loopDetector.replayModeEnabled ->
                loopDetector.shouldSwitchInReplayMode()
            /*
             * In the regular mode, we use loop detector only to determine should we
             * switch current thread or not due to new or early detection of spin locks.
             * Regular thread switches are dictated by the current interleaving.
             */
            else ->
                (runner.currentExecutionPart == PARALLEL) && shouldSwitch(iThread)
        }
        // check if live-lock is detected
        val decision = loopDetector.visitCodeLocation(iThread, codeLocation)
        // if we reached maximum number of events threshold, then fail immediately
        if (decision == LoopDetector.Decision.EventsThresholdReached) {
            failDueToDeadlock()
        }
        // if any kind of live-lock was detected, check for obstruction-freedom violation
        if (decision.isLivelockDetected) {
            failIfObstructionFreedomIsRequired {
                if (decision is LoopDetector.Decision.LivelockFailureDetected) {
                    // if failure is detected, add a special obstruction-freedom violation
                    // trace point to account for that
                    traceCollector?.passObstructionFreedomViolationTracePoint(iThread, beforeMethodCall = tracePoint is MethodCallTracePoint)
                } else {
                    // otherwise log the last event that caused obstruction-freedom violation
                    traceCollector?.passCodeLocation(tracePoint)
                }
                OBSTRUCTION_FREEDOM_SPINLOCK_VIOLATION_MESSAGE
            }
        }
        // if live-lock failure was detected, then fail immediately
        if (decision is LoopDetector.Decision.LivelockFailureDetected) {
            traceCollector?.newSwitch(iThread, SwitchReason.ACTIVE_LOCK, beforeMethodCallSwitch = tracePoint is MethodCallTracePoint)
            failDueToDeadlock()
        }
        // if live-lock was detected, and replay was requested,
        // then abort current execution and start the replay
        if (decision.isReplayRequired) {
            abortWithSuddenInvocationResult(SpinCycleFoundAndReplayRequired)
        }
        // if the current thread in a live-lock, then try to switch to another thread
        if (decision is LoopDetector.Decision.LivelockThreadSwitch) {
            val switchHappened = switchCurrentThread(iThread, SwitchReason.ACTIVE_LOCK, tracePoint = tracePoint)
            if (switchHappened) {
                loopDetector.initializeFirstCodeLocationAfterSwitch(codeLocation)
            }
            traceCollector?.passCodeLocation(tracePoint)
            return
        }
        // if strategy requested thread switch, then do it
        if (shouldSwitch) {
            val switchHappened = switchCurrentThread(iThread, SwitchReason.STRATEGY_SWITCH, tracePoint = tracePoint)
            if (switchHappened) {
                loopDetector.initializeFirstCodeLocationAfterSwitch(codeLocation)
            }
            traceCollector?.passCodeLocation(tracePoint)
            return
        }
        if (!loopDetector.replayModeEnabled) {
            loopDetector.onNextExecutionPoint(codeLocation)
        }
        traceCollector?.passCodeLocation(tracePoint)
    }

    override fun beforeThreadFork(thread: Thread, descriptor: ThreadDescriptor) = runInIgnoredSection {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        // do not track threads forked from unregistered threads
        if (currentThreadId < 0) return
        // scenario threads are handled separately by the runner itself
        if (thread is TestThread) return
        @Suppress("UNUSED_VARIABLE")
        val forkedThreadId = registerThread(thread, descriptor)
    }

    override fun afterThreadFork(thread: Thread?) = runInIgnoredSection {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        // do not track threads forked from unregistered threads
        if (currentThreadId < 0) return
        // scenario threads are handled separately by the runner itself
        if (thread is TestThread) return
        newSwitchPoint(currentThreadId, UNKNOWN_CODE_LOCATION, tracePoint = null)
    }

    override fun beforeThreadStart() = runInIgnoredSection {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        // do not track unregistered threads
        if (currentThreadId < 0) return
        // scenario threads are handled separately
        if (currentThreadId < scenario.nThreads) return
        onThreadStart(currentThreadId)
        enterTestingCode()
    }

    override fun afterThreadFinish() = runInIgnoredSection {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        // do not track unregistered threads
        if (currentThreadId < 0) return
        // scenario threads are handled separately by the runner itself
        if (currentThreadId < scenario.nThreads) return
        leaveTestingCode()
        onThreadFinish(currentThreadId)
    }

    override fun beforeThreadJoin(thread: Thread?) = runInIgnoredSection {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        val joinThreadId = threadScheduler.getThreadId(thread!!)
        // TODO: add trace point ?
        while (threadScheduler.getThreadState(joinThreadId) != ThreadState.FINISHED) {
            // TODO: should wait on thread-join be considered an obstruction-freedom violation?
            // Switch to another thread and wait for a moment when the thread is finished
            switchCurrentThread(currentThreadId, SwitchReason.THREAD_JOIN_WAIT)
        }
    }

    fun registerThread(thread: Thread, descriptor: ThreadDescriptor): ThreadId {
        val threadId = threadScheduler.registerThread(thread, descriptor)
        isSuspended[threadId] = false
        currentActorId[threadId] = -1
        callStackTrace[threadId] = mutableListOf()
        suspendedFunctionsStack[threadId] = mutableListOf()
        callStackContextPerThread[threadId] = arrayListOf(
            CallContext.InstanceCallContext(runner.testInstance)
        )
        lastReadTracePoint[threadId] = null
        randoms[threadId] = Random(threadId + 239L)
        objectTracker.registerThread(threadId, thread)
        monitorTracker.registerThread(threadId)
        parkingTracker.registerThread(threadId)
        return threadId
    }

    private fun resetThreads() {
        threadScheduler.reset()
        isSuspended.clear()
        currentActorId.clear()
        callStackTrace.clear()
        suspendedFunctionsStack.clear()
        callStackContextPerThread.clear()
        randoms.clear()
    }

    override fun awaitAllThreads(timeoutNano: Long): Long {
        val elapsedTime = threadScheduler.awaitAllThreadsFinish(timeoutNano)
        if (elapsedTime < 0L) throw TimeoutException()
        return elapsedTime
    }

    /**
     * This method is executed as the first thread action.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     */
    open fun onThreadStart(iThread: Int) {
        threadScheduler.awaitTurn(iThread)
        threadScheduler.startThread(iThread)
    }

    /**
     * This method is executed as the last thread action if no exception has been thrown.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     */
    open fun onThreadFinish(iThread: Int) {
        threadScheduler.awaitTurn(iThread)
        threadScheduler.finishThread(iThread)
        loopDetector.onThreadFinish(iThread)
        traceCollector?.onThreadFinish()
        unblockJoiningThreads(iThread)
        val nextThread = chooseThreadSwitch(iThread, true)
        setCurrentThread(nextThread)
    }

    /**
     * This method is executed if an illegal exception has been thrown (see [exceptionCanBeValidExecutionResult]).
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param exception the exception that was thrown
     */
    open fun onFailure(iThread: Int, exception: Throwable) {
        // This method is called only if exception can't be treated as a normal operation result,
        // so we exit testing code to avoid trace collection resume or some bizarre bugs
        leaveTestingCode()
        // skip abort exception
        if (exception !== ThreadAbortedError) {
            // Despite the fact that the corresponding failure will be detected by the runner,
            // the managed strategy can construct a trace to reproduce this failure.
            // Let's then store the corresponding failing result and construct the trace.
            suddenInvocationResult = UnexpectedExceptionInvocationResult(exception, runner.collectExecutionResults())
            threadScheduler.abortAllThreads()
        }
        // notify the scheduler that the thread is going to be finished
        threadScheduler.finishThread(iThread)
    }

    override fun onActorStart(iThread: Int) {
        val actorId = 1 + currentActorId[iThread]!!
        currentActorId[iThread] = actorId
        callStackTrace[iThread]!!.clear()
        suspendedFunctionsStack[iThread]!!.clear()
        loopDetector.onActorStart(iThread)
        enterTestingCode()
    }

    override fun onActorFinish() {
        // This is a hack to guarantee correct stepping in the plugin.
        // When stepping out to the TestThreadExecution class, stepping continues unproductively.
        // With this method, we force the debugger to stop at the beginning of the next actor.
        onThreadSwitchesOrActorFinishes()
        leaveTestingCode()
    }

    /**
     * Returns whether the specified thread is active and
     * can continue its execution (i.e. is not blocked/finished).
     */
    private fun isActive(iThread: Int): Boolean =
        threadScheduler.isSchedulable(iThread) &&
        // TODO: coroutine suspensions are currently handled separately from `ThreadScheduler`
        !(isSuspended[iThread]!! && !runner.isCoroutineResumed(iThread, currentActorId[iThread]!!))

    /**
     * A regular context thread switch to another thread.
     *
     * @return was this thread actually switched to another or not.
     */
    private fun switchCurrentThread(
        iThread: Int,
        switchReason: SwitchReason = SwitchReason.STRATEGY_SWITCH,
        tracePoint: TracePoint? = null
    ): Boolean {
        val blockingReason = when (switchReason) {
            SwitchReason.LOCK_WAIT          -> BlockingReason.LOCKED
            SwitchReason.MONITOR_WAIT       -> BlockingReason.WAITING
            SwitchReason.PARK_WAIT          -> BlockingReason.PARKED
            SwitchReason.THREAD_JOIN_WAIT   -> BlockingReason.THREAD_JOIN
            // TODO: coroutine suspensions are currently handled separately from `ThreadScheduler`
            // SwitchReason.SUSPENDED   -> BlockingReason.SUSPENDED
            else                        -> null
        }
        val mustSwitch = (blockingReason != null) || (switchReason == SwitchReason.SUSPENDED)
        val nextThread = chooseThreadSwitch(iThread, mustSwitch)
        val switchHappened = (iThread != nextThread)
        if (switchHappened) {
            traceCollector?.newSwitch(iThread, switchReason,
                beforeMethodCallSwitch = (tracePoint != null && tracePoint is MethodCallTracePoint)
            )
            if (blockingReason != null) {
                blockThread(iThread, blockingReason)
            }
            setCurrentThread(nextThread)
        }
        threadScheduler.awaitTurn(iThread)
        return switchHappened
    }

    private fun chooseThreadSwitch(iThread: Int, mustSwitch: Boolean = false): Int {
        onNewSwitch(iThread, mustSwitch)
        val threads = switchableThreads(iThread)
        // do the switch if there is an available thread
        if (threads.isNotEmpty()) {
            val nextThread = chooseThread(iThread).also {
                check(it in threads) {
                    """
                        Trying to switch the execution to thread $it,
                        but only the following threads are eligible to switch: $threads
                    """.trimIndent()
                }
            }
            return nextThread
        }
        // otherwise exit if the thread switch is optional, or all threads are finished
        if (!mustSwitch || threadScheduler.areAllThreadsFinished()) {
           return iThread
        }
        // try to resume some suspended thread
        val suspendedThread = (0 until nThreads).firstOrNull {
           !threadScheduler.isFinished(it) && isSuspended[it]!!
        }
        if (suspendedThread != null) {
           return suspendedThread
        }
        // any other situation is considered to be a deadlock
        val result = ManagedDeadlockInvocationResult(runner.collectExecutionResults())
        abortWithSuddenInvocationResult(result)
    }

    @JvmName("setNextThread")
    private fun setCurrentThread(nextThread: Int) {
        loopDetector.onThreadSwitch(nextThread)
        threadScheduler.scheduleThread(nextThread)
    }

    private fun abortWithSuddenInvocationResult(invocationResult: InvocationResult): Nothing {
        suddenInvocationResult = invocationResult
        threadScheduler.abortOtherThreads()
        threadScheduler.abortCurrentThread()
    }

    /**
     * Threads to which an execution can be switched from thread [iThread].
     */
    protected fun switchableThreads(iThread: Int) =
        if (runner.currentExecutionPart == PARALLEL) {
            (0 until threadScheduler.nThreads).filter { it != iThread && isActive(it) }
        } else {
            emptyList()
        }

    // == LISTENING METHODS ==

    override fun beforeLock(codeLocation: Int): Unit = runInIgnoredSection {
        val iThread = threadScheduler.getCurrentThreadId()
        val tracePoint = if (collectTrace) {
            MonitorEnterTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread]!!,
                callStackTrace = callStackTrace[iThread]!!,
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            )
        } else {
            null
        }
        newSwitchPoint(iThread, codeLocation, tracePoint)
    }

    /*
   TODO: Here Lincheck performs in-optimal switching.
   Firstly an optional switch point is added before lock, and then adds force switches in case execution cannot continue in this thread.
   More effective way would be to do force switch in case the thread is blocked (smart order of thread switching is needed),
   or create a switch point if the switch is really optional.

   Because of this additional switching we had to split this method into two, as the beforeEvent method must be called after the switch point.
    */
    override fun lock(monitor: Any): Unit = runInIgnoredSection {
        val iThread = threadScheduler.getCurrentThreadId()
        // Try to acquire the monitor
        while (!monitorTracker.acquireMonitor(iThread, monitor)) {
            // Switch to another thread and wait for a moment when the monitor can be acquired
            switchCurrentThread(iThread, SwitchReason.LOCK_WAIT)
        }
    }

    override fun unlock(monitor: Any, codeLocation: Int): Unit = runInIgnoredSection {
        val iThread = threadScheduler.getCurrentThreadId()
        // We need to be extremely careful with the MONITOREXIT instruction,
        // as it can be put into a recursive "finally" block, releasing
        // the lock over and over until the instruction succeeds.
        // Therefore, we always release the lock in this case,
        // without tracking the event.
        if (threadScheduler.isAborted(iThread)) return
        check(iThread == threadScheduler.getCurrentThreadId())
        val isReleased = monitorTracker.releaseMonitor(iThread, monitor)
        if (isReleased) {
            unblockAcquiringThreads(iThread, monitor)
        }
        if (collectTrace) {
            val tracePoint = MonitorExitTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread]!!,
                callStackTrace = callStackTrace[iThread]!!,
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            )
            traceCollector!!.passCodeLocation(tracePoint)
        }
    }

    override fun park(codeLocation: Int): Unit = runInIgnoredSection {
        val iThread = threadScheduler.getCurrentThreadId()
        val tracePoint = if (collectTrace) {
            ParkTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread]!!,
                callStackTrace = callStackTrace[iThread]!!,
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            )
        } else {
            null
        }
        // Instead of fairly supporting the park/unpark semantics,
        // we simply add a new switch point here, thus, also
        // emulating spurious wake-ups.
        newSwitchPoint(iThread, codeLocation, tracePoint)
        parkingTracker.park(iThread)
        while (parkingTracker.waitUnpark(iThread)) {
            // switch to another thread and wait till an unpark event happens
            switchCurrentThread(iThread, SwitchReason.PARK_WAIT)
        }
    }

    override fun unpark(thread: Thread, codeLocation: Int): Unit = runInIgnoredSection {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        val unparkedThreadId = (thread as TestThread).threadId
        parkingTracker.unpark(currentThreadId, unparkedThreadId)
        unblockParkedThread(unparkedThreadId)
        if (collectTrace) {
            val tracePoint = UnparkTracePoint(
                iThread = currentThreadId,
                actorId = currentActorId[currentThreadId]!!,
                callStackTrace = callStackTrace[currentThreadId]!!,
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            )
            traceCollector?.passCodeLocation(tracePoint)
        }
    }

    override fun beforeWait(codeLocation: Int): Unit = runInIgnoredSection {
        val iThread = threadScheduler.getCurrentThreadId()
        val tracePoint = if (collectTrace) {
            WaitTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread]!!,
                callStackTrace = callStackTrace[iThread]!!,
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            )
        } else {
            null
        }
        newSwitchPoint(iThread, codeLocation, tracePoint)
    }

    /*
    TODO: Here Lincheck performs in-optimal switching.
    Firstly an optional switch point is added before wait, and then adds force switches in case execution cannot continue in this thread.
    More effective way would be to do force switch in case the thread is blocked (smart order of thread switching is needed),
    or create a switch point if the switch is really optional.

    Because of this additional switching we had to split this method into two, as the beforeEvent method must be called after the switch point.
     */
    override fun wait(monitor: Any, withTimeout: Boolean): Unit = runInIgnoredSection {
        if (withTimeout) return // timeouts occur instantly
        val iThread = threadScheduler.getCurrentThreadId()
        while (monitorTracker.waitOnMonitor(iThread, monitor)) {
            unblockAcquiringThreads(iThread, monitor)
            switchCurrentThread(iThread, SwitchReason.MONITOR_WAIT)
        }
    }

    override fun notify(monitor: Any, codeLocation: Int, notifyAll: Boolean): Unit = runInIgnoredSection {
        val iThread = threadScheduler.getCurrentThreadId()
        monitorTracker.notify(iThread, monitor, notifyAll = notifyAll)
        if (collectTrace) {
            val tracePoint = NotifyTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread]!!,
                callStackTrace = callStackTrace[iThread]!!,
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            )
            traceCollector?.passCodeLocation(tracePoint)
        }
    }

    private fun blockThread(threadId: ThreadId, blockingReason: BlockingReason) {
        failIfObstructionFreedomIsRequired {
            blockingReason.obstructionFreedomViolationMessage
        }
        threadScheduler.blockThread(threadId, blockingReason)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun unblockJoiningThreads(finishedThreadId: Int) {
        for (threadId in 0 until threadScheduler.nThreads) {
            // TODO: unlock only those threads waiting for finishedThreadId
            if (threadScheduler.getBlockingReason(threadId) == BlockingReason.THREAD_JOIN) {
                threadScheduler.unblockThread(threadId)
            }
        }
    }

    private fun unblockParkedThread(unparkedThreadId: Int) {
        if (threadScheduler.getBlockingReason(unparkedThreadId) == BlockingReason.PARKED) {
            threadScheduler.unblockThread(unparkedThreadId)
        }
    }

    private fun unblockAcquiringThreads(iThread: Int, monitor: Any) {
        monitorTracker.acquiringThreads(monitor).forEach { threadId ->
            if (iThread == threadId) return@forEach
            val blockingReason = threadScheduler.getBlockingReason(threadId)?.ensure {
                it == BlockingReason.LOCKED || it == BlockingReason.WAITING
            }
            // do not wake up thread waiting for notification
            if (blockingReason == BlockingReason.WAITING && monitorTracker.isWaiting(threadId))
                return@forEach
            threadScheduler.unblockThread(threadId)
        }
    }

    /**
     * Returns `true` if a switch point is created.
     */
    override fun beforeReadField(obj: Any?, className: String, fieldName: String, codeLocation: Int,
                                 isStatic: Boolean, isFinal: Boolean) = runInIgnoredSection {
        // We need to ensure all the classes related to the reading object are instrumented.
        // The following call checks all the static fields.
        if (isStatic) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(className.canonicalClassName)
        }
        // Optimization: do not track final field reads
        if (isFinal) {
            return@runInIgnoredSection false
        }
        // Do not track accesses to untracked objects
        if (!objectTracker.shouldTrackObjectAccess(obj ?: StaticObject)) {
            return@runInIgnoredSection false
        }
        val iThread = threadScheduler.getCurrentThreadId()
        val tracePoint = if (collectTrace) {
            ReadTracePoint(
                ownerRepresentation = if (isStatic) simpleClassName(className) else findOwnerName(obj!!),
                iThread = iThread,
                actorId = currentActorId[iThread]!!,
                callStackTrace = callStackTrace[iThread]!!,
                fieldName = fieldName,
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            )
        } else {
            null
        }
        if (tracePoint != null) {
            lastReadTracePoint[iThread] = tracePoint
        }
        newSwitchPoint(iThread, codeLocation, tracePoint)
        loopDetector.beforeReadField(obj)
        return@runInIgnoredSection true
    }

    /** Returns <code>true</code> if a switch point is created. */
    override fun beforeReadArrayElement(array: Any, index: Int, codeLocation: Int): Boolean = runInIgnoredSection {
        if (!objectTracker.shouldTrackObjectAccess(array)) {
            return@runInIgnoredSection false
        }
        val iThread = threadScheduler.getCurrentThreadId()
        val tracePoint = if (collectTrace) {
            ReadTracePoint(
                ownerRepresentation = null,
                iThread = iThread,
                actorId = currentActorId[iThread]!!,
                callStackTrace = callStackTrace[iThread]!!,
                fieldName = "${adornedStringRepresentation(array)}[$index]",
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            )
        } else {
            null
        }
        if (tracePoint != null) {
            lastReadTracePoint[iThread] = tracePoint
        }
        newSwitchPoint(iThread, codeLocation, tracePoint)
        loopDetector.beforeReadArrayElement(array, index)
        true
    }

    override fun afterRead(value: Any?) = runInIgnoredSection {
        if (collectTrace) {
                val iThread = threadScheduler.getCurrentThreadId()
                lastReadTracePoint[iThread]?.initializeReadValue(adornedStringRepresentation(value))
                lastReadTracePoint[iThread] = null
        }
        loopDetector.afterRead(value)
    }

    override fun beforeWriteField(obj: Any?, className: String, fieldName: String, value: Any?, codeLocation: Int,
                                  isStatic: Boolean, isFinal: Boolean): Boolean = runInIgnoredSection {
        objectTracker.registerObjectLink(fromObject = obj ?: StaticObject, toObject = value)
        if (!objectTracker.shouldTrackObjectAccess(obj ?: StaticObject)) {
            return@runInIgnoredSection false
        }
        // Optimization: do not track final field writes
        if (isFinal) {
            return@runInIgnoredSection false
        }
        val iThread = threadScheduler.getCurrentThreadId()
        val tracePoint = if (collectTrace) {
            WriteTracePoint(
                ownerRepresentation = if (isStatic) simpleClassName(className) else findOwnerName(obj!!),
                iThread = iThread,
                actorId = currentActorId[iThread]!!,
                callStackTrace = callStackTrace[iThread]!!,
                fieldName = fieldName,
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            ).also {
                it.initializeWrittenValue(adornedStringRepresentation(value))
            }
        } else {
            null
        }
        newSwitchPoint(iThread, codeLocation, tracePoint)
        loopDetector.beforeWriteField(obj, value)
        return@runInIgnoredSection true
    }

    override fun beforeWriteArrayElement(array: Any, index: Int, value: Any?, codeLocation: Int): Boolean = runInIgnoredSection {
        objectTracker.registerObjectLink(fromObject = array, toObject = value)
        if (!objectTracker.shouldTrackObjectAccess(array)) {
            return@runInIgnoredSection false
        }
        val iThread = threadScheduler.getCurrentThreadId()
        val tracePoint = if (collectTrace) {
            WriteTracePoint(
                ownerRepresentation = null,
                iThread = iThread,
                actorId = currentActorId[iThread]!!,
                callStackTrace = callStackTrace[iThread]!!,
                fieldName = "${adornedStringRepresentation(array)}[$index]",
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            ).also {
                it.initializeWrittenValue(adornedStringRepresentation(value))
            }
        } else {
            null
        }
        newSwitchPoint(iThread, codeLocation, tracePoint)
        loopDetector.beforeWriteArrayElement(array, index, value)
        true
    }

    override fun afterWrite() {
        if (collectTrace) {
            runInIgnoredSection {
                traceCollector?.addStateRepresentation()
            }
        }
    }

    override fun afterReflectiveSetter(receiver: Any?, value: Any?) = runInIgnoredSection {
        objectTracker.registerObjectLink(fromObject = receiver ?: StaticObject, toObject = value)
    }

    override fun getThreadLocalRandom(): Random = runInIgnoredSection {
        return randoms[threadScheduler.getCurrentThreadId()]!!
    }

    override fun randomNextInt(): Int = runInIgnoredSection {
        getThreadLocalRandom().nextInt()
    }

    protected fun enterTestingCode() {
        return Injections.enterTestingCode();
    }

    protected fun leaveTestingCode() {
        return Injections.leaveTestingCode();
    }

    protected fun inIgnoredSection(): Boolean {
        return Injections.inIgnoredSection();
    }

    protected fun enterIgnoredSection(): Boolean {
        return Injections.enterIgnoredSection()
    }

    protected fun leaveIgnoredSection() {
        return Injections.leaveIgnoredSection()
    }

    override fun beforeNewObjectCreation(className: String) = runInIgnoredSection {
        LincheckJavaAgent.ensureClassHierarchyIsTransformed(className)
    }

    override fun afterNewObjectCreation(obj: Any) {
        if (obj is String || obj is Int || obj is Long || obj is Byte || obj is Char || obj is Float || obj is Double) return
        runInIgnoredSection {
            objectTracker.registerNewObject(obj)
        }
    }

    private fun methodGuaranteeType(owner: Any?, className: String, methodName: String): ManagedGuaranteeType? = runInIgnoredSection {
        userDefinedGuarantees?.forEach { guarantee ->
            val ownerName = owner?.javaClass?.canonicalName ?: className
            if (guarantee.classPredicate(ownerName) && guarantee.methodPredicate(methodName)) {
                return guarantee.type
            }
        }

        return null
    }

    override fun beforeMethodCall(
        owner: Any?,
        className: String,
        methodName: String,
        codeLocation: Int,
        methodId: Int,
        params: Array<Any?>
    ) {
        val guarantee = runInIgnoredSection {
            val atomicMethodDescriptor = getAtomicMethodDescriptor(owner, methodName)
            val guarantee = when {
                (atomicMethodDescriptor != null) -> ManagedGuaranteeType.TREAT_AS_ATOMIC
                else -> methodGuaranteeType(owner, className, methodName)
            }
            if (owner == null && atomicMethodDescriptor == null && guarantee == null) { // static method
                LincheckJavaAgent.ensureClassHierarchyIsTransformed(className.canonicalClassName)
            }
            if (collectTrace) {
                traceCollector!!.checkActiveLockDetected()
                addBeforeMethodCallTracePoint(owner, codeLocation, methodId, className, methodName, params, atomicMethodDescriptor)
            }
            if (guarantee == ManagedGuaranteeType.TREAT_AS_ATOMIC) {
                newSwitchPointOnAtomicMethodCall(codeLocation, params)
            }
            if (guarantee == null) {
                loopDetector.beforeMethodCall(codeLocation, params)
            }
            guarantee
        }
        if (guarantee == ManagedGuaranteeType.IGNORE ||
            guarantee == ManagedGuaranteeType.TREAT_AS_ATOMIC) {
            // It's important that this method can't be called inside runInIgnoredSection, as the ignored section
            // flag would be set to false when leaving runInIgnoredSection,
            // so enterIgnoredSection would have no effect
            enterIgnoredSection()
        }
    }

    override fun onMethodCallReturn(result: Any?) {
        runInIgnoredSection {
            loopDetector.afterMethodCall()
            if (collectTrace) {
                val iThread = threadScheduler.getCurrentThreadId()
                // this case is possible and can occur when we resume the coroutine,
                // and it results in a call to a top-level actor `suspend` function;
                // currently top-level actor functions are not represented in the `callStackTrace`,
                // we should probably refactor and fix that, because it is very inconvenient
                if (callStackTrace[iThread]!!.isEmpty())
                    return@runInIgnoredSection
                val tracePoint = callStackTrace[iThread]!!.last().tracePoint
                when (result) {
                    Injections.VOID_RESULT -> tracePoint.initializeVoidReturnedValue()
                    COROUTINE_SUSPENDED -> tracePoint.initializeCoroutineSuspendedResult()
                    else -> tracePoint.initializeReturnedValue(adornedStringRepresentation(result))
                }
                afterMethodCall(iThread, tracePoint)
                traceCollector!!.addStateRepresentation()
            }
        }
        // In case the code is now in an "ignore" section due to
        // an "atomic" or "ignore" guarantee, we need to leave
        // this "ignore" section.
        leaveIgnoredSection()
    }

    override fun onMethodCallException(t: Throwable) {
        runInIgnoredSection {
            loopDetector.afterMethodCall()
        }
        if (collectTrace) {
            runInIgnoredSection {
                // We cannot simply read `thread` as Forcible???Exception can be thrown.
                val threadId = threadScheduler.getCurrentThreadId()
                val tracePoint = callStackTrace[threadId]!!.last().tracePoint
                tracePoint.initializeThrownException(t)
                afterMethodCall(threadId, tracePoint)
                traceCollector!!.addStateRepresentation()
            }
        }
        // In case the code is now in an "ignore" section due to
        // an "atomic" or "ignore" guarantee, we need to leave
        // this "ignore" section.
        leaveIgnoredSection()
    }

    private fun newSwitchPointOnAtomicMethodCall(codeLocation: Int, params: Array<Any?>) {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        // re-use last call trace point
        newSwitchPoint(currentThreadId, codeLocation, callStackTrace[currentThreadId]!!.lastOrNull()?.tracePoint)
        loopDetector.passParameters(params)
    }

    private fun isSuspendFunction(className: String, methodName: String, params: Array<Any?>): Boolean =
        try {
            // While this code is inefficient, it is called only when an error is detected.
            getMethod(className.canonicalClassName, methodName, params)?.isSuspendable() ?: false
        } catch (t: Throwable) {
            // Something went wrong. Ignore it, as the error might lead only
            // to an extra "<cont>" in the method call line in the trace.
            false
        }

    private fun getMethod(className: String, methodName: String, params: Array<Any?>): Method? {
        val clazz = Class.forName(className)

        // Filter methods by name
        val possibleMethods = clazz.declaredMethods.filter { it.name == methodName }

        for (method in possibleMethods) {
            val parameterTypes = method.parameterTypes
            if (parameterTypes.size != params.size) continue

            var match = true
            for (i in parameterTypes.indices) {
                val paramType = params[i]?.javaClass
                if (paramType != null && !parameterTypes[i].isAssignableFrom(paramType)) {
                    match = false
                    break
                }
            }

            if (match) return method
        }

        return null // or throw an exception if a match is mandatory
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was suspended.
     * @param iThread number of invoking thread
     */
    internal fun afterCoroutineSuspended(iThread: Int) {
        check(threadScheduler.getCurrentThreadId() == iThread)
        isSuspended[iThread] = true
        if (runner.isCoroutineResumed(iThread, currentActorId[iThread]!!)) {
            // `COROUTINE_SUSPENSION_CODE_LOCATION`, because we do not know the actual code location
            newSwitchPoint(iThread, UNKNOWN_CODE_LOCATION, null)
        } else {
            // coroutine suspension does not violate obstruction-freedom
            switchCurrentThread(iThread, SwitchReason.SUSPENDED)
        }
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was resumed.
     */
    internal fun afterCoroutineResumed() {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        isSuspended[currentThreadId] = false
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was cancelled.
     */
    internal fun afterCoroutineCancelled() {
        val iThread = threadScheduler.getCurrentThreadId()
        isSuspended[iThread] = false
        // method will not be resumed after suspension, so clear prepared for resume call stack
        suspendedFunctionsStack[iThread]!!.clear()
    }

    private fun addBeforeMethodCallTracePoint(
        owner: Any?,
        codeLocation: Int,
        methodId: Int,
        className: String,
        methodName: String,
        methodParams: Array<Any?>,
        atomicMethodDescriptor: AtomicMethodDescriptor?,
    ) {
        val iThread = threadScheduler.getCurrentThreadId()
        val callStackTrace = callStackTrace[iThread]!!
        val suspendedMethodStack = suspendedFunctionsStack[iThread]!!
        val isSuspending = isSuspendFunction(className, methodName, methodParams)
        val isResumption = isSuspending && suspendedMethodStack.isNotEmpty()
        if (isResumption) {
            // In case of resumption, we need to find a call stack frame corresponding to the resumed function
            var elementIndex = suspendedMethodStack.indexOfFirst {
                it.tracePoint.className == className && it.tracePoint.methodName == methodName
            }
            if (elementIndex == -1) {
                // this case is possible and can occur when we resume the coroutine,
                // and it results in a call to a top-level actor `suspend` function;
                // currently top-level actor functions are not represented in the `callStackTrace`,
                // we should probably refactor and fix that, because it is very inconvenient
                val actor = scenario.threads[iThread][currentActorId[iThread]!!]
                check(methodName == actor.method.name)
                check(className.canonicalClassName == actor.method.declaringClass.name)
                elementIndex = suspendedMethodStack.size
            }
            // get suspended stack trace elements to restore
            val resumedStackTrace = suspendedMethodStack
                .subList(elementIndex, suspendedMethodStack.size)
                .reversed()
            // we assume that all methods lying below the resumed one in stack trace
            // have empty resumption part or were already resumed before,
            // so we remove them from the suspended methods stack.
            suspendedMethodStack.subList(0, elementIndex).clear()
            // we need to restore suspended stack trace elements
            // if they are not on the top of the current stack trace
            if (!resumedStackTrace.isSuffixOf(callStackTrace)) {
                // restore resumed stack trace elements
                callStackTrace.addAll(resumedStackTrace)
                resumedStackTrace.forEach { beforeMethodEnter(it.instance) }
            }
            // since we are in resumption, skip the next ` beforeEvent ` call
            skipNextBeforeEvent = true
            return
        }
        val callId = callStackTraceElementId++
        val params = if (isSuspending) {
            methodParams.dropLast(1).toTypedArray()
        } else {
            methodParams
        }
        // The code location of the new method call is currently the last one
        val tracePoint = createBeforeMethodCallTracePoint(
            iThread = iThread,
            owner = owner,
            className = className,
            methodName = methodName,
            params = params,
            codeLocation = codeLocation,
            atomicMethodDescriptor = atomicMethodDescriptor,
        )
        // Method invocation id used to calculate spin cycle start label call depth.
        // Two calls are considered equals if two same methods were called with the same parameters.
        val methodInvocationId = Objects.hash(methodId,
            params.map { primitiveOrIdentityHashCode(it) }.toTypedArray().contentHashCode()
        )
        val stackTraceElement = CallStackTraceElement(
            id = callId,
            tracePoint = tracePoint,
            instance = owner,
            methodInvocationId = methodInvocationId
        )
        callStackTrace.add(stackTraceElement)
        beforeMethodEnter(owner)
    }

    private fun createBeforeMethodCallTracePoint(
        iThread: Int,
        owner: Any?,
        className: String,
        methodName: String,
        params: Array<Any?>,
        codeLocation: Int,
        atomicMethodDescriptor: AtomicMethodDescriptor?,
    ): MethodCallTracePoint {
        val callStackTrace = callStackTrace[iThread]!!
        val tracePoint = MethodCallTracePoint(
            iThread = iThread,
            actorId = currentActorId[iThread]!!,
            className = className,
            methodName = methodName,
            callStackTrace = callStackTrace,
            stackTraceElement = CodeLocations.stackTrace(codeLocation)
        )
        // handle non-atomic methods
        if (atomicMethodDescriptor == null) {
            val ownerName = if (owner != null) findOwnerName(owner) else simpleClassName(className)
            if (ownerName != null) {
                tracePoint.initializeOwnerName(ownerName)
            }
            tracePoint.initializeParameters(params.map { adornedStringRepresentation(it) })
            return tracePoint
        }
        // handle atomic methods
        if (isVarHandle(owner)) {
            return initializeVarHandleMethodCallTracePoint(tracePoint, owner, params)
        }
        if (isAtomicFieldUpdater(owner)) {
            return initializeAtomicUpdaterMethodCallTracePoint(tracePoint, owner!!, params)
        }
        if (isAtomic(owner) || isAtomicArray(owner)) {
            return initializeAtomicReferenceMethodCallTracePoint(tracePoint, owner!!, params)
        }
        if (isUnsafe(owner)) {
            return initializeUnsafeMethodCallTracePoint(tracePoint, owner!!, params)
        }
        error("Unknown atomic method $className::$methodName")
    }

    private fun simpleClassName(className: String) = className.takeLastWhile { it != '/' }

    private fun initializeUnsafeMethodCallTracePoint(
        tracePoint: MethodCallTracePoint,
        receiver: Any,
        params: Array<Any?>
    ): MethodCallTracePoint {
        when (val unsafeMethodName = UnsafeNames.getMethodCallType(params)) {
            is UnsafeArrayMethod -> {
                val owner = "${adornedStringRepresentation(unsafeMethodName.array)}[${unsafeMethodName.index}]"
                tracePoint.initializeOwnerName(owner)
                tracePoint.initializeParameters(unsafeMethodName.parametersToPresent.map { adornedStringRepresentation(it) })
            }
            is UnsafeName.TreatAsDefaultMethod -> {
                tracePoint.initializeOwnerName(adornedStringRepresentation(receiver))
                tracePoint.initializeParameters(params.map { adornedStringRepresentation(it) })
            }
            is UnsafeInstanceMethod -> {
                val ownerName = findOwnerName(unsafeMethodName.owner)
                val owner = ownerName?.let { "$ownerName.${unsafeMethodName.fieldName}" } ?: unsafeMethodName.fieldName
                tracePoint.initializeOwnerName(owner)
                tracePoint.initializeParameters(unsafeMethodName.parametersToPresent.map { adornedStringRepresentation(it) })
            }
            is UnsafeStaticMethod -> {
                tracePoint.initializeOwnerName("${unsafeMethodName.clazz.simpleName}.${unsafeMethodName.fieldName}")
                tracePoint.initializeParameters(unsafeMethodName.parametersToPresent.map { adornedStringRepresentation(it) })
            }
        }

        return tracePoint
    }

    private fun initializeAtomicReferenceMethodCallTracePoint(
        tracePoint: MethodCallTracePoint,
        receiver: Any,
        params: Array<Any?>
    ): MethodCallTracePoint {
        when (val atomicReferenceInfo = AtomicReferenceNames.getMethodCallType(runner.testInstance, receiver, params)) {
            is AtomicArrayMethod -> {
                tracePoint.initializeOwnerName("${adornedStringRepresentation(atomicReferenceInfo.atomicArray)}[${atomicReferenceInfo.index}]")
                tracePoint.initializeParameters(params.drop(1).map { adornedStringRepresentation(it) })
            }
            is InstanceFieldAtomicArrayMethod -> {
                val receiverName = findOwnerName(atomicReferenceInfo.owner)
                tracePoint.initializeOwnerName((receiverName?.let { "$it." } ?: "") + "${atomicReferenceInfo.fieldName}[${atomicReferenceInfo.index}]")
                tracePoint.initializeParameters(params.drop(1).map { adornedStringRepresentation(it) })
            }
            is AtomicReferenceInstanceMethod -> {
                val receiverName = findOwnerName(atomicReferenceInfo.owner)
                tracePoint.initializeOwnerName(receiverName?.let { "$it.${atomicReferenceInfo.fieldName}" } ?: atomicReferenceInfo.fieldName)
                tracePoint.initializeParameters(params.map { adornedStringRepresentation(it) })
            }
            is AtomicReferenceStaticMethod ->  {
                tracePoint.initializeOwnerName("${atomicReferenceInfo.ownerClass.simpleName}.${atomicReferenceInfo.fieldName}")
                tracePoint.initializeParameters(params.map { adornedStringRepresentation(it) })
            }
            is StaticFieldAtomicArrayMethod -> {
                tracePoint.initializeOwnerName("${atomicReferenceInfo.ownerClass.simpleName}.${atomicReferenceInfo.fieldName}[${atomicReferenceInfo.index}]")
                tracePoint.initializeParameters(params.drop(1).map { adornedStringRepresentation(it) })
            }
            AtomicReferenceMethodType.TreatAsDefaultMethod -> {
                tracePoint.initializeOwnerName(adornedStringRepresentation(receiver))
                tracePoint.initializeParameters(params.map { adornedStringRepresentation(it) })
            }
        }
        return tracePoint
    }

    private fun initializeVarHandleMethodCallTracePoint(
        tracePoint: MethodCallTracePoint,
        varHandle: Any, // for Java 8, the VarHandle class does not exist
        parameters: Array<Any?>,
    ): MethodCallTracePoint {
        when (val varHandleMethodType = VarHandleNames.varHandleMethodType(varHandle, parameters)) {
            is ArrayVarHandleMethod -> {
                tracePoint.initializeOwnerName("${adornedStringRepresentation(varHandleMethodType.array)}[${varHandleMethodType.index}]")
                tracePoint.initializeParameters(varHandleMethodType.parameters.map { adornedStringRepresentation(it) })
            }
            VarHandleMethodType.TreatAsDefaultMethod -> {
                tracePoint.initializeOwnerName(adornedStringRepresentation(varHandle))
                tracePoint.initializeParameters(parameters.map { adornedStringRepresentation(it) })
            }
            is InstanceVarHandleMethod -> {
                val receiverName = findOwnerName(varHandleMethodType.owner)
                tracePoint.initializeOwnerName(receiverName?.let { "$it.${varHandleMethodType.fieldName}" } ?: varHandleMethodType.fieldName)
                tracePoint.initializeParameters(varHandleMethodType.parameters.map { adornedStringRepresentation(it) })
            }
            is StaticVarHandleMethod -> {
                tracePoint.initializeOwnerName("${varHandleMethodType.ownerClass.simpleName}.${varHandleMethodType.fieldName}")
                tracePoint.initializeParameters(varHandleMethodType.parameters.map { adornedStringRepresentation(it) })
            }
        }

        return tracePoint
    }

    private fun initializeAtomicUpdaterMethodCallTracePoint(
        tracePoint: MethodCallTracePoint,
        atomicUpdater: Any,
        parameters: Array<Any?>,
    ): MethodCallTracePoint {
        getAtomicFieldUpdaterName(atomicUpdater)?.let { tracePoint.initializeOwnerName(it) }
        tracePoint.initializeParameters(parameters.drop(1).map { adornedStringRepresentation(it) })
        return tracePoint
    }

    /**
     * Returns beautiful string representation of the [owner].
     * If the [owner] is `this` of the current method, then returns `null`.
     * Otherwise, we try to find if this [owner] is stored in only one field in the testObject
     * and this field is final. If such field is found we construct beautiful representation for
     * this field owner (if it's not a current `this`, again) and the field name.
     * Otherwise, return beautiful representation for the provided [owner].
     */
    private fun findOwnerName(owner: Any): String? {
        // If the current owner is this - no owner needed.
        if (isOwnerCurrentContext(owner)) return null
        val fieldWithOwner = findFinalFieldWithOwner(runner.testInstance, owner) ?: return adornedStringRepresentation(owner)
        // If such a field is found - construct representation with its owner and name.
        return if (fieldWithOwner is OwnerWithName.InstanceOwnerWithName) {
            val fieldOwner = fieldWithOwner.owner
            val fieldName = fieldWithOwner.fieldName
            if (!isOwnerCurrentContext(fieldOwner)) {
                "${adornedStringRepresentation(fieldOwner)}.$fieldName"
            } else fieldName
        } else null
    }

    /**
     * Checks if [owner] is the current `this` in the current method context.
     */
    private fun isOwnerCurrentContext(owner: Any): Boolean {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        return when (val callContext = callStackContextPerThread[currentThreadId]!!.last()) {
            is CallContext.InstanceCallContext -> callContext.instance === owner
            is CallContext.StaticCallContext -> false
        }
    }

    /* Methods to control the current call context. */

    private fun beforeMethodEnter(owner: Any?) {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        if (owner == null) {
            callStackContextPerThread[currentThreadId]!!.add(CallContext.StaticCallContext)
        } else {
            callStackContextPerThread[currentThreadId]!!.add(CallContext.InstanceCallContext(owner))
        }
    }

    private fun afterExitMethod(iThread: Int) {
        val currentContext = callStackContextPerThread[iThread]!!
        currentContext.removeLast()
        check(currentContext.isNotEmpty()) { "Context cannot be empty" }
    }

    /**
     * This method is invoked by a test thread
     * after each method invocation.
     * @param iThread number of invoking thread
     * @param tracePoint the corresponding trace point for the invocation
     */
    private fun afterMethodCall(iThread: Int, tracePoint: MethodCallTracePoint) {
        afterExitMethod(iThread)
        val callStackTrace = callStackTrace[iThread]!!
        if (tracePoint.wasSuspended) {
            // if a method call is suspended, save its call stack element to reuse for continuation resuming
            suspendedFunctionsStack[iThread]!!.add(callStackTrace.last())
        }
        callStackTrace.removeLast()
    }

    // == LOGGING METHODS ==

    /**
     * Creates a new [CoroutineCancellationTracePoint].
     */
    internal fun createAndLogCancellationTracePoint(): CoroutineCancellationTracePoint? {
        if (collectTrace) {
            val cancellationTracePoint = doCreateTracePoint(::CoroutineCancellationTracePoint)
            traceCollector?.passCodeLocation(cancellationTracePoint)
            return cancellationTracePoint
        }
        return null
    }

    private fun <T : TracePoint> doCreateTracePoint(constructor: (iThread: Int, actorId: Int, CallStackTrace) -> T): T {
        val iThread = threadScheduler.getCurrentThreadId()
        val actorId = currentActorId[iThread] ?: Int.MIN_VALUE
        return constructor(iThread, actorId, callStackTrace[iThread]?.toList() ?: emptyList())
    }

    override fun beforeEvent(eventId: Int, type: String) {
        ideaPluginBeforeEvent(eventId, type)
    }

    /**
     * This method is called before [beforeEvent] method call to provide current event (trace point) id.
     */
    override fun getEventId(): Int = eventIdProvider.currentId()

    /**
     * This method generates and sets separate event id for the last method call.
     * Method call trace points are not added to the event list by default, so their event ids are not set otherwise.
     */
    override fun setLastMethodCallEventId() {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        val lastMethodCall = callStackTrace[currentThreadId]!!.lastOrNull()?.tracePoint ?: return
        setBeforeEventId(lastMethodCall)
    }

    /**
     * Set eventId of the [tracePoint] right after it is added to the trace.
     */
    private fun setBeforeEventId(tracePoint: TracePoint) {
        if (shouldInvokeBeforeEvent()) {
            // Method calls and atomic method calls share the same trace points
            if (tracePoint.eventId == -1
                && tracePoint !is CoroutineCancellationTracePoint
                && tracePoint !is ObstructionFreedomViolationExecutionAbortTracePoint
                && tracePoint !is SpinCycleStartTracePoint
                && tracePoint !is SectionDelimiterTracePoint
            ) {
                tracePoint.eventId = eventIdProvider.nextId()
            }
        }
    }

    /**
     * Indicates if the next [beforeEvent] method call should be skipped.
     *
     * @see skipNextBeforeEvent
     */
    protected fun shouldSkipNextBeforeEvent(): Boolean {
        val skipBeforeEvent = skipNextBeforeEvent
        if (skipNextBeforeEvent) {
            skipNextBeforeEvent = false
        }
        return skipBeforeEvent
    }

    protected fun resetEventIdProvider() {
        eventIdProvider = EventIdProvider()
    }

    // == UTILITY METHODS ==

    /**
     * Logs thread events such as thread switches and passed code locations.
     */
    /**
     * Logs thread events such as thread switches and passed code locations.
     */
    private inner class TraceCollector {
        private val _trace = mutableListOf<TracePoint>()
        val trace: List<TracePoint> = _trace
        private var spinCycleStartAdded = false

        private val spinCycleMethodCallsStackTraces: MutableList<List<CallStackTraceElement>> = mutableListOf()

        fun newSwitch(iThread: Int, reason: SwitchReason, beforeMethodCallSwitch: Boolean = false) {
            if (reason == SwitchReason.ACTIVE_LOCK) {
                afterSpinCycleTraceCollected(
                    trace = _trace,
                    callStackTrace = callStackTrace[iThread]!!,
                    spinCycleMethodCallsStackTraces = spinCycleMethodCallsStackTraces,
                    iThread = iThread,
                    currentActorId = currentActorId[iThread]!!,
                    beforeMethodCallSwitch = beforeMethodCallSwitch
                )
            }
            _trace += SwitchEventTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread]!!,
                reason = reason,
                callStackTrace = callStackTrace[iThread]!!
            )
            spinCycleStartAdded = false
        }

        fun onThreadFinish() {
            spinCycleStartAdded = false
        }

        fun checkActiveLockDetected() {
            val currentThreadId = threadScheduler.getCurrentThreadId()
            if (!loopDetector.replayModeCurrentlyInSpinCycle) return
            if (spinCycleStartAdded) {
                spinCycleMethodCallsStackTraces += callStackTrace[currentThreadId]!!.toList()
                return
            }
            val spinCycleStartTracePoint = SpinCycleStartTracePoint(
                iThread = currentThreadId,
                actorId = currentActorId[currentThreadId]!!,
                callStackTrace = callStackTrace[currentThreadId]!!,
            )
            _trace.add(spinCycleStartTracePoint)
            spinCycleStartAdded = true
            spinCycleMethodCallsStackTraces.clear()
        }

        fun passCodeLocation(tracePoint: TracePoint?) {
            if (tracePoint !is SectionDelimiterTracePoint) checkActiveLockDetected()
            // tracePoint can be null here if trace is not available, e.g. in case of suspension
            if (tracePoint != null) {
                _trace += tracePoint
                setBeforeEventId(tracePoint)
            }
        }

        fun addStateRepresentation() {
            val currentThreadId = threadScheduler.getCurrentThreadId()
            val stateRepresentation = runner.constructStateRepresentation() ?: return
            // use call stack trace of the previous trace point
            val callStackTrace = callStackTrace[currentThreadId]!!
            _trace += StateRepresentationTracePoint(
                iThread = currentThreadId,
                actorId = currentActorId[currentThreadId]!!,
                stateRepresentation = stateRepresentation,
                callStackTrace = callStackTrace,
            )

        }

        fun passObstructionFreedomViolationTracePoint(iThread: Int, beforeMethodCall: Boolean) {
            val currentThreadId = threadScheduler.getCurrentThreadId()
            afterSpinCycleTraceCollected(
                trace = _trace,
                callStackTrace = callStackTrace[currentThreadId]!!,
                spinCycleMethodCallsStackTraces = spinCycleMethodCallsStackTraces,
                iThread = iThread,
                currentActorId = currentActorId[iThread]!!,
                beforeMethodCallSwitch = beforeMethodCall
            )
            _trace += ObstructionFreedomViolationExecutionAbortTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread]!!,
                callStackTrace = _trace.last().callStackTrace
            )
        }
    }



    /**
     * Utility class to set trace point ids for the Lincheck Plugin.
     *
     * It's methods have the following contract:
     *
     * [nextId] must be called first of after [currentId] call,
     *
     * [currentId] must be called only after [nextId] call.
     */
    private class EventIdProvider {

        /**
         * ID of the previous event.
         */
        private var lastId = -1

        // The properties below are needed only for debug purposes to provide an informative message
        // if ids are now strictly sequential.
        private var lastVisited = -1
        private var lastGeneratedId: Int? = null
        private var lastIdReturnedAsCurrent: Int? = null

        /**
         * Generates the id for the next trace point.
         */
        fun nextId(): Int {
            val nextId = ++lastId
            if (eventIdStrictOrderingCheck) {
                if (lastVisited + 1 != nextId) {
                    val lastRead = lastIdReturnedAsCurrent
                    if (lastRead == null) {
                        error("Create nextEventId $nextId readNextEventId has never been called")
                    } else {
                        error("Create nextEventId $nextId but last read event is $lastVisited, last read value is $lastIdReturnedAsCurrent")
                    }
                }
                lastGeneratedId = nextId
            }
            return nextId
        }

        /**
         * Returns the last generated id.
         * Also, if [eventIdStrictOrderingCheck] is enabled, checks that.
         */
        fun currentId(): Int {
            val id = lastId
            if (eventIdStrictOrderingCheck) {
                if (lastVisited + 1 != id) {
                    val lastIncrement = lastGeneratedId
                    if (lastIncrement == null) {
                        error("ReadNextEventId is called while nextEventId has never been called")
                    } else {
                        error("ReadNextEventId $id after previous value $lastVisited, last incremented value is $lastIncrement")
                    }
                }
                lastVisited = id
                lastIdReturnedAsCurrent = id
            }
            return id
        }
    }

    /**
     * Current method context in a call stack.
     */
    sealed interface CallContext {
        /**
         * Indicates that current method is static.
         */
        data object StaticCallContext: CallContext

        /**
         * Indicates that method is called on the instance.
         */
        data class InstanceCallContext(val instance: Any): CallContext
    }
}

/**
 * This class is a [ParallelThreadsRunner] with some overrides that add callbacks
 * to the strategy so that it can known about some required events.
 */
internal class ManagedStrategyRunner(
    private val managedStrategy: ManagedStrategy,
    testClass: Class<*>, validationFunction: Actor?, stateRepresentationMethod: Method?,
    timeoutMs: Long, useClocks: UseClocks
) : ParallelThreadsRunner(managedStrategy, testClass, validationFunction, stateRepresentationMethod, timeoutMs, useClocks) {

    override fun onThreadStart(iThread: Int) = runInIgnoredSection {
        if (currentExecutionPart !== PARALLEL) return
        managedStrategy.onThreadStart(iThread)
    }

    override fun onThreadFinish(iThread: Int) = runInIgnoredSection {
        if (currentExecutionPart !== PARALLEL) return
        managedStrategy.onThreadFinish(iThread)
    }

    override fun onFailure(iThread: Int, e: Throwable) = runInIgnoredSection {
        managedStrategy.onFailure(iThread, e)
    }

    override fun afterCoroutineSuspended(iThread: Int) = runInIgnoredSection {
        super.afterCoroutineSuspended(iThread)
        managedStrategy.afterCoroutineSuspended(iThread)
    }

    override fun afterCoroutineResumed(iThread: Int) = runInIgnoredSection {
        managedStrategy.afterCoroutineResumed()
    }

    override fun afterCoroutineCancelled(iThread: Int) = runInIgnoredSection {
        managedStrategy.afterCoroutineCancelled()
    }

    override fun constructStateRepresentation(): String? {
        if (stateRepresentationFunction == null) return null
        // Enter ignored section, because Runner will call transformed state representation method
        return runInIgnoredSection {
            super.constructStateRepresentation()
        }
    }

    override fun <T> cancelByLincheck(cont: CancellableContinuation<T>, promptCancellation: Boolean): CancellationResult = runInIgnoredSection {
        // Create a cancellation trace point before `cancel`, so that cancellation trace point
        // precede the events in `onCancellation` handler.
        val cancellationTracePoint = managedStrategy.createAndLogCancellationTracePoint()
        try {
            // Call the `cancel` method.
            val cancellationResult = super.cancelByLincheck(cont, promptCancellation)
            // Pass the result to `cancellationTracePoint`.
            cancellationTracePoint?.initializeCancellationResult(cancellationResult)
            // Invoke `strategy.afterCoroutineCancelled` if the coroutine was cancelled successfully.
            if (cancellationResult != CANCELLATION_FAILED)
                managedStrategy.afterCoroutineCancelled()
            return cancellationResult
        } catch (e: Throwable) {
            cancellationTracePoint?.initializeException(e)
            throw e // throw further
        }
    }
}

// currently the exact place of coroutine suspension is not known
internal const val UNKNOWN_CODE_LOCATION = -1

private val BlockingReason.obstructionFreedomViolationMessage: String get() = when (this) {
    BlockingReason.LOCKED       -> OBSTRUCTION_FREEDOM_LOCK_VIOLATION_MESSAGE
    BlockingReason.WAITING      -> OBSTRUCTION_FREEDOM_WAIT_VIOLATION_MESSAGE
    BlockingReason.PARKED       -> OBSTRUCTION_FREEDOM_PARK_VIOLATION_MESSAGE
    BlockingReason.THREAD_JOIN  -> OBSTRUCTION_FREEDOM_THREAD_JOIN_VIOLATION_MESSAGE
    BlockingReason.SUSPENDED    -> OBSTRUCTION_FREEDOM_SUSPEND_VIOLATION_MESSAGE
}

private const val OBSTRUCTION_FREEDOM_SPINLOCK_VIOLATION_MESSAGE =
    "The algorithm should be non-blocking, but an active lock is detected"

private const val OBSTRUCTION_FREEDOM_LOCK_VIOLATION_MESSAGE =
    "The algorithm should be non-blocking, but a lock is detected"

private const val OBSTRUCTION_FREEDOM_WAIT_VIOLATION_MESSAGE =
    "The algorithm should be non-blocking, but a wait call is detected"

private const val OBSTRUCTION_FREEDOM_PARK_VIOLATION_MESSAGE =
    "The algorithm should be non-blocking, but a thread park is detected"

private const val OBSTRUCTION_FREEDOM_THREAD_JOIN_VIOLATION_MESSAGE =
    "The algorithm should be non-blocking, but a thread join is detected"

private const val OBSTRUCTION_FREEDOM_SUSPEND_VIOLATION_MESSAGE =
    "The algorithm should be non-blocking, but a coroutine suspension is detected"

/**
 * With idea plugin enabled, we should not use default Lincheck timeout
 * as debugging may take more time than default timeout.
 */
private const val INFINITE_TIMEOUT = 1000L * 60 * 60 * 24 * 365

private fun getTimeOutMs(strategy: ManagedStrategy, defaultTimeOutMs: Long): Long =
    if (strategy is ModelCheckingStrategy && strategy.replay) INFINITE_TIMEOUT else defaultTimeOutMs
