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
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicFieldUpdaterNames.getAtomicFieldUpdaterName
import sun.nio.ch.lincheck.*
import java.lang.invoke.*
import sun.misc.Unsafe
import kotlinx.coroutines.*
import java.util.concurrent.atomic.*
import java.lang.reflect.*
import java.util.*
import kotlin.collections.set

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
    protected val loopDetector: LoopDetector = LoopDetector(testCfg.hangingDetectionThreshold)

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
        // Re-transform class constructing trace
        collectTrace = true
        // Replace the current runner with a new one in order to use a new
        runner.close()
        runner = createRunner()

        loopDetector.enableReplayMode(
            failDueToDeadlockInTheEnd = failingResult is ManagedDeadlockInvocationResult || failingResult is ObstructionFreedomViolationInvocationResult
        )

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

    private fun failIfObstructionFreedomIsRequired(lazyMessage: () -> String) {
        if (testCfg.checkObstructionFreedom && !currentActorIsBlocking && !concurrentActorCausesBlocking) {
            suddenInvocationResult = ObstructionFreedomViolationInvocationResult(lazyMessage())
            // Forcibly finish the current execution by throwing an exception.
            throw ForcibleExecutionFinishError
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

    private fun failDueToDeadlock(): Nothing {
        suddenInvocationResult = ManagedDeadlockInvocationResult
        // Forcibly finish the current execution by throwing an exception.
        throw ForcibleExecutionFinishError
    }

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
        check(iThread == currentThread)

        if (loopDetector.replayModeEnabled) {
            /*
             When replaying executions, it's important to repeat the same executions and switches,
             that were recorded to loopDetector history during the last execution.
             For example, let's consider that interleaving say us to switch from thread 1 to thread 2
             at the execution position 200. But after execution 10 spin cycle with period 2 occurred,
             so we will switch from the spin cycle, so when we leave this cycle due to the switch for the first time
             interleaving execution counter may be near 200 and the strategy switch will happen soon. But on the replay run,
             we will switch from thread 1 early, after 12 operations, but no strategy switch will be performed
             for the next 200-12 operations. This leads to the results of another execution, compared to the
             original failure results.
             To avoid this bug when we're replaying some executions, we have to follow only loopDetector history during
             the last execution. In the considered example, we will retain that we will switch soon after
             the spin cycle in thread 1, so no bug will appear.
             */
            newSwitchPointInReplayMode(iThread, codeLocation, tracePoint)
        } else {
            /*
            In the regular mode, we use loop detector only to determine should we
            switch current thread or not due to new or early detection of spin locks. Regular switches appears
            according to the current interleaving.
             */
            newSwitchPointRegular(iThread, codeLocation)
        }
        traceCollector?.passCodeLocation(tracePoint)
        // continue the operation
    }

    private fun newSwitchPointRegular(
        iThread: Int,
        codeLocation: Int
    ) {
        // Switch in the parallel part if the strategy decides so.
        val shouldSwitchDueToStrategy = runner.currentExecutionPart == PARALLEL && shouldSwitch(iThread)
        val spinLockDetected = loopDetector.visitCodeLocation(iThread, codeLocation)

        if (spinLockDetected) {
            failIfObstructionFreedomIsRequired {
                OBSTRUCTION_FREEDOM_SPINLOCK_VIOLATION_MESSAGE
            }
        }
        if (shouldSwitchDueToStrategy or spinLockDetected) {
            if (spinLockDetected) {
                switchCurrentThreadDueToActiveLock(iThread, loopDetector.replayModeCurrentCyclePeriod)
            } else {
                switchCurrentThread(iThread, SwitchReason.STRATEGY_SWITCH)
            }
            loopDetector.initializeFirstCodeLocationAfterSwitch(codeLocation)
        } else {
            loopDetector.onNextExecutionPoint(codeLocation)
        }
    }

    private fun newSwitchPointInReplayMode(iThread: Int, codeLocation: Int, tracePoint: TracePoint?) {
        if (loopDetector.visitCodeLocation(iThread, codeLocation)) {
            if (loopDetector.isSpinLockSwitch) {
                failIfObstructionFreedomIsRequired {
                    // Log the last event that caused obstruction freedom violation.
                    traceCollector?.passCodeLocation(tracePoint)
                    OBSTRUCTION_FREEDOM_SPINLOCK_VIOLATION_MESSAGE
                }
                switchCurrentThreadDueToActiveLock(iThread, loopDetector.replayModeCurrentCyclePeriod)
            } else {
                switchCurrentThread(iThread, SwitchReason.STRATEGY_SWITCH)
            }
        }
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
        suddenInvocationResult = UnexpectedExceptionInvocationResult(exception)
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
    private fun switchCurrentThread(iThread: Int, reason: SwitchReason = SwitchReason.STRATEGY_SWITCH, mustSwitch: Boolean = false) {
        traceCollector?.newSwitch(iThread, reason)
        doSwitchCurrentThread(iThread, mustSwitch)
        awaitTurn(iThread)
    }

    /**
     * A regular context thread switch to another thread.
     */
    private fun switchCurrentThreadDueToActiveLock(
        iThread: Int, cyclePeriod: Int
    ) {
        traceCollector?.let {
            it.newActiveLockDetected(iThread, cyclePeriod)
            it.newSwitch(iThread, SwitchReason.ACTIVE_LOCK)
        }
        doSwitchCurrentThread(iThread, false)
        awaitTurn(iThread)
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
                    suddenInvocationResult = ManagedDeadlockInvocationResult
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
    override fun beforeReadField(obj: Any, className: String, fieldName: String, codeLocation: Int) = runInIgnoredSection {
        if (localObjectManager.isLocalObject(obj)) return@runInIgnoredSection false
        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            ReadTracePoint(
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
        true
    }

    override fun beforeReadFinalFieldStatic(className: String) = runInIgnoredSection {
        // We need to ensure all the classes related to the reading object are instrumented.
        // The following call checks all the static fields.
        LincheckJavaAgent.ensureClassHierarchyIsTransformed(className.canonicalClassName)
    }

    override fun beforeReadFieldStatic(className: String, fieldName: String, codeLocation: Int) = runInIgnoredSection {
        // We need to ensure all the classes related to the reading object are instrumented.
        // The following call checks all the static fields.
        LincheckJavaAgent.ensureClassHierarchyIsTransformed(className.canonicalClassName)

        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            ReadTracePoint(
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
    }

    /** Returns <code>true</code> if a switch point is created. */
    override fun beforeReadArrayElement(array: Any, index: Int, codeLocation: Int): Boolean = runInIgnoredSection {
        if (localObjectManager.isLocalObject(array)) return@runInIgnoredSection false
        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            ReadTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread],
                callStackTrace = callStackTrace[iThread],
                fieldName = "Array[$index]",
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            )
        } else {
            null
        }
        if (tracePoint != null) {
            lastReadTracePoint[iThread] = tracePoint
        }
        newSwitchPoint(iThread, codeLocation, tracePoint)
        true
    }

    override fun afterRead(value: Any?) {
        if (collectTrace) {
            runInIgnoredSection {
                val iThread = currentThread
                lastReadTracePoint[iThread]?.initializeReadValue(value)
                lastReadTracePoint[iThread] = null
            }
        }
    }

    override fun beforeWriteField(obj: Any, className: String, fieldName: String, value: Any?, codeLocation: Int): Boolean = runInIgnoredSection {
        localObjectManager.onWriteToObjectFieldOrArrayCell(obj, value)
        if (localObjectManager.isLocalObject(obj)) {
            return@runInIgnoredSection false
        }
        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            WriteTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread],
                callStackTrace = callStackTrace[iThread],
                fieldName = fieldName,
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            ).also {
                it.initializeWrittenValue(value)
            }
        } else {
            null
        }
        newSwitchPoint(iThread, codeLocation, tracePoint)
        true
    }


    override fun beforeWriteFieldStatic(className: String, fieldName: String, value: Any?, codeLocation: Int): Unit = runInIgnoredSection {
        localObjectManager.markObjectNonLocal(value)
        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            WriteTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread],
                callStackTrace = callStackTrace[iThread],
                fieldName = fieldName,
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            ).also {
                it.initializeWrittenValue(value)
            }
        } else {
            null
        }
        newSwitchPoint(iThread, codeLocation, tracePoint)
    }

    override fun beforeWriteArrayElement(array: Any, index: Int, value: Any?, codeLocation: Int): Boolean = runInIgnoredSection {
        localObjectManager.onWriteToObjectFieldOrArrayCell(array, value)
        if (localObjectManager.isLocalObject(array)) {
            return@runInIgnoredSection false
        }
        val iThread = currentThread
        val tracePoint = if (collectTrace) {
            WriteTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread],
                callStackTrace = callStackTrace[iThread],
                fieldName = "Array[$index]",
                stackTraceElement = CodeLocations.stackTrace(codeLocation)
            ).also {
                it.initializeWrittenValue(value)
            }
        } else {
            null
        }
        newSwitchPoint(iThread, codeLocation, tracePoint)
        true
    }

    override fun afterWrite() {
        if (collectTrace) {
            runInIgnoredSection {
                traceCollector?.addStateRepresentation()
            }
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

    override fun onWriteToObjectFieldOrArrayCell(receiver: Any, fieldOrArrayCellValue: Any?) = runInIgnoredSection {
        localObjectManager.onWriteToObjectFieldOrArrayCell(receiver, fieldOrArrayCellValue)
    }

    override fun onWriteObjectToStaticField(fieldValue: Any?) = runInIgnoredSection {
        localObjectManager.markObjectNonLocal(fieldValue)
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
                        beforeMethodCall(currentThread, codeLocation, null, methodName, params)
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
                        beforeMethodCall(currentThread, codeLocation, null, methodName, params)
                    }
                    newSwitchPointOnAtomicMethodCall(codeLocation)
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
                if (collectTrace) {
                    runInIgnoredSection {
                        val params = if (isSuspendFunction(className, methodName, params)) {
                            params.dropLast(1).toTypedArray()
                        } else {
                            params
                        }
                        beforeMethodCall(currentThread, codeLocation, null, methodName, params)
                    }
                }
            }
        }
    }

    override fun beforeAtomicMethodCall(
        owner: Any?,
        methodName: String,
        codeLocation: Int,
        params: Array<Any?>
    ) = runInIgnoredSection {
        if (collectTrace) {
            val isAtomicUpdater = owner is AtomicIntegerFieldUpdater<*> || owner is AtomicLongFieldUpdater<*> || owner is AtomicReferenceFieldUpdater<*, *>
            val ownerName = if (isAtomicUpdater) owner?.let { getAtomicFieldUpdaterName(it) } else null
            // Drop the object instance and offset (in case of Unsafe) from the parameters
            // when using Unsafe, VarHandle, or AtomicFieldUpdater.
            @Suppress("NAME_SHADOWING")
            val params = when {
                isAtomicUpdater || owner is VarHandle -> params.drop(1).toTypedArray()
                owner is Unsafe || (owner != null && owner::class.java.name == "jdk.internal.misc.Unsafe") -> params.drop(2).toTypedArray()
                else -> params
            }
            beforeMethodCall(currentThread, codeLocation, ownerName, methodName, params)
        }
        newSwitchPointOnAtomicMethodCall(codeLocation)
    }

    override fun onMethodCallFinishedSuccessfully(result: Any?) {
        if (collectTrace) {
            runInIgnoredSection {
                val iThread = currentThread
                val tracePoint = methodCallTracePointStack[iThread].removeLast()
                tracePoint.initializeReturnedValue(if (result == Injections.VOID_RESULT) VoidResult else result)
                afterMethodCall(iThread, tracePoint)
                traceCollector!!.addStateRepresentation()
            }
        }
        // In case the code is now in an "ignore" section due to
        // an "atomic" or "ignore" guarantee, we need to leave
        // this "ignore" section.
        leaveIgnoredSectionIfEntered()
    }

    override fun onMethodCallThrewException(t: Throwable) {
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

    private fun newSwitchPointOnAtomicMethodCall(codeLocation: Int) {
        // re-use last call trace point
        newSwitchPoint(currentThread, codeLocation, callStackTrace[currentThread].lastOrNull()?.call)
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
    private fun beforeMethodCall(iThread: Int, codeLocation: Int, ownerName: String?, methodName: String, params: Array<Any?>) {
        val callStackTrace = callStackTrace[iThread]
        val suspendedMethodStack = suspendedFunctionsStack[iThread]
        val methodId = if (suspendedMethodStack.isNotEmpty()) {
            // If there was a suspension before, then instead of creating a new identifier,
            // use the one that the suspended call had
            val lastId = suspendedMethodStack.last()
            suspendedMethodStack.removeAt(suspendedMethodStack.lastIndex)
            lastId
        } else {
            methodCallNumber++
        }
        // Code location of the new method call is currently the last one
        val tracePoint = MethodCallTracePoint(
            iThread = iThread,
            actorId = currentActorId[iThread],
            callStackTrace = callStackTrace,
            methodName = methodName,
            stackTraceElement = CodeLocations.stackTrace(codeLocation)
        ).also {
            it.initializeParameters(params)
            it.initializeOwnerName(ownerName)
        }
        methodCallTracePointStack[iThread] += tracePoint
        callStackTrace.add(CallStackTraceElement(tracePoint, methodId))
    }

    /**
     * This method is invoked by a test thread
     * after each method invocation.
     * @param iThread number of invoking thread
     * @param tracePoint the corresponding trace point for the invocation
     */
    private fun afterMethodCall(iThread: Int, tracePoint: MethodCallTracePoint) {
        val callStackTrace = callStackTrace[iThread]
        if (tracePoint.wasSuspended) {
            // if a method call is suspended, save its identifier to reuse for continuation resuming
            suspendedFunctionsStack[iThread].add(callStackTrace.last().identifier)
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

        fun newSwitch(iThread: Int, reason: SwitchReason) {
            _trace += SwitchEventTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread],
                reason = reason,
                callStackTrace = callStackTrace[iThread]
            )
        }

        fun newActiveLockDetected(iThread: Int, cyclePeriod: Int) {
            val spinCycleStartPosition = _trace.size - cyclePeriod
            val spinCycleStartStackTrace = if (spinCycleStartPosition <= _trace.lastIndex) {
                _trace[spinCycleStartPosition].callStackTrace
            } else {
                emptyList()
            }
            val spinCycleStartTracePoint = SpinCycleStartTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread],
                callStackTrace = spinCycleStartStackTrace
            )
            _trace.add(spinCycleStartPosition, spinCycleStartTracePoint)
        }

        fun passCodeLocation(tracePoint: TracePoint?) {
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

        fun passObstructionFreedomViolationTracePoint(iThread: Int) {
            _trace += ObstructionFreedomViolationExecutionAbortTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread],
                callStackTrace = _trace.last().callStackTrace
            )
        }
    }


    /**
     * The LoopDetector class identifies loops, active locks, and live locks by monitoring the frequency of visits to the same code location.
     * It operates under a specific scenario constraint due to its reliance on cache information about loops,
     * determined by thread executions and switches, which is only reusable in a single scenario.
     *
     * The LoopDetector functions in two modes: default and replay mode.
     *
     * In default mode:
     * - The LoopDetector tracks code location executions (using [currentThreadCodeLocationsHistory]) performed by threads.
     * The history is stored for the current thread and is cleared during a thread switch.
     * - A map ([currentThreadCodeLocationVisitCountMap]) is maintained to track the number of times a thread visits a certain code location.
     * This map is also cleared during a thread switch.
     * - If a code location is visited more than a defined [hangingDetectionThreshold], it is considered as a spin cycle.
     * The LoopDetector then tries to identify the sequence of actions leading to the spin cycle.
     * Once identified, this sub-interleaving is stored for future avoidance.
     * - A history of executions and switches is maintained to record the sequence of actions and thread switches.
     * - A [loopTrackingCursor] tracks executions and thread switches to facilitate early thread switches.
     * - A counter for operation execution [totalExecutionsCount] across all threads is maintained.
     * This counter increments with each code location visit and is increased by the hangingDetectionThreshold if a spin cycle is detected early.
     * - If the counter exceeds the [ManagedCTestConfiguration.LIVELOCK_EVENTS_THRESHOLD], a total deadlock is assumed.
     * Due to the relative small size of scenarios generated by Lincheck, such a high number of executions indicates a lack of progress in the system.
     *
     * In replay mode:
     * - The number of allowable events to execute in each thread is determined using saved information from the last interleaving.
     * - For instance, if the [currentInterleavingHistory] is [0: 2], [1: 3], [0: 3], [1: 3], [0: 3], ..., [1: 3], [0: 3] and a deadlock is detected,
     * the cycle is identified as [1: 3], [0: 3].
     * This means 2 executions in thread 0 and 3 executions in both threads 1 and 0 will be allowed.
     * - Execution is halted after the last execution in thread 0 using [ForcibleExecutionFinishError].
     * - The logic for tracking executions and switches in replay mode is implemented in [ReplayModeLoopDetectorHelper].
     *
     * Note: An example of this behavior is detailed in the comments of the code itself.
     */
    inner class LoopDetector(private val hangingDetectionThreshold: Int) {
        private var lastExecutedThread = -1// no last thread

        /**
         * Map, which helps us to determine how many times current thread visits some code location.
         */
        private val currentThreadCodeLocationVisitCountMap = mutableMapOf<Int, Int>()

        /**
         * Is used to find a cycle period inside exact thread execution if it has hung
         */
        private val currentThreadCodeLocationsHistory = mutableListOf<Int>()

        /**
         *  Threads switches and executions history to store sequences lead to loops
         */
        private val currentInterleavingHistory = ArrayList<InterleavingHistoryNode>()

        /**
         * When we're back to some thread, newSwitchPoint won't be called before the first event in the current
         * thread part as it was called before the switch. So when we return to a thread that already was running,
         * we have to start from 1 its executions counter. This set helps us to determine if some thread is running
         * for the first time in an execution or not.
         */
        private val threadsRan: BooleanArray = BooleanArray(nThreads) { false }

        /**
         * Set of interleaving event sequences lead to loops. (A set of previously detected hangs)
         */
        private val interleavingsLeadToSpinLockSet = InterleavingSequenceTrackableSet()

        /**
         * Helps to determine does current interleaving equal to some saved interleaving leading to spin cycle or not
         */
        private val loopTrackingCursor = interleavingsLeadToSpinLockSet.cursor

        private var totalExecutionsCount = 0

        private val firstThreadSet: Boolean get() = lastExecutedThread != -1

        /**
         * Delegate helper, active in replay (trace collection) mode.
         * It just tracks executions and switches and helps to halt execution or switch in case of spin-lock early.
         */
        private var replayModeLoopDetectorHelper: ReplayModeLoopDetectorHelper? = null

        val replayModeCurrentCyclePeriod: Int get() = replayModeLoopDetectorHelper?.currentCyclePeriod ?: 0

        val replayModeEnabled: Boolean get() = replayModeLoopDetectorHelper != null

        val isSpinLockSwitch: Boolean
            get() = replayModeLoopDetectorHelper?.isActiveLockNode ?: error("Loop detector is not in replay mode")

        fun enableReplayMode(failDueToDeadlockInTheEnd: Boolean) {
            val contextSwitchesBeforeHalt =
                findMaxPrefixLengthWithNoCycleOnSuffix(currentInterleavingHistory)?.let { it.executionsBeforeCycle + it.cyclePeriod }
                    ?: currentInterleavingHistory.size
            val spinCycleInterleavingHistory = currentInterleavingHistory.take(contextSwitchesBeforeHalt)
            // Remove references to interleaving tree
            interleavingsLeadToSpinLockSet.clear()
            loopTrackingCursor.clear()

            replayModeLoopDetectorHelper = ReplayModeLoopDetectorHelper(
                interleavingHistory = spinCycleInterleavingHistory,
                failDueToDeadlockInTheEnd = failDueToDeadlockInTheEnd
            )
        }

        /**
         * Returns `true` if a loop or a hang is detected,
         * `false` otherwise.
         */
        fun visitCodeLocation(iThread: Int, codeLocation: Int): Boolean {
            threadsRan[iThread] = true
            replayModeLoopDetectorHelper?.let { return it.onNextExecution() }
            // Increase the total number of happened operations for live-lock detection
            totalExecutionsCount++
            // Have the thread changed? Reset the counters in this case.
            check(lastExecutedThread == iThread) { "reset expected!" }
            // Ignore coroutine suspension code locations.
            if (codeLocation == COROUTINE_SUSPENSION_CODE_LOCATION) return false
            // Increment the number of times the specified code location is visited.
            val count = currentThreadCodeLocationVisitCountMap.getOrDefault(codeLocation, 0) + 1
            currentThreadCodeLocationVisitCountMap[codeLocation] = count
            currentThreadCodeLocationsHistory += codeLocation
            val detectedFirstTime = count > hangingDetectionThreshold
            val detectedEarly = loopTrackingCursor.isInCycle
            // DetectedFirstTime and detectedEarly can both sometimes be true
            // when we can't find a cycle period and can't switch to another thread.
            // Check whether the count exceeds the maximum number of repetitions for loop/hang detection.
            if (detectedFirstTime && !detectedEarly) {
                registerCycle()
                // Enormous operations count considered as total spin lock
                if (totalExecutionsCount > ManagedCTestConfiguration.LIVELOCK_EVENTS_THRESHOLD) {
                    failDueToDeadlock()
                }
                // Replay current interleaving to avoid side effects caused by multiple cycle executions
                suddenInvocationResult = SpinCycleFoundAndReplayRequired
                throw ForcibleExecutionFinishError
            }
            if (!detectedFirstTime && detectedEarly) {
                totalExecutionsCount += hangingDetectionThreshold
                val lastNode = currentInterleavingHistory.last()
                // spinCyclePeriod may be not 0 only we tried to switch
                // from the current thread but no available threads were available to switch
                if (lastNode.spinCyclePeriod == 0) {
                    // transform current node to the state corresponding to early found cycle
                    val cyclePeriod = loopTrackingCursor.cyclePeriod
                    lastNode.executions -= cyclePeriod
                    lastNode.spinCyclePeriod = cyclePeriod
                    lastNode.executionHash = loopTrackingCursor.cycleLocationsHash
                }
                // Enormous operations count considered as total spin lock
                if (totalExecutionsCount > ManagedCTestConfiguration.LIVELOCK_EVENTS_THRESHOLD) {
                    failDueToDeadlock()
                }
            }
            return detectedFirstTime || detectedEarly
        }

        fun onActorStart(iThread: Int) {
            check(iThread == lastExecutedThread)
            // if a thread has reached a new actor, then it means it has made some progress;
            // therefore, we reset the code location counters,
            // so that code location hits from a previous actor do not affect subsequent actors
            currentThreadCodeLocationVisitCountMap.clear()
        }

        fun onThreadSwitch(iThread: Int) {
            lastExecutedThread = iThread
            currentThreadCodeLocationVisitCountMap.clear()
            currentThreadCodeLocationsHistory.clear()
            onNextThreadSwitchPoint(iThread)
        }

        fun onThreadFinish(iThread: Int) {
            check(iThread == lastExecutedThread)
            onNextExecutionPoint(executionIdentity = -iThread)
        }

        private fun onNextThreadSwitchPoint(nextThread: Int) {
            /*
                When we're back to some thread, newSwitchPoint won't be called before the fist
                in current thread part as it was called before switch.
                So, we're tracking that to maintain the number of performed operations correctly.
             */
            val threadRunningFirstTime = !threadsRan[nextThread]
            if (currentInterleavingHistory.isNotEmpty() && currentInterleavingHistory.last().threadId == nextThread) {
                return
            }
            currentInterleavingHistory.add(
                InterleavingHistoryNode(
                    threadId = nextThread,
                    executions = if (threadRunningFirstTime) 0 else 1,
                )
            )
            loopTrackingCursor.onNextSwitchPoint(nextThread)
            if (!threadRunningFirstTime) {
                loopTrackingCursor.onNextExecutionPoint()
            }
            replayModeLoopDetectorHelper?.onNextSwitch(threadRunningFirstTime)
        }

        /**
         * Is called after switch back to a thread
         */
        fun initializeFirstCodeLocationAfterSwitch(codeLocation: Int) {
            val lastInterleavingHistoryNode = currentInterleavingHistory.last()
            lastInterleavingHistoryNode.executionHash = lastInterleavingHistoryNode.executionHash xor codeLocation
        }

        fun onNextExecutionPoint(executionIdentity: Int) {
            val lastInterleavingHistoryNode = currentInterleavingHistory.last()
            if (lastInterleavingHistoryNode.cycleOccurred) {
                return /* If we already ran into cycle and haven't switched than no need to track executions */
            }
            lastInterleavingHistoryNode.addExecution(executionIdentity)
            loopTrackingCursor.onNextExecutionPoint()
            replayModeLoopDetectorHelper?.onNextExecution()
        }

        private fun registerCycle() {
            val cycleInfo = findMaxPrefixLengthWithNoCycleOnSuffix(currentThreadCodeLocationsHistory)
            if (cycleInfo == null) {
                val lastNode = currentInterleavingHistory.last()
                val cycleStateLastNode = lastNode.asNodeCorrespondingToCycle(
                    executionsBeforeCycle = currentThreadCodeLocationsHistory.size - 1,
                    cyclePeriod = 0,
                    cycleExecutionsHash = lastNode.executionHash // corresponds to a cycle
                )

                currentInterleavingHistory[currentInterleavingHistory.lastIndex] = cycleStateLastNode
                interleavingsLeadToSpinLockSet.addBranch(currentInterleavingHistory)
                return
            }
            /*
            For nodes, correspond to cycles we re-calculate hash using only code locations related to the cycle,
            because if we run into a DeadLock,
            it's enough to show events before the cycle and first cycle iteration in the current thread.
            For example,
            [threadId = 0, executions = 10],
            [threadId = 1, executions = 5], // 2 executions before cycle and then cycle of 3 executions begins
            [threadId = 0, executions = 3],
            [threadId = 1, executions = 3],
            [threadId = 0, executions = 3],
            ...
            [threadId = 1, executions = 3],
            [threadId = 0, executions = 3]

            In this situation, we have a spin cycle:[threadId = 1, executions = 3], [threadId = 0, executions = 3].
            We want to cut off events suffix to get:
            [threadId = 0, executions = 10],
            [threadId = 1, executions = 5], // 2 executions before cycle, and then cycle begins
            [threadId = 0, executions = 3],

            So we need to [threadId = 1, executions = 5] execution part to have a hash equals to next cycle nodes,
            because we will take only thread executions before cycle and the first cycle iteration.
             */
            var cycleExecutionLocationsHash = currentThreadCodeLocationsHistory[cycleInfo.executionsBeforeCycle]
            for (i in cycleInfo.executionsBeforeCycle + 1 until cycleInfo.executionsBeforeCycle + cycleInfo.cyclePeriod) {
                cycleExecutionLocationsHash = cycleExecutionLocationsHash xor currentThreadCodeLocationsHistory[i]
            }

            val cycleStateLastNode = currentInterleavingHistory.last().asNodeCorrespondingToCycle(
                executionsBeforeCycle = cycleInfo.executionsBeforeCycle,
                cyclePeriod = cycleInfo.cyclePeriod,
                cycleExecutionsHash = cycleExecutionLocationsHash // corresponds to a cycle
            )

            currentInterleavingHistory[currentInterleavingHistory.lastIndex] = cycleStateLastNode
            interleavingsLeadToSpinLockSet.addBranch(currentInterleavingHistory)
        }

        /**
         * Is called before each interleaving part processing
         */
        fun beforePart(nextThread: Int) {
            clearRanThreads()
            if (!firstThreadSet) {
                setFirstThread(nextThread)
            } else if (lastExecutedThread != nextThread) {
                onThreadSwitch(nextThread)
            }
        }

        /**
         * Is called before each interleaving processing
         */
        fun initialize() {
            lastExecutedThread = -1
            clearRanThreads()
        }

        private fun clearRanThreads() {
            for (i in 0 until nThreads) {
                threadsRan[i] = false
            }
        }

        private fun setFirstThread(iThread: Int) {
            lastExecutedThread = iThread // certain last thread
            currentThreadCodeLocationVisitCountMap.clear()
            currentThreadCodeLocationsHistory.clear()
            totalExecutionsCount = 0

            loopTrackingCursor.reset(iThread)
            currentInterleavingHistory.clear()
            currentInterleavingHistory.add(InterleavingHistoryNode(threadId = iThread))
            replayModeLoopDetectorHelper?.initialize()
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
     * Helper class to halt execution on replay (trace collection phase) and to switch thread early on spin-cycles
     */
    private inner class ReplayModeLoopDetectorHelper(
        private val interleavingHistory: List<InterleavingHistoryNode>,
        /**
         * Should we fail with deadlock failure when all events in the current interleaving are completed
         */
        private val failDueToDeadlockInTheEnd: Boolean,
    ) {
        val isActiveLockNode: Boolean get() = interleavingHistory[currentInterleavingNodeIndex].spinCyclePeriod != 0

        /**
         * Cycle period if is occurred in during current thread switch or 0 if no spin-cycle happened
         */
        val currentCyclePeriod: Int get() = interleavingHistory[currentInterleavingNodeIndex].spinCyclePeriod

        private var currentInterleavingNodeIndex = 0

        private var executionsPerformedInCurrentThread = 0

        /**
         * A set of thread, executed at least once during this interleaving.
         *
         * We have to maintain this set to determine how to initialize
         * [executionsPerformedInCurrentThread] after thread switch.
         * When a thread is executed for the first time, [newSwitchPoint]
         * strategy method is called before the first switch point,
         * so number of executions in this thread should start with zero,
         * and it will be incremented after [onNextExecution] call.
         *
         * But when we return to a thread which has already executed its operations, [newSwitchPoint]
         * strategy method won't be called,
         * as we already considered this switch point before we switched from this thread earlier,
         * [onNextExecution] won't be called before the first execution,
         * so we have to start [executionsPerformedInCurrentThread] from 1.
         */
        private val threadsRan = hashSetOf<Int>()

        fun initialize() {
            currentInterleavingNodeIndex = 0
            executionsPerformedInCurrentThread = 0
            threadsRan.clear()
        }

        /**
         * Called before next execution in current thread.
         *
         * @return should we switch from the current thread?
         */
        fun onNextExecution(): Boolean {
            require(currentInterleavingNodeIndex <= interleavingHistory.lastIndex) { "Internal error" }
            val historyNode = interleavingHistory[currentInterleavingNodeIndex]
            // switch current thread after we executed operations before spin cycle and cycle iteration to show it
            val shouldSwitchThread =
                executionsPerformedInCurrentThread++ >= historyNode.spinCyclePeriod + historyNode.executions
            checkFailDueToDeadlock(shouldSwitchThread)
            return shouldSwitchThread
        }

        /**
         * Called before next thread switch
         */
        fun onNextSwitch(threadRunningFirstTime: Boolean) {
            currentInterleavingNodeIndex++
            // See threadsRan field description to understand the following initialization logic
            executionsPerformedInCurrentThread = if (threadRunningFirstTime) 0 else 1
        }

        private fun checkFailDueToDeadlock(shouldSwitchThread: Boolean) {
            // Fail if we ran into cycle,
            // this cycle node is the last node in the replayed interleaving
            // and have to fail at the end of the execution
            if (shouldSwitchThread && currentInterleavingNodeIndex == interleavingHistory.lastIndex && failDueToDeadlockInTheEnd) {
                val cyclePeriod = interleavingHistory[currentInterleavingNodeIndex].spinCyclePeriod
                if (cyclePeriod != 0) {
                    traceCollector?.newActiveLockDetected(currentThread, cyclePeriod)
                }
                failIfObstructionFreedomIsRequired {
                    traceCollector?.passObstructionFreedomViolationTracePoint(currentThread)
                    OBSTRUCTION_FREEDOM_SPINLOCK_VIOLATION_MESSAGE
                }
                traceCollector?.newSwitch(currentThread, SwitchReason.ACTIVE_LOCK)
                failDueToDeadlock()
            }
        }
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

private const val COROUTINE_SUSPENSION_CODE_LOCATION = -1 // currently the exact place of coroutine suspension is not known

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
