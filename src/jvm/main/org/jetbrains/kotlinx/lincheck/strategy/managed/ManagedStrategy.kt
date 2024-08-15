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
import java.lang.invoke.VarHandle
import java.lang.reflect.*
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
    private val testCfg: ManagedCTestConfiguration,
) : Strategy(scenario), EventTracker {
    // The number of parallel threads.
    protected val nThreads: Int = scenario.nThreads

    // Runner for scenario invocations,
    // can be replaced with a new one for trace construction.
    override var runner = createRunner()

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

    // Stores the global number of method calls.
    private var methodCallNumber = 0

    // Stores the currently executing methods call stack for each thread.
    private val callStackTrace = Array(nThreads) { mutableListOf<CallStackTraceElement>() }

    // In case of suspension, the call stack of the corresponding `suspend`
    // methods is stored here, so that the same method call identifiers are
    // used on resumption, and the trace point before and after the suspension
    // correspond to the same method call in the trace.
    // NOTE: the call stack is stored in the reverse order,
    // i.e. the first element is the top stack trace element.
    // TODO: store CallStackTraceElement instead
    private val suspendedFunctionsStack = Array(nThreads) { mutableListOf<Int>() }

    // Current call stack for a thread, updated during beforeMethodCall and afterMethodCall methods.
    private val methodCallTracePointStack = (0 until nThreads + 2).map { mutableListOf<MethodCallTracePoint>() }

    // Helps to ignore potential switch point in local objects (see LocalObjectManager) to avoid
    // useless interleavings analysis.
    private var localObjectManager = LocalObjectManager()

    // Last read trace point, occurred in the current thread.
    // We store it as we initialize read value after the point is created so we have to store
    // the trace point somewhere to obtain it later.
    private var lastReadTracePoint = Array<ReadTracePoint?>(nThreads) { null }

    // Random instances with fixed seeds to replace random calls in instrumented code.
    private var randoms = (0 until nThreads + 2).map { Random(it + 239L) }

    // User-specified guarantees on specific function, which can be considered as atomic or ignored.
    private val userDefinedGuarantees: List<ManagedStrategyGuarantee>? = testCfg.guarantees.ifEmpty { null }

    // Utility class for the plugin integration to provide ids for each trace point
    private var eventIdProvider = EventIdProvider()

    /**
     * Current method call context (static or instance).
     * Initialized and used only in the trace collecting stage.
     */
    private lateinit var callStackContextPerThread: Array<ArrayList<CallContext>>

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
            // Unexpected `ForcibleExecutionFinishError` should be thrown.
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

    override fun beforePart(part: ExecutionPart) {
        traceCollector?.passCodeLocation(SectionDelimiterTracePoint(part))
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

    fun initializeCallStack(testInstance: Any) {
        if (collectTrace) {
            callStackContextPerThread = Array(nThreads) { arrayListOf(CallContext.InstanceCallContext(testInstance)) }
        }
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
        if (decision.isLivelockDetected) {
            failIfObstructionFreedomIsRequired {
                if (decision is LoopDetector.Decision.LivelockFailureDetected) {
                    // if failure is detected, add a special obstruction-freedom violation
                    // trace point to account for that
                    traceCollector?.passObstructionFreedomViolationTracePoint(currentThread, beforeMethodCall = tracePoint is MethodCallTracePoint)
                } else {
                    // otherwise log the last event that caused obstruction-freedom violation
                    traceCollector?.passCodeLocation(tracePoint)
                }
                OBSTRUCTION_FREEDOM_SPINLOCK_VIOLATION_MESSAGE
            }
        }
        // if live-lock failure was detected, then fail immediately
        if (decision is LoopDetector.Decision.LivelockFailureDetected) {
            traceCollector?.newSwitch(currentThread, SwitchReason.ACTIVE_LOCK, beforeMethodCallSwitch = tracePoint is MethodCallTracePoint)
            failDueToDeadlock()
        }
        // if live-lock was detected, and replay was requested,
        // then abort current execution and start the replay
        if (decision.isReplayRequired) {
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
     *
     * @return was this thread actually switched to another or not
     */
    private fun switchCurrentThread(
        iThread: Int,
        reason: SwitchReason = SwitchReason.STRATEGY_SWITCH,
        mustSwitch: Boolean = false,
        tracePoint: TracePoint? = null
    ): Boolean {
        traceCollector?.newSwitch(iThread, reason, beforeMethodCallSwitch = tracePoint != null && tracePoint is MethodCallTracePoint)
        doSwitchCurrentThread(iThread, mustSwitch)
        val switchHappened = iThread != currentThread
        awaitTurn(iThread)
        return switchHappened
    }

    private fun doSwitchCurrentThread(iThread: Int, mustSwitch: Boolean = false) {
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
            setCurrentThread(nextThread)
            return
        }
        // otherwise exit if the thread switch is optional, or all threads are finished
        if (!mustSwitch || finished.all { it }) {
           return
        }
        // try to resume some suspended thread
        val suspendedThread = (0 until nThreads).firstOrNull {
           !finished[it] && isSuspended[it]
        }
        if (suspendedThread != null) {
           setCurrentThread(suspendedThread)
           return
        }
        // any other situation is considered to be a deadlock
        suddenInvocationResult = ManagedDeadlockInvocationResult(runner.collectExecutionResults())
        throw ForcibleExecutionFinishError
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
        loopDetector.beforeReadField(obj)
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
        loopDetector.beforeReadArrayElement(array, index)
        true
    }

    override fun afterRead(value: Any?) = runInIgnoredSection {
        if (collectTrace) {
                val iThread = currentThread
                lastReadTracePoint[iThread]?.initializeReadValue(adornedStringRepresentation(value))
                lastReadTracePoint[iThread] = null
        }
        loopDetector.afterRead(value)
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
        loopDetector.beforeWriteField(obj, value)
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
            loopDetector.afterMethodCall()
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

    private fun addBeforeMethodCallTracePoint(
        owner: Any?,
        codeLocation: Int,
        methodId: Int,
        className: String,
        methodName: String,
        methodParams: Array<Any?>,
        atomicMethodDescriptor: AtomicMethodDescriptor?,
    ) {
        val iThread = currentThread
        val callStackTrace = callStackTrace[iThread]
        val suspendedMethodStack = suspendedFunctionsStack[iThread]
        val suspensionId = if (suspendedMethodStack.isNotEmpty()) {
            // If there was a suspension before, then instead of creating a new identifier,
            // use the one that the suspended call had
            // TODO: do not remove it here, remove in `afterMethodCall` instead???
            suspendedMethodStack.removeLast()
        } else {
            methodCallNumber++
        }
        val params = if (isSuspendFunction(className, methodName, methodParams)) {
            methodParams.dropLast(1).toTypedArray()
        } else {
            methodParams
        }
        // Code location of the new method call is currently the last one
        val tracePoint = createBeforeMethodCallTracePoint(owner, iThread, className, methodName, params, codeLocation, atomicMethodDescriptor)
        methodCallTracePointStack[iThread] += tracePoint
        // Method id used to calculate spin cycle start label call depth.
        // Two calls are considered equals if two same methods were called with the same parameters.
        val methodIdentifierWithSignatureAndParams = Objects.hash(methodId,
            params.map { primitiveOrIdentityHashCode(it) }.toTypedArray().contentHashCode()
        )
        callStackTrace.add(CallStackTraceElement(tracePoint, suspensionId, methodIdentifierWithSignatureAndParams))
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
        codeLocation: Int,
        atomicMethodDescriptor: AtomicMethodDescriptor?,
    ): MethodCallTracePoint {
        val callStackTrace = callStackTrace[iThread]
        val tracePoint = MethodCallTracePoint(
            iThread = iThread,
            actorId = currentActorId[iThread],
            callStackTrace = callStackTrace,
            methodName = methodName,
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
            return initializeVarHandleMethodCallTracePoint(tracePoint, owner as VarHandle, params)
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
        callStackContextPerThread[currentThread].add(CallContext.StaticCallContext)
    }

    private fun beforeInstanceMethodCall(receiver: Any) {
        callStackContextPerThread[currentThread].add(CallContext.InstanceCallContext(receiver))
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
            val id = callStackTrace.last().suspensionId
            suspendedFunctionsStack[iThread].add(id)
        }
        // TODO: reset suspensionId for finished resumed method
        //  - put whole `callStackTrace` into `suspendedFunctionsStack`?
        //  - on resumption match (a prefix of) the current call stack and saved stack in `suspendedFunctionsStack`?
        //  - or current call stack should be a prefix of saved stack?
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
                afterSpinCycleTraceCollected(
                    trace = _trace,
                    callStackTrace = callStackTrace[currentThread],
                    spinCycleMethodCallsStackTraces = spinCycleMethodCallsStackTraces,
                    iThread = iThread,
                    currentActorId = currentActorId[iThread],
                    beforeMethodCallSwitch = beforeMethodCallSwitch
                )
            }
            _trace += SwitchEventTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread],
                reason = reason,
                callStackTrace = callStackTrace[iThread]
            )
            spinCycleStartAdded = false
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
            afterSpinCycleTraceCollected(
                trace = _trace,
                callStackTrace = callStackTrace[currentThread],
                spinCycleMethodCallsStackTraces = spinCycleMethodCallsStackTraces,
                iThread = iThread,
                currentActorId = currentActorId[iThread],
                beforeMethodCallSwitch = beforeMethodCall
            )
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

// currently the exact place of coroutine suspension is not known
internal const val COROUTINE_SUSPENSION_CODE_LOCATION = -1

// when spin-loop is detected, we might need to replay the execution up to N times
private const val MAX_SPIN_CYCLE_REPLAY_COUNT = 3

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
