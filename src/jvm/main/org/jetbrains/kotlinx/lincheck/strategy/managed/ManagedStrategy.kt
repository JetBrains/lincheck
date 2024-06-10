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
import org.jetbrains.kotlinx.lincheck.verifier.*
import sun.nio.ch.lincheck.*
import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicFieldUpdaterNames.getAtomicFieldUpdaterName
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicReferenceMethodType.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.FieldSearchHelper.findFinalFieldWithOwner
import org.jetbrains.kotlinx.lincheck.strategy.managed.ObjectLabelFactory.adornedStringRepresentation
import org.jetbrains.kotlinx.lincheck.strategy.managed.ObjectLabelFactory.cleanObjectNumeration
import org.jetbrains.kotlinx.lincheck.strategy.managed.UnsafeName.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.VarHandleMethodType.*
import java.lang.invoke.VarHandle
import java.lang.reflect.*
import java.util.*
import java.util.concurrent.atomic.*
import kotlin.collections.set
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
    private val verifier: Verifier,
    private val validationFunction: Actor?,
    private val stateRepresentationFunction: Method?,
    private val testCfg: ManagedCTestConfiguration
) : Strategy(scenario), EventTracker {
    // The number of parallel threads.
    protected val nThreads: Int = scenario.nThreads

    // Runner for scenario invocations,
    // can be replaced with a new one for trace construction.
    internal var runner: ManagedStrategyRunner = createRunner()
    // Spin-waiters for each thread
    private val spinners = SpinnerGroup(nThreads)

    // == EXECUTION CONTROL FIELDS ==

    // Which thread is allowed to perform operations?
    @Volatile
    protected var currentThread: Int = 0

    // Which threads finished all the operations?
    private val finished = BooleanArray(nThreads) { false }

    // Which threads are suspended?
    private val isSuspended = BooleanArray(nThreads) { false }

    // Current actor id for each thread.
    protected val currentActorId = IntArray(nThreads)
    
    // Detector of loops or hangs (i.e. active locks).
    internal val loopDetector: LoopDetector = LoopDetector(testCfg.hangingDetectionThreshold)

    // Tracker of acquisitions and releases of monitors.
    private lateinit var monitorTracker: MonitorTracker

    // InvocationResult that was observed by the strategy during the execution (e.g., a deadlock).
    @Volatile
    protected var suddenInvocationResult: InvocationResult? = null

    // == TRACE CONSTRUCTION FIELDS ==

    // Whether an additional information requires for the trace construction should be collected.
    protected var collectTrace = false

    // Collector of all events in the execution such as thread switches.
    private var traceCollector: TraceCollector? = null // null when `collectTrace` is false

    // Stores the currently executing methods call stack for each thread.
    private val callStackTrace = Array(nThreads) { mutableListOf<CallStackTraceElement>() }

    // Stores the global number of method calls.
    private var methodCallNumber = 0

    // In case of suspension, the call stack of the corresponding `suspend`
    // methods is stored here, so that the same method call identifiers are
    // used on resumption, and the trace point before and after the suspension
    // correspond to the same method call in the trace.
    private val suspendedFunctionsStack = Array(nThreads) { mutableListOf<Int>() }

    // Helps to ignore potential switch point in local objects (see LocalObjectManager) to avoid
    // useless interleavings analysis.
    private var localObjectManager = LocalObjectManager()

    // Last read trace point, occurred in the current thread.
    // We store it as we initialize read value after the point is created so we have to store
    // the trace point somewhere to obtain it later.
    private var lastReadTracePoint = Array<ReadTracePoint?>(nThreads) { null }

    // Random instances with fixed seeds to replace random calls in instrumented code.
    private var randoms = (0 until nThreads + 2).map { Random(it + 239L) }

    // Current call stack for a thread, updated during beforeMethodCall and afterMethodCall methods.
    private val methodCallTracePointStack = (0 until nThreads + 2).map { mutableListOf<MethodCallTracePoint>() }

    // User-specified guarantees on specific function, which can be considered as atomic or ignored.
    private val userDefinedGuarantees: List<ManagedStrategyGuarantee>? = testCfg.guarantees.ifEmpty { null }

    // Utility class for the plugin integration to provide ids for each trace point
    private var eventIdProvider = EventIdProvider()

    /**
     * Current method call context (static or instance).
     * Initialized and used only in the trace collecting stage.
     */
    private lateinit var callStackContextPerThread: Array<ArrayList<CallContext>>

    private fun createRunner(): ManagedStrategyRunner =
        ManagedStrategyRunner(
            managedStrategy = this,
            testClass = testClass,
            validationFunction = validationFunction,
            stateRepresentationMethod = stateRepresentationFunction,
            timeoutMs = getTimeOutMs(this, testCfg.timeoutMs),
            useClocks = UseClocks.ALWAYS
        )

    override fun run(): LincheckFailure? = try {
        runImpl()
    } finally {
        runner.close()
        // clear the numeration at the end to avoid memory leaks
        cleanObjectNumeration()
    }

    // == STRATEGY INTERFACE METHODS ==

    /**
     * This method implements the strategy logic.
     */
    protected abstract fun runImpl(): LincheckFailure?

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
     * Returns all data to the initial state.
     */
    protected open fun initializeInvocation() {
        finished.fill(false)
        isSuspended.fill(false)
        currentActorId.fill(-1)
        monitorTracker = MonitorTracker(nThreads)
        traceCollector = if (collectTrace) TraceCollector() else null
        suddenInvocationResult = null
        callStackTrace.forEach { it.clear() }
        suspendedFunctionsStack.forEach { it.clear() }
        randoms.forEachIndexed { i, r -> r.setSeed(i + 239L) }
        localObjectManager = LocalObjectManager()
    }

    override fun beforePart(part: ExecutionPart) {
        traceCollector?.passCodeLocation(SectionDelimiterTracePoint(part))
    }

    // == BASIC STRATEGY METHODS ==

    /**
     * Checks whether the [result] is a failing one or is [CompletedInvocationResult]
     * but the verification fails, and return the corresponding failure.
     * Returns `null` if the result is correct.
     */
    protected fun checkResult(result: InvocationResult): LincheckFailure? = when (result) {
        is CompletedInvocationResult -> {
            if (verifier.verifyResults(scenario, result.results)) null
            else IncorrectResultsFailure(scenario, result.results, collectTrace(result))
        }
        // In case the runner detects a deadlock,
        // some threads can still work with the current strategy instance
        // and simultaneously adding events to the TraceCollector, which leads to an inconsistent trace.
        // Therefore, if the runner detects a deadlock, we don’t even try to collect a trace.
        is RunnerTimeoutInvocationResult -> result.toLincheckFailure(scenario, trace = null)
        else -> result.toLincheckFailure(scenario, collectTrace(result))
    }

    /**
     * Re-runs the last invocation to collect its trace.
     */
    private fun collectTrace(failingResult: InvocationResult): Trace? {
        val detectedByStrategy = suddenInvocationResult != null
        val canCollectTrace = when {
            detectedByStrategy -> true // ObstructionFreedomViolationInvocationResult or UnexpectedExceptionInvocationResult
            failingResult is CompletedInvocationResult -> true
            failingResult is ValidationFailureInvocationResult -> true
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
                failingResult is ManagedDeadlockInvocationResult ||
                failingResult is ObstructionFreedomViolationInvocationResult
        )
        cleanObjectNumeration()

        runner.close()
        runner = createRunner()
        val loggedResults = runInvocation()
        // In case the runner detects a deadlock, some threads can still be in an active state,
        // simultaneously adding events to the TraceCollector, which leads to an inconsistent trace.
        // Therefore, if the runner detects deadlock, we don't even try to collect trace.
        if (loggedResults is RunnerTimeoutInvocationResult) return null
        val sameResultTypes = loggedResults.javaClass == failingResult.javaClass
        val sameResults =
            loggedResults !is CompletedInvocationResult || failingResult !is CompletedInvocationResult || loggedResults.results == failingResult.results
        check(sameResultTypes && sameResults) {
            StringBuilder().apply {
                appendln("Non-determinism found. Probably caused by non-deterministic code (WeakHashMap, Object.hashCode, etc).")
                appendln("== Reporting the first execution without execution trace ==")
                appendln(failingResult.toLincheckFailure(scenario, null))
                appendln("== Reporting the second execution ==")
                appendln(loggedResults.toLincheckFailure(scenario, Trace(traceCollector!!.trace)).toString())
            }.toString()
        }

        return Trace(traceCollector!!.trace)
    }

    fun initializeCallStack(testInstance: Any) {
        if (collectTrace) {
            callStackContextPerThread = Array(nThreads) { arrayListOf(CallContext.InstanceCallContext(testInstance)) }
        }
    }

    /**
     * Runs the next invocation with the same [scenario][ExecutionScenario].
     */
    protected fun runInvocation(): InvocationResult {
        initializeInvocation()
        val result = runner.run()
        // In case the runner detects a deadlock, some threads can still manipulate the current strategy,
        // so we're not interested in suddenInvocationResult in this case
        // and immediately return RunnerTimeoutInvocationResult.
        if (result is RunnerTimeoutInvocationResult) {
            return result
        }
        // Has strategy already determined the invocation result?
        suddenInvocationResult?.let {
            // Unexpected `ForcibleExecutionFinishError` should be thrown.
            check(result is UnexpectedExceptionInvocationResult)
            return it
        }
        return result
    }

    private fun failDueToDeadlock(): Nothing {
        suddenInvocationResult = ManagedDeadlockInvocationResult(runner.collectExecutionResults())
        // Forcibly finish the current execution by throwing an exception.
        throw ForcibleExecutionFinishError
    }

    private fun failDueToLivelock(lazyMessage: () -> String): Nothing {
        suddenInvocationResult = ObstructionFreedomViolationInvocationResult(lazyMessage(), runner.collectExecutionResults())
        // Forcibly finish the current execution by throwing an exception.
        throw ForcibleExecutionFinishError
    }

    private fun failIfObstructionFreedomIsRequired(lazyMessage: () -> String) {
        if (testCfg.checkObstructionFreedom && !currentActorIsBlocking && !concurrentActorCausesBlocking) {
            failDueToLivelock(lazyMessage)
        }
    }

    private val currentActorIsBlocking: Boolean
        get() {
            val actorId = currentActorId[currentThread]
            // Handle the case when the first actor has not yet started,
            // see https://github.com/JetBrains/lincheck/pull/277
            if (actorId < 0) return false
            return scenario.threads[currentThread][actorId].blocking
        }

    private val concurrentActorCausesBlocking: Boolean
        get() = currentActorId.mapIndexed { iThread, actorId ->
                    if (iThread != currentThread && actorId >= 0 && !finished[iThread])
                        scenario.threads[iThread][actorId]
                    else null
                }.filterNotNull().any { it.causesBlocking }

    // == EXECUTION CONTROL METHODS ==

    /**
     * Create a new switch point, where a thread context switch can occur.
     * @param iThread the current thread
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of the point in code.
     */
    private fun newSwitchPoint(iThread: Int, codeLocation: Int, tracePoint: TracePoint?) {
        // Throw ForcibleExecutionFinishException if the invocation
        // result is already calculated.
        if (suddenInvocationResult != null) throw ForcibleExecutionFinishError
        // check we are in the right thread
        check(iThread == currentThread)
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
        if (decision.isLivelockDetected()) {
            failIfObstructionFreedomIsRequired {
                if (decision is LoopDetector.Decision.LivelockFailureDetected) {
                    // if failure is detected, add a special obstruction-freedom violation
                    // trace point to account for that
                    traceCollector?.passObstructionFreedomViolationTracePoint(currentThread, tracePoint is MethodCallTracePoint)
                } else {
                    // otherwise log the last event that caused obstruction-freedom violation
                    traceCollector?.passCodeLocation(tracePoint)
                }
                OBSTRUCTION_FREEDOM_SPINLOCK_VIOLATION_MESSAGE
            }
        }
        // if live-lock failure was detected, then fail immediately
        if (decision is LoopDetector.Decision.LivelockFailureDetected) {
            traceCollector?.newSwitch(currentThread, SwitchReason.ACTIVE_LOCK, tracePoint is MethodCallTracePoint)
            failDueToDeadlock()
        }
        // if live-lock was detected, and replay was requested,
        // then abort current execution and start the replay
        if (decision is LoopDetector.Decision.LivelockReplayRequired || decision is LoopDetector.Decision.LivelockReplayToDetectCycleRequired) {
            suddenInvocationResult = SpinCycleFoundAndReplayRequired
            throw ForcibleExecutionFinishError
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

    /**
     * This method is executed as the first thread action.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     */
    open fun onStart(iThread: Int) {
        awaitTurn(iThread)
    }

    /**
     * This method is executed as the last thread action if no exception has been thrown.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     */
    open fun onFinish(iThread: Int) {
        awaitTurn(iThread)
        finished[iThread] = true
        loopDetector.onThreadFinish(iThread)
        traceCollector?.onThreadFinish()
        doSwitchCurrentThread(iThread, true)
    }

    /**
     * This method is executed if an illegal exception has been thrown (see [exceptionCanBeValidExecutionResult]).
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param exception the exception that was thrown
     */
    open fun onFailure(iThread: Int, exception: Throwable) {
        // This method is called only if exception can't be treated as a normal operation result,
        // so we exit testing code to avoid trace collection resume or some bizarre bugs
        (Thread.currentThread() as TestThread).inTestingCode = false
        // Despite the fact that the corresponding failure will be detected by the runner,
        // the managed strategy can construct a trace to reproduce this failure.
        // Let's then store the corresponding failing result and construct the trace.
        if (exception === ForcibleExecutionFinishError) return // not a forcible execution finish
        suddenInvocationResult = UnexpectedExceptionInvocationResult(exception, runner.collectExecutionResults())
    }

    override fun onActorStart(iThread: Int) = runInIgnoredSection {
        currentActorId[iThread]++
        callStackTrace[iThread].clear()
        suspendedFunctionsStack[iThread].clear()
        loopDetector.onActorStart(iThread)
        (Thread.currentThread() as TestThread).inTestingCode = true
    }

    override fun onActorFinish() {
        // This is a hack to guarantee correct stepping in the plugin.
        // When stepping out to the TestThreadExecution class, stepping continues unproductively.
        // With this method, we force the debugger to stop at the beginning of the next actor.
        onThreadSwitchesOrActorFinishes()
        (Thread.currentThread() as TestThread).inTestingCode = false
    }

    /**
     * Returns whether the specified thread is active and
     * can continue its execution (i.e. is not blocked/finished).
     */
    private fun isActive(iThread: Int): Boolean =
        !finished[iThread] &&
        !monitorTracker.isWaiting(iThread) &&
        !(isSuspended[iThread] && !runner.isCoroutineResumed(iThread, currentActorId[iThread]))

    /**
     * Waits until the specified thread can continue
     * the execution according to the strategy decision.
     */
    private fun awaitTurn(iThread: Int) = runInIgnoredSection {
        spinners[iThread].spinWaitUntil {
            // Finish forcibly if an error occurred and we already have an `InvocationResult`.
            if (suddenInvocationResult != null) throw ForcibleExecutionFinishError
            currentThread == iThread
        }
    }

    /**
     * A regular context thread switch to another thread.
     */
    private fun switchCurrentThread(
        iThread: Int,
        reason: SwitchReason = SwitchReason.STRATEGY_SWITCH,
        mustSwitch: Boolean = false,
        tracePoint: TracePoint? = null
    ): Boolean {
        traceCollector?.newSwitch(iThread, reason, tracePoint != null && tracePoint is MethodCallTracePoint)
        doSwitchCurrentThread(iThread, mustSwitch)
        val switchHappened = iThread != currentThread
        awaitTurn(iThread)
        return switchHappened
    }

    private fun doSwitchCurrentThread(iThread: Int, mustSwitch: Boolean = false) {
        onNewSwitch(iThread, mustSwitch)
        val switchableThreads = switchableThreads(iThread)
        if (switchableThreads.isEmpty()) {
            if (mustSwitch && !finished.all { it }) {
                // All threads are suspended
                // then switch on any suspended thread to finish it and get SuspendedResult
                val nextThread = (0 until nThreads).firstOrNull { !finished[it] && isSuspended[it] }
                if (nextThread == null) {
                    // must switch not to get into a deadlock, but there are no threads to switch.
                    suddenInvocationResult = ManagedDeadlockInvocationResult(runner.collectExecutionResults())
                    // forcibly finish execution by throwing an exception.
                    throw ForcibleExecutionFinishError
                }
                setCurrentThread(nextThread)
            }
            return // ignore switch, because there is no one to switch to
        }
        val nextThread = chooseThread(iThread)
        setCurrentThread(nextThread)
    }

    @JvmName("setNextThread")
    private fun setCurrentThread(nextThread: Int) {
        loopDetector.onThreadSwitch(nextThread)
        currentThread = nextThread
    }

    /**
     * Threads to which an execution can be switched from thread [iThread].
     */
    protected fun switchableThreads(iThread: Int) =
        if (runner.currentExecutionPart == PARALLEL) {
            (0 until nThreads).filter { it != iThread && isActive(it) }
        } else {
            emptyList()
        }

    // == LISTENING METHODS ==

    override fun beforeLock(codeLocation: Int): Unit = runInIgnoredSection {
        val tracePoint = if (collectTrace) {
            val iThread = currentThread
            MonitorEnterTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread],
                callStackTrace = callStackTrace[iThread],
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            )
        } else {
            null
        }
        val iThread = currentThread
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
        val iThread = currentThread
        // Try to acquire the monitor
        while (!monitorTracker.acquireMonitor(iThread, monitor)) {
            failIfObstructionFreedomIsRequired {
                // TODO: This might be a false positive when this MONITORENTER call never suspends.
                // TODO: We can keep it as is until refactoring, as this weird case is an anti-pattern anyway.
                OBSTRUCTION_FREEDOM_LOCK_VIOLATION_MESSAGE
            }
            // Switch to another thread and wait for a moment when the monitor can be acquired
            switchCurrentThread(iThread, SwitchReason.LOCK_WAIT, true)
        }
    }

    override fun unlock(monitor: Any, codeLocation: Int): Unit = runInIgnoredSection {
        // We need to be extremely careful with the MONITOREXIT instruction,
        // as it can be put into a recursive "finally" block, releasing
        // the lock over and over until the instruction succeeds.
        // Therefore, we always release the lock in this case,
        // without tracking the event.
        if (suddenInvocationResult != null) return
        monitorTracker.releaseMonitor(monitor)
        if (collectTrace) {
            val iThread = currentThread
            val tracePoint = MonitorExitTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread],
                callStackTrace = callStackTrace[iThread],
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            )
            traceCollector!!.passCodeLocation(tracePoint)
        }
    }

    override fun park(codeLocation: Int): Unit = runInIgnoredSection {
        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            ParkTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread],
                callStackTrace = callStackTrace[iThread],
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            )
        } else {
            null
        }
        // Instead of fairly supporting the park/unpark semantics,
        // we simply add a new switch point here, thus, also
        // emulating spurious wake-ups.
        newSwitchPoint(iThread, codeLocation, tracePoint)
    }

    override fun unpark(thread: Thread, codeLocation: Int): Unit = runInIgnoredSection {
        val iThread = currentThread
        // We don't suspend on `park()` calls to emulate spurious wake-ups,
        // therefore, no actions are needed.
        if (collectTrace) {
            val tracePoint = UnparkTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread],
                callStackTrace = callStackTrace[iThread],
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            )
            traceCollector?.passCodeLocation(tracePoint)
        }
    }

    override fun beforeWait(codeLocation: Int): Unit = runInIgnoredSection {
        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            WaitTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread],
                callStackTrace = callStackTrace[iThread],
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
        val iThread = currentThread
        failIfObstructionFreedomIsRequired {
            // TODO: This might be a false positive when this `wait()` call never suspends.
            // TODO: We can keep it as is until refactoring, as this weird case is an anti-pattern anyway.
            OBSTRUCTION_FREEDOM_WAIT_VIOLATION_MESSAGE
        }
        if (withTimeout) return // timeouts occur instantly
        while (monitorTracker.waitOnMonitor(iThread, monitor)) {
            val mustSwitch = monitorTracker.isWaiting(iThread)
            switchCurrentThread(iThread, SwitchReason.MONITOR_WAIT, mustSwitch)
        }
    }

    override fun notify(monitor: Any, codeLocation: Int, notifyAll: Boolean): Unit = runInIgnoredSection {
        if (notifyAll) {
            monitorTracker.notifyAll(monitor)
        } else {
            monitorTracker.notify(monitor)
        }
        if (collectTrace) {
            val iThread = currentThread
            val tracePoint = NotifyTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread],
                callStackTrace = callStackTrace[iThread],
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            )
            traceCollector?.passCodeLocation(tracePoint)
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
        // Optimization: do not track accesses to thread-local objects
        if (!isStatic && localObjectManager.isLocalObject(obj)) {
            return@runInIgnoredSection false
        }
        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            ReadTracePoint(
                ownerRepresentation = if (isStatic) simpleClassName(className) else findOwnerName(obj!!),
                iThread = iThread,
                actorId = currentActorId[iThread],
                callStackTrace = callStackTrace[iThread],
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
        return@runInIgnoredSection true
    }

    /** Returns <code>true</code> if a switch point is created. */
    override fun beforeReadArrayElement(array: Any, index: Int, codeLocation: Int): Boolean = runInIgnoredSection {
        if (localObjectManager.isLocalObject(array)) return@runInIgnoredSection false
        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            ReadTracePoint(
                ownerRepresentation = null,
                iThread = iThread,
                actorId = currentActorId[iThread],
                callStackTrace = callStackTrace[iThread],
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
        loopDetector.passValue(array)
        loopDetector.passValue(index)
        true
    }

    override fun afterRead(value: Any?) {
        if (collectTrace) {
            runInIgnoredSection {
                val iThread = currentThread
                lastReadTracePoint[iThread]?.initializeReadValue(adornedStringRepresentation(value))
                lastReadTracePoint[iThread] = null
            }
        }
        runInIgnoredSection {
            loopDetector.passValue(value)
        }
    }

    override fun beforeWriteField(obj: Any?, className: String, fieldName: String, value: Any?, codeLocation: Int,
                                  isStatic: Boolean, isFinal: Boolean): Boolean = runInIgnoredSection {
        if (isStatic) {
            localObjectManager.markObjectNonLocal(value)
        } else if (obj != null) {
            localObjectManager.onWriteToObjectFieldOrArrayCell(obj, value)
            if (localObjectManager.isLocalObject(obj)) {
                return@runInIgnoredSection false
            }
        }
        // Optimization: do not track final field writes
        if (isFinal) {
            return@runInIgnoredSection false
        }
        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            WriteTracePoint(
                ownerRepresentation = if (isStatic) simpleClassName(className) else findOwnerName(obj!!),
                iThread = iThread,
                actorId = currentActorId[iThread],
                callStackTrace = callStackTrace[iThread],
                fieldName = fieldName,
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            ).also {
                it.initializeWrittenValue(adornedStringRepresentation(value))
            }
        } else {
            null
        }
        newSwitchPoint(iThread, codeLocation, tracePoint)
        return@runInIgnoredSection true
    }

    override fun beforeWriteArrayElement(array: Any, index: Int, value: Any?, codeLocation: Int): Boolean = runInIgnoredSection {
        localObjectManager.onWriteToObjectFieldOrArrayCell(array, value)
        if (localObjectManager.isLocalObject(array)) {
            return@runInIgnoredSection false
        }
        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            WriteTracePoint(
                ownerRepresentation = null,
                iThread = iThread,
                actorId = currentActorId[iThread],
                callStackTrace = callStackTrace[iThread],
                fieldName = "${adornedStringRepresentation(array)}[$index]",
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            ).also {
                it.initializeWrittenValue(adornedStringRepresentation(value))
            }
        } else {
            null
        }
        newSwitchPoint(iThread, codeLocation, tracePoint)
        loopDetector.passValue(array)
        loopDetector.passValue(index)
        loopDetector.passValue(value)
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
        if (receiver == null) {
            localObjectManager.markObjectNonLocal(value)
        } else {
            localObjectManager.onWriteToObjectFieldOrArrayCell(receiver, value)
        }
    }

    override fun getThreadLocalRandom(): Random = runInIgnoredSection {
        return randoms[currentThread]
    }

    override fun randomNextInt(): Int = runInIgnoredSection {
        getThreadLocalRandom().nextInt()
    }

    private fun enterIgnoredSection() {
        val thread = (Thread.currentThread() as? TestThread) ?: return
        thread.inIgnoredSection = true
    }

    private fun leaveIgnoredSectionIfEntered() {
        val thread = (Thread.currentThread() as? TestThread) ?: return
        if (thread.inIgnoredSection) {
            thread.inIgnoredSection = false
        }
    }

    override fun beforeNewObjectCreation(className: String) = runInIgnoredSection {
        LincheckJavaAgent.ensureClassHierarchyIsTransformed(className)
    }

    override fun afterNewObjectCreation(obj: Any) {
        if (obj is String || obj is Int || obj is Long || obj is Byte || obj is Char || obj is Float || obj is Double) return
        runInIgnoredSection {
            localObjectManager.registerNewObject(obj)
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
        val guarantee = methodGuaranteeType(owner, className, methodName)
        when (guarantee) {
            ManagedGuaranteeType.IGNORE -> {
                if (collectTrace) {
                    runInIgnoredSection {
                        val params = if (isSuspendFunction(className, methodName, params)) {
                            params.dropLast(1).toTypedArray()
                        } else {
                            params
                        }
                        beforeMethodCall(owner, currentThread, codeLocation, 0, className, methodName, params)
                    }
                }
                // It's important that this method can't be called inside runInIgnoredSection, as the ignored section
                // flag would be set to false when leaving runInIgnoredSection,
                // so enterIgnoredSection would have no effect
                enterIgnoredSection()
            }

            ManagedGuaranteeType.TREAT_AS_ATOMIC -> {
                runInIgnoredSection {
                    if (collectTrace) {
                        beforeMethodCall(owner, currentThread, codeLocation, 0, className, methodName, params)
                    }
                    newSwitchPointOnAtomicMethodCall(codeLocation, params)
                }
                // It's important that this method can't be called inside runInIgnoredSection, as the ignored section
                // flag would be set to false when leaving runInIgnoredSection,
                // so enterIgnoredSection would have no effect
                enterIgnoredSection()
            }

            null -> {
                if (owner == null) { // static method
                    runInIgnoredSection {
                        LincheckJavaAgent.ensureClassHierarchyIsTransformed(className.canonicalClassName)
                    }
                }
                runInIgnoredSection {
                    loopDetector.beforeRegularMethodCall(codeLocation, params)
                }
                if (collectTrace) {
                    runInIgnoredSection {
                        traceCollector!!.checkActiveLockDetected()
                        val params = if (isSuspendFunction(className, methodName, params)) {
                            params.dropLast(1).toTypedArray()
                        } else {
                            params
                        }
                        beforeMethodCall(owner, currentThread, codeLocation, methodId, className, methodName, params)
                    }
                }
            }
        }
    }

    override fun beforeAtomicMethodCall(
        owner: Any?,
        className: String,
        methodName: String,
        codeLocation: Int,
        params: Array<Any?>
    ) = runInIgnoredSection {
        if (collectTrace) {
            beforeMethodCall(owner, currentThread, codeLocation, 0, className, methodName, params)
        }
        newSwitchPointOnAtomicMethodCall(codeLocation, params)
    }

    override fun onMethodCallReturn(result: Any?) {
        runInIgnoredSection {
            loopDetector.afterRegularMethodCall()
        }
        if (collectTrace) {
            runInIgnoredSection {
                val iThread = currentThread
                val tracePoint = methodCallTracePointStack[iThread].removeLast()
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
        leaveIgnoredSectionIfEntered()
    }

    override fun onMethodCallException(t: Throwable) {
        runInIgnoredSection {
            loopDetector.afterRegularMethodCall()
        }
        if (collectTrace) {
            runInIgnoredSection {
                // We cannot simply read `thread` as Forcible???Exception can be thrown.
                val iThread = (Thread.currentThread() as TestThread).threadId
                val tracePoint = methodCallTracePointStack[iThread].removeLast()
                tracePoint.initializeThrownException(t)
                afterMethodCall(iThread, tracePoint)
                traceCollector!!.addStateRepresentation()
            }
        }
        // In case the code is now in an "ignore" section due to
        // an "atomic" or "ignore" guarantee, we need to leave
        // this "ignore" section.
        leaveIgnoredSectionIfEntered()
    }

    private fun newSwitchPointOnAtomicMethodCall(codeLocation: Int, params: Array<Any?>) {
        // re-use last call trace point
        newSwitchPoint(currentThread, codeLocation, callStackTrace[currentThread].lastOrNull()?.call)
        loopDetector.passParameters(params)
    }

    private fun isSuspendFunction(className: String, methodName: String, params: Array<Any?>) =
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
        check(currentThread == iThread)
        isSuspended[iThread] = true
        if (runner.isCoroutineResumed(iThread, currentActorId[iThread])) {
            // `COROUTINE_SUSPENSION_CODE_LOCATION`, because we do not know the actual code location
            newSwitchPoint(iThread, COROUTINE_SUSPENSION_CODE_LOCATION, null)
        } else {
            // coroutine suspension does not violate obstruction-freedom
            switchCurrentThread(iThread, SwitchReason.SUSPENDED, true)
        }
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was resumed.
     */
    internal fun afterCoroutineResumed() {
        isSuspended[currentThread] = false
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was cancelled.
     */
    internal fun afterCoroutineCancelled() {
        val iThread = currentThread
        isSuspended[iThread] = false
        // method will not be resumed after suspension, so clear prepared for resume call stack
        suspendedFunctionsStack[iThread].clear()
    }

    /**
     * This method is invoked by a test thread
     * before each method invocation.
     * @param codeLocation the byte-code location identifier of this invocation
     * @param iThread number of invoking thread
     */
    private fun beforeMethodCall(
        owner: Any?,
        iThread: Int,
        codeLocation: Int,
        identifier: Int,
        className: String,
        methodName: String,
        params: Array<Any?>,
    ) {
        val callStackTrace = callStackTrace[iThread]
        val suspendedMethodStack = suspendedFunctionsStack[iThread]
        val suspensionIdentifier = if (suspendedMethodStack.isNotEmpty()) {
            // If there was a suspension before, then instead of creating a new identifier,
            // use the one that the suspended call had
            val lastId = suspendedMethodStack.last()
            suspendedMethodStack.removeAt(suspendedMethodStack.lastIndex)
            lastId
        } else {
            methodCallNumber++
        }
        // Code location of the new method call is currently the last one
        val tracePoint = createBeforeMethodCallTracePoint(owner, iThread, className, methodName, params, codeLocation)
        methodCallTracePointStack[iThread] += tracePoint
        // Method id used to calculate spin cycle start label call depth.
        // Two calls are considered equals if two same methods were called with the same parameters.
        val methodId = Objects.hash(identifier,
            params.map { primitiveHashCodeOrSystemHashCode(it) }.toTypedArray().contentHashCode()
        )
        callStackTrace.add(CallStackTraceElement(tracePoint, suspensionIdentifier, methodId))
        if (owner == null) {
            beforeStaticMethodCall()
        } else {
            beforeInstanceMethodCall(owner)
        }
    }

    private fun createBeforeMethodCallTracePoint(
        owner: Any?,
        iThread: Int,
        className: String,
        methodName: String,
        params: Array<Any?>,
        codeLocation: Int
    ): MethodCallTracePoint {
        val callStackTrace = callStackTrace[iThread]
        val tracePoint = MethodCallTracePoint(
            iThread = iThread,
            actorId = currentActorId[iThread],
            callStackTrace = callStackTrace,
            methodName = methodName,
            stackTraceElement = CodeLocations.stackTrace(codeLocation)
        )
        if (owner is VarHandle) {
            return initializeVarHandleMethodCallTracePoint(tracePoint, owner, params)
        }
        if (owner is AtomicIntegerFieldUpdater<*> || owner is AtomicLongFieldUpdater<*> || owner is AtomicReferenceFieldUpdater<*, *>) {
            return initializeAtomicUpdaterMethodCallTracePoint(tracePoint, owner, params)
        }
        if (isAtomicReference(owner)) {
            return initializeAtomicReferenceMethodCallTracePoint(tracePoint, owner!!, params)
        }
        if (isUnsafe(owner)) {
            return initializeUnsafeMethodCallTracePoint(tracePoint, owner!!, params)
        }

        tracePoint.initializeParameters(params.map { adornedStringRepresentation(it) })

        val ownerName = if (owner != null) findOwnerName(owner) else simpleClassName(className)
        if (ownerName != null) {
            tracePoint.initializeOwnerName(ownerName)
        }

        return tracePoint
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
            AtomicReferenceMethodType.TreatAsDefaultMethod -> {
                tracePoint.initializeOwnerName(adornedStringRepresentation(receiver))
                tracePoint.initializeParameters(params.map { adornedStringRepresentation(it) })
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
        }
        return tracePoint
    }

    private fun initializeVarHandleMethodCallTracePoint(
        tracePoint: MethodCallTracePoint,
        varHandle: VarHandle,
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

    private fun isAtomicReference(receiver: Any?) = receiver is AtomicReference<*> ||
            receiver is AtomicLong ||
            receiver is AtomicInteger ||
            receiver is AtomicBoolean ||
            receiver is AtomicIntegerArray ||
            receiver is AtomicReferenceArray<*> ||
            receiver is AtomicLongArray

    private fun isUnsafe(receiver: Any?): Boolean {
        if (receiver == null) return false
        val className = receiver::class.java.name
        return className == "sun.misc.Unsafe" || className == "jdk.internal.misc.Unsafe"
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
        return when (val callContext = callStackContextPerThread[currentThread].last()) {
            is CallContext.InstanceCallContext -> callContext.instance === owner
            is CallContext.StaticCallContext -> false
        }
    }

    /* Methods to control the current call context. */

    private fun beforeStaticMethodCall() {
        val element = CallContext.StaticCallContext
        callStackContextPerThread[currentThread].add(element)
    }

    private fun beforeInstanceMethodCall(receiver: Any) {
        val element = CallContext.InstanceCallContext(receiver)
        callStackContextPerThread[currentThread].add(element)
    }

    private fun afterExitMethod(iThread: Int) {
        val currentContext = callStackContextPerThread[iThread]
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
        val callStackTrace = callStackTrace[iThread]
        if (tracePoint.wasSuspended) {
            // if a method call is suspended, save its identifier to reuse for continuation resuming
            suspendedFunctionsStack[iThread].add(callStackTrace.last().suspensionIdentifier)
        }
        callStackTrace.removeAt(callStackTrace.lastIndex)
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
        val iThread = currentThread
        val actorId = currentActorId.getOrElse(iThread) { Int.MIN_VALUE }
        return constructor(iThread, actorId, callStackTrace.getOrNull(iThread)?.toList() ?: emptyList())
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
        val lastMethodCall: MethodCallTracePoint = callStackTrace[currentThread].lastOrNull()?.call ?: return
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
                afterSpinCycleTraceCollected(iThread, beforeMethodCallSwitch)
            }
            _trace += SwitchEventTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread],
                reason = reason,
                callStackTrace = callStackTrace[iThread]
            )
            spinCycleStartAdded = false
        }

        /**
         * Calculates the [SpinCycleStartTracePoint] trace point callStackTrace correctly after all trace points
         * related to the spin cycle are collected.
         *
         * # The problem overview
         *
         * [TraceCollector] collects two types of the [TracePoint]:
         * 1. TracePoints that represents places where potential context switch points could happened.
         * 2. TracePoints, representing regular, non-atomic method calls.
         *
         * This division is important for us, as [LoopDetector] stores execution points sequences of the
         * first type, causing the challenges, described below.
         *
         * When [SpinCycleStartTracePoint] is added initially to the [trace], its callStackTrace, constructed via
         * `callStackTrace[currentThread]` may be inaccurate. Consider the following example:
         * We have the following code:
         * ```kotlin
         * @Operation
         * fun actor() {
         *     atomicInteger.set(false) // code location = 1
         *     spinLock() // method call code location = 2
         * }
         *
         * fun spinLock() {
         *     while (true) {
         *         val value = getValue() // method call code location = 3
         *         atomicInteger.compareAndSet(value, !value) // code location = 5
         *     }
         * }
         *
         * fun getValue() = atomicInteger.get() // code location = 4
         * ```
         * When we ran into a cycle, we'll have the following code locations history:
         * ```
         * [1, 2, 3, 4, 5, 3, 4, 5, 3, 4 ,5 ..., 3, 4, 5]
         * ```
         * Cycle period here is 3: `[3, 4, 5]` and only two `[1, 2]` executions happened before the cycle.
         * Then, suppose we have a scenario with two actors.
         * The interleaving we have:
         * ```
         * [
         *  1. Execute 2 instructions (switch positions) in thread 1,
         *  2. Execute 1 instruction (switch positions) in the thread 1,
         *  3. Execute 3 instructions (switch positions) in the thread 1 -> execution hung.
         * ]
         * ```
         * It's worth noting that the LoopDetector operates with a switch positions when replaying the
         * execution to collect trace.
         *
         * Due to the internals of the [LoopDetector], [LoopDetector.replayModeCurrentlyInSpinCycle] will only
         * return `true` at the beginning of step 3 described above, as execution only contains a sequence of
         * executed potential switch points, while method call isn't a potential switch point.
         * But at the beginning of step 3 we'll be inside the `getValue` method, so when [LoopDetector.replayModeCurrentlyInSpinCycle]
         * is triggered, it's not correct using `callStackTrace[currentThread]`, as it would contain `getValue`.
         *
         * Summarizing, we may receive the signal about spin cycle start some non-atomic method calls after the actual spin cycle start
         * if we switch right before the first spin cycle potential switch point execution,
         * and this happens as [LoopDetector] stores sequences of the only potential switch points executions and
         * all enters to the non-atomic methods are considered as a part of a step 1, which doesn't correspond to a spin cycle.
         *
         * Hence, **we can only use trace points of the first type defined above (which are potential switch points)
         * to calculate the correct spin cycle start label call depth**.
         *
         * The problem has two solutions, depending on the spin cycle type:
         * * recursive
         * * iterative.
         *
         * # Iterative spin lock
         * Let's consider the solution for the iterative spin-cycle.
         *
         * When we report an iterative spin cycle, we **must** visit the first spin cycle execution point
         * and the first execution point of the second iteration (with an exit before).
         * Let's track the current stacktrace before each switch point, before and after each method calls.
         * Consider the example of a spin cycle, representing call stack traces of the execution points:
         * ```
         * A -> B -> C
         * A -> B -> C -> K
         * A -> B -> E -> D
         * A -> B -> E
         * A -> B -> X
         * ```
         * As we can see, even if all the execution points are wrapped in the non-atomic method calls (which we track
         * during the trace collection too), the longest common prefix of the method calls `A, B` in the stack traces
         * is the right position to place spin cycle start label.
         * Indeed, due to the iterative nature of the spin lock, the first spin cycle execution of the first iteration
         * and of the second iteration are placed in the same depth of calls. So it's guaranteed that we'll visit
         * the most nested call and the least nested call. The wanted recursive method (and its call depth) can be found
         * using the longest prefix of all the points is the spin cycle, as it is going to be the same along all the
         * trace points and method calls.
         *
         * So that's what we do in case of an iterative spin lock.
         * Track all the trace points of the spin cycle,
         * calculate the max common method call prefix of these trace points, and place the spin cycle start label
         * onto the found call depth.
         *
         * # Recursive spin lock
         *
         * Unlike iterative spin lock, in case of a recursive spin lock, longest common prefix may include
         * method calls, that are a part of a spin cycle. Consider the following example:
         * ```kotlin
         * fun actor() {
         *     a()
         * }
         *
         * fun a() = b()
         *
         * fun b() {
         *     c()
         *     c()
         *     a() // recursive call
         * }
         *
         * fun c() = value.get()
         * ```
         * According to the problem described above, if we switch to another thread and back right before point X,
         * the trace points of the spin cycle will have the following stack traces:
         * ```
         * [
         *     actor -> a -> b -> c
         *     actor -> a -> b -> c
         * ]
         * ```
         * And the first execution point of the second iteration will be
         * ```
         * actor -> a -> b -> a -> b -> c
         * ```
         *
         * As mentioned, it's incorrect to say that spin cycle starts at the depth `actor -> a -> c`, as the first
         * recursive call is method `b` call, so we can't use the approach for the iterative spin cycle type.
         *
         * Let's consider the last potential switch trace point before the first spin cycle trace point,
         * the first iteration first potential switch trace point
         * and the second iteration first potential switch trace point:
         *
         * 1. `actor -> a -> b -> c`
         * 2. `actor -> a -> b -> c -> a -> b -> c`
         *
         * Due to the recursive nature of a spin cycle, the second and the third trace points must have common
         * method call suffix.
         * It's important how to compare method calls in the suffixes. We consider method calls identical if
         * they have the same ids produced by [MethodIds] and the same parameters. That means we don't care
         * here where this method is called - it's more convenient to see as short version of the spin cycle as possible.
         *
         * But this approach may produce a wrong result in case when we switch from the spin lock and then occur
         * in the same spin lock again.
         * Let's consider this situation using the example above.
         * Trace points of the second spin lock would have the following call stacks:
         *
         * 1. `actor -> [a -> b -> c] -> [a -> b -> c]`
         * 2. `actor -> [a -> b -> c] -> [a -> b -> c]`
         *
         * And the first execution point of the second iteration will be
         * ```
         *     actor -> [a -> b -> c] -> [a -> b -> c] -> [a -> b -> c]
         * ```
         *
         * If we apply this algorithm to the first point from the first iteration and the first point from the second iteration
         * we get `[a -> b -> c] -> [a -> b -> c]` longest common suffix, which is not correct, because the first recursive call
         * of this cycle starts at the depth `actor -> a -> b`.
         * In this case, the last trace point before the spin cycle becomes handy.
         * In the example above it would have the following call stack `actor -> a -> b -> c`.
         *
         * Here are the points we're interested in:
         * 1. `actor -> [a -> b -> c]` - The last trace point before the cycle.
         * 2. `actor -> [a -> b -> c] -> [a -> b -> c]` - The first trace point of the cycle.
         * 3. ` actor -> [a -> b -> c] -> [a -> b -> c] -> [a -> b -> c]` - The first trace point of the second iteration.
         * Let's note the following rule: if point 1 has the same method call on the same depth as point 2,
         * then this call can't be a part of the current spin cycle.
         * The reason is quite easy: if it was, we would mark point 1 as a first spin cycle trace point.
         *
         * So, to detect the spin cycle, start trace point depth in case of a recursive spin lock we:
         * 1. Take the first trace point of the spin cycle on the first iteration (point A) and on the second iteration (point B).
         * 2. Take the last trace point before the spin cycle (point C).
         * 3. We walk synchronously on their call stacks from the end to the beginning, while call from the A stack
         * is the same as a call from the stack B. But it also equals the call from the call C, then we need to stop.
         * The count of the call passed the condition above equals to the call we need to lift from the first spin cycle
         * node to get the correct spin cycle start trace point depth.
         *
         * @param beforeMethodCallSwitch flag if this method invoked right after [MethodCallTracePoint] is added to a trace,
         * before the corresponding method is called.
         */
        private fun afterSpinCycleTraceCollected(
            iThread: Int,
            beforeMethodCallSwitch: Boolean
        ) {
            // Obtaining spin cycle trace points.
            val spinLockTracePoints = trace.takeLastWhile { it.iThread == iThread && it !is SpinCycleStartTracePoint }
            // Nothing to do in this case (seems unreal).
            if (spinLockTracePoints.isEmpty()) return
            // Get the call stack of first trace point of the second spin cycle iteration
            var currentCallStackTrace: List<CallStackTraceElement> = callStackTrace[iThread]
            // If this method is invoked after beforeMethodCall or beforeAtomicMethodCall
            // than MethodCallTracePoint is already added, correct it by altering current stack trace
            if (beforeMethodCallSwitch) {
                currentCallStackTrace = currentCallStackTrace.dropLast(1)
            }

            val cycleStartTracePointIndex = _trace.size - spinLockTracePoints.size - 1
            if (cycleStartTracePointIndex < 0 || _trace[cycleStartTracePointIndex] !is SpinCycleStartTracePoint) return

            val spinCycleFirstTracePointCallStackTrace = spinLockTracePoints.first().callStackTrace
            val isRecursive = currentCallStackTrace.size != spinCycleFirstTracePointCallStackTrace.size
            val spinCycleStartStackTrace = if (isRecursive) {
                // See above the description of the algorithm for recursive spin lock.
                val prevIndex = _trace.lastIndex - spinLockTracePoints.size - 1
                val tracePointBeforeCycle = if (prevIndex >= 0) (prevIndex downTo 0)
                    .firstOrNull { _trace[it] !is SwitchEventTracePoint && _trace[it].iThread == iThread }?.let { _trace[it] }
                    else null

                var currentI = currentCallStackTrace.lastIndex
                var firstI = spinCycleFirstTracePointCallStackTrace.lastIndex
                var count = 0
                while (firstI >= 0) {
                    val identifier = spinCycleFirstTracePointCallStackTrace[firstI].methodId
                    // Comparing corresponding calls.
                    if (identifier != currentCallStackTrace[currentI].methodId) break
                    // Check for the last trace point before the cycle.
                    if ((tracePointBeforeCycle != null) &&
                        (tracePointBeforeCycle.callStackTrace.lastIndex >= firstI) &&
                        (tracePointBeforeCycle.callStackTrace[firstI].methodId == identifier)
                    ) break

                    currentI--
                    firstI--
                    count++
                }
                spinCycleFirstTracePointCallStackTrace.dropLast(count)
            } else {
               // See above the description of the algorithm for iterative spin lock.
               getCommonMinStackTrace(spinLockTracePoints)
                    .dropLast(currentCallStackTrace.size - spinCycleFirstTracePointCallStackTrace.size)
            }
            _trace[cycleStartTracePointIndex] =
                SpinCycleStartTracePoint(iThread, currentActorId[iThread], spinCycleStartStackTrace)
        }

        /**
         * @return Max common prefix of the [StackTraceElement] of the provided [spinCycleTracePoints]
         */
        private fun getCommonMinStackTrace(spinCycleTracePoints: List<TracePoint>): List<CallStackTraceElement> {
            val callStackTraces = spinCycleTracePoints.map { it.callStackTrace } + spinCycleMethodCallsStackTraces
            var count = 0
            outer@while (true) {
                if (count == callStackTraces[0].size) break
                val stackTraceElement = callStackTraces[0][count].call.stackTraceElement
                for (i in 1 until callStackTraces.size) {
                    val traceElements = callStackTraces[i]
                    if (count == traceElements.size) break@outer
                    if (stackTraceElement != traceElements[count].call.stackTraceElement) break@outer
                }
                count++
            }
            return spinCycleTracePoints.first().callStackTrace.take(count)
        }

        fun onThreadFinish() {
            spinCycleStartAdded = false
        }

        fun checkActiveLockDetected() {
            if (!loopDetector.replayModeCurrentlyInSpinCycle) return
            if (spinCycleStartAdded) {
                spinCycleMethodCallsStackTraces += callStackTrace[currentThread].toList()
                return
            }
            val spinCycleStartTracePoint = SpinCycleStartTracePoint(
                iThread = currentThread,
                actorId = currentActorId[currentThread],
                callStackTrace = callStackTrace[currentThread]
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
            val stateRepresentation = runner.constructStateRepresentation() ?: return
            // use call stack trace of the previous trace point
            val callStackTrace = callStackTrace[currentThread]
            _trace += StateRepresentationTracePoint(
                iThread = currentThread,
                actorId = currentActorId[currentThread],
                stateRepresentation = stateRepresentation,
                callStackTrace = callStackTrace
            )

        }

        fun passObstructionFreedomViolationTracePoint(iThread: Int, beforeMethodCall: Boolean) {
            afterSpinCycleTraceCollected(iThread, beforeMethodCall)
            _trace += ObstructionFreedomViolationExecutionAbortTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread],
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
         * Also, if [eventIdStrictOrderingCheck] is enabled, checks that
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
    override fun onStart(iThread: Int) = runInIgnoredSection {
        if (currentExecutionPart !== PARALLEL) return
        managedStrategy.onStart(iThread)
    }

    override fun onFinish(iThread: Int) = runInIgnoredSection {
        if (currentExecutionPart !== PARALLEL) return
        managedStrategy.onFinish(iThread)
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


/**
 * Tracks synchronization operations with monitors (acquire/release, wait/notify) to maintain a set of active threads.
 */
private class MonitorTracker(nThreads: Int) {
    // Maintains a set of acquired monitors with an information on which thread
    // performed the acquisition and the reentrancy depth.
    private val acquiredMonitors = IdentityHashMap<Any, MonitorAcquiringInfo>()

    // Maintains a set of monitors on which each thread is waiting.
    // Note, that a thread can wait on a free monitor if it is waiting for a `notify` call.
    // Stores `null` if thread is not waiting on any monitor.
    private val waitingMonitor = Array<MonitorAcquiringInfo?>(nThreads) { null }

    // Stores `true` for the threads which are waiting for a
    // `notify` call on the monitor stored in `acquiringMonitor`.
    private val waitForNotify = BooleanArray(nThreads) { false }

    /**
     * Performs a logical acquisition.
     */
    fun acquireMonitor(iThread: Int, monitor: Any): Boolean {
        // Increment the reentrant depth and store the
        // acquisition info if needed.
        val info = acquiredMonitors.computeIfAbsent(monitor) {
            MonitorAcquiringInfo(monitor, iThread, 0)
        }
        if (info.iThread != iThread) {
            waitingMonitor[iThread] = MonitorAcquiringInfo(monitor, iThread, 0)
            return false
        }
        info.timesAcquired++
        waitingMonitor[iThread] = null
        return true
    }

    /**
     * Performs a logical release.
     */
    fun releaseMonitor(monitor: Any) {
        // Decrement the reentrancy depth and remove the acquisition info
        // if the monitor becomes free to acquire by another thread.
        val info = acquiredMonitors[monitor]!!
        info.timesAcquired--
        if (info.timesAcquired == 0)
            acquiredMonitors.remove(monitor)
    }

    /**
     * Returns `true` if the corresponding threads is waiting on some monitor.
     */
    fun isWaiting(iThread: Int): Boolean {
        val monitor = waitingMonitor[iThread]?.monitor ?: return false
        return waitForNotify[iThread] || !canAcquireMonitor(iThread, monitor)
    }

    /**
     * Returns `true` if the monitor is already acquired by
     * the thread [iThread], or if this monitor is free to acquire.
     */
    private fun canAcquireMonitor(iThread: Int, monitor: Any) =
        acquiredMonitors[monitor]?.iThread?.equals(iThread) ?: true

    /**
     * Performs a logical wait, [isWaiting] for the specified thread
     * returns `true` until the corresponding [notify] or [notifyAll] is invoked.
     */
    fun waitOnMonitor(iThread: Int, monitor: Any): Boolean {
        // TODO: we can add spurious wakeups here
        var info = acquiredMonitors[monitor]
        if (info != null) {
            // in case when lock is currently acquired by another thread continue waiting
            if (info.iThread != iThread)
                return true
            // in case when current thread owns the lock we release it
            // in order to give other thread a chance to acquire it
            // and put the current thread into waiting state
            waitForNotify[iThread] = true
            waitingMonitor[iThread] = info
            acquiredMonitors.remove(monitor)
            return true
        }
        // otherwise the lock is held by no-one and can be acquired
        info = waitingMonitor[iThread]
        check(info != null && info.monitor === monitor && info.iThread == iThread) {
            "Monitor should have been acquired by this thread"
        }
        // if there has been no `notify` yet continue waiting
        if (waitForNotify[iThread])
            return true
        // otherwise acquire monitor restoring its re-entrance depth
        acquiredMonitors[monitor] = info
        waitingMonitor[iThread] = null
        return false
    }

    /**
     * Just notify all thread. Odd threads will have a spurious wakeup
     */
    fun notify(monitor: Any) = notifyAll(monitor)

    /**
     * Performs the logical `notifyAll`.
     */
    fun notifyAll(monitor: Any): Unit = waitingMonitor.forEachIndexed { iThread, info ->
        if (monitor === info?.monitor)
            waitForNotify[iThread] = false
    }

    /**
     * Stores the [monitor], id of the thread acquired the monitor [iThread],
     * and the number of reentrant acquisitions [timesAcquired].
     */
    private class MonitorAcquiringInfo(val monitor: Any, val iThread: Int, var timesAcquired: Int)
}

/**
 * This exception is used to finish the execution correctly for managed strategies.
 * Otherwise, there is no way to do it in case of (e.g.) deadlocks.
 * If we just leave it, then the execution will not be halted.
 * If we forcibly pass through all barriers, then we can get another exception due to being in an incorrect state.
 */
internal object ForcibleExecutionFinishError : Error() {
    // do not create a stack trace -- it simply can be unsafe
    override fun fillInStackTrace() = this
}

internal const val COROUTINE_SUSPENSION_CODE_LOCATION = -1 // currently the exact place of coroutine suspension is not known

private const val OBSTRUCTION_FREEDOM_SPINLOCK_VIOLATION_MESSAGE =
    "The algorithm should be non-blocking, but an active lock is detected"

private const val OBSTRUCTION_FREEDOM_LOCK_VIOLATION_MESSAGE =
    "The algorithm should be non-blocking, but a lock is detected"

private const val OBSTRUCTION_FREEDOM_WAIT_VIOLATION_MESSAGE =
    "The algorithm should be non-blocking, but a wait call is detected"

/**
 * With idea plugin enabled, we should not use default Lincheck timeout
 * as debugging may take more time than default timeout.
 */
private const val INFINITE_TIMEOUT = 1000L * 60 * 60 * 24 * 365

private fun getTimeOutMs(strategy: ManagedStrategy, defaultTimeOutMs: Long): Long =
    if (strategy is ModelCheckingStrategy && strategy.replay) INFINITE_TIMEOUT else defaultTimeOutMs
