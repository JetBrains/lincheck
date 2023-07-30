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

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.CancellationResult.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.objectweb.asm.*
import java.io.*
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
    private val validationFunctions: List<Method>,
    private val stateRepresentationFunction: Method?,
    private val testCfg: ManagedCTestConfiguration
) : Strategy(scenario), Closeable {
    // The number of parallel threads.
    protected val nThreads: Int = scenario.nThreads
    // Runner for scenario invocations,
    // can be replaced with a new one for trace construction.
    private var runner: ManagedStrategyRunner
    // Shares location ids between class transformers in order
    // to keep them different in different code locations.
    private val codeLocationIdProvider = CodeLocationIdProvider()

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
    // Ihe number of entered but not left (yet) blocks that should be ignored by the strategy analysis for each thread.
    private val ignoredSectionDepth = IntArray(nThreads) { 0 }
    // Detector of loops or hangs (i.e. active locks).
    protected val loopDetector: LoopDetector = LoopDetector(testCfg.hangingDetectionThreshold)

    // Tracker of acquisitions and releases of monitors.
    private lateinit var monitorTracker: MonitorTracker

    // InvocationResult that was observed by the strategy during the execution (e.g., a deadlock).
    @Volatile
    protected var suddenInvocationResult: InvocationResult? = null

    // == TRACE CONSTRUCTION FIELDS ==

    // Whether an additional information requires for the trace construction should be collected.
    private var collectTrace = false
    // Whether state representations (see `@StateRepresentation`) should be collected after interleaving events.
    private val collectStateRepresentation get() = collectTrace && stateRepresentationFunction != null
    // Trace point constructors, where `tracePointConstructors[id]`
    // stores a constructor for the corresponding code location.
    private val tracePointConstructors: MutableList<TracePointConstructor> = ArrayList()
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
    // Current execution part
    protected lateinit var executionPart: ExecutionPart

    init {
        runner = createRunner()
        // The managed state should be initialized before еру test class transformation.
        try {
            // Initialize ManagedStrategyStateHolder - it can be used during test class construction.
            ManagedStrategyStateHolder.setState(runner.classLoader, this, testClass)
            runner.initialize()
        } catch (t: Throwable) {
            runner.close()
            throw t
        }
    }

    private fun createRunner(): ManagedStrategyRunner =
        ManagedStrategyRunner(this, testClass, validationFunctions, stateRepresentationFunction, testCfg.timeoutMs, UseClocks.ALWAYS)

    override fun createTransformer(cv: ClassVisitor): ClassVisitor = ManagedStrategyTransformer(
        cv = cv,
        tracePointConstructors = tracePointConstructors,
        guarantees = testCfg.guarantees,
        eliminateLocalObjects = testCfg.eliminateLocalObjects,
        collectStateRepresentation = collectStateRepresentation,
        constructTraceRepresentation = collectTrace,
        codeLocationIdProvider = codeLocationIdProvider
    )

    override fun needsTransformation(): Boolean = true

    override fun run(): LincheckFailure? = runImpl().also { close() }

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
        ignoredSectionDepth.fill(0)
        callStackTrace.forEach { it.clear() }
        suspendedFunctionsStack.forEach { it.clear() }
        ManagedStrategyStateHolder.setState(runner.classLoader, this, testClass)
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
        // `TransformationClassLoader` with a transformer that inserts the trace collection logic.
        runner.close()
        runner = createRunner()
        ManagedStrategyStateHolder.setState(runner.classLoader, this, testClass)
        runner.initialize()

        loopDetector.enableReplayMode(
            failDueToDeadlockInTheEnd = failingResult is DeadlockInvocationResult || failingResult is ObstructionFreedomViolationInvocationResult
        )

        val loggedResults = runInvocation()
        val sameResultTypes = loggedResults.javaClass == failingResult.javaClass
        val sameResults = loggedResults !is CompletedInvocationResult || failingResult !is CompletedInvocationResult || loggedResults.results == failingResult.results
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
        // Has strategy already determined the invocation result?
        suddenInvocationResult?.let { return it  }
        return result
    }

    private fun failIfObstructionFreedomIsRequired(lazyMessage: () -> String) {
        if (testCfg.checkObstructionFreedom && !curActorIsBlocking && !concurrentActorCausesBlocking) {
            suddenInvocationResult = ObstructionFreedomViolationInvocationResult(lazyMessage())
            // Forcibly finish the current execution by throwing an exception.
            throw ForcibleExecutionFinishException
        }
    }

    private val curActorIsBlocking: Boolean
        get() = scenario.threads[currentThread][currentActorId[currentThread]].blocking

    private val concurrentActorCausesBlocking: Boolean
        get() = currentActorId.mapIndexed { iThread, actorId ->
                    if (iThread != currentThread && !finished[iThread])
                        scenario.threads[iThread][actorId]
                    else null
                }.filterNotNull().any { it.causesBlocking }

    private fun failDueToDeadlock(): Nothing {
        suddenInvocationResult = DeadlockInvocationResult()
        // Forcibly finish the current execution by throwing an exception.
        throw ForcibleExecutionFinishException
    }

    override fun close() {
        runner.close()
    }

    // == EXECUTION CONTROL METHODS ==

    /**
     * Create a new switch point, where a thread context switch can occur.
     * @param iThread the current thread
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of the point in code.
     */
    private fun newSwitchPoint(iThread: Int, codeLocation: Int, tracePoint: TracePoint?) {
        if (!isTestThread(iThread)) return // can switch only test threads
        if (inIgnoredSection(iThread)) return // cannot suspend in ignored sections
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
        val shouldSwitchDueToStrategy = shouldSwitch(iThread)
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
                    // Log the last event that caused obstruction freedom violation
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
     * This method is executed if an exception has been thrown.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param exception the exception that was thrown
     */
    open fun onFailure(iThread: Int, exception: Throwable) {
        // Despite the fact that the corresponding failure will be detected by the runner,
        // the managed strategy can construct a trace to reproduce this failure.
        // Let's then store the corresponding failing result and construct the trace.
        if (exception === ForcibleExecutionFinishException) return // not a forcible execution finish
        suddenInvocationResult =
            UnexpectedExceptionInvocationResult(wrapInvalidAccessFromUnnamedModuleExceptionWithDescription(exception))
    }

    override fun onActorStart(iThread: Int) {
        currentActorId[iThread]++
        callStackTrace[iThread].clear()
        suspendedFunctionsStack[iThread].clear()
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
    private fun awaitTurn(iThread: Int) {
        // Wait actively until the thread is allowed to continue
        var i = 0
        while (currentThread != iThread) {
            // Finish forcibly if an error occurred and we already have an `InvocationResult`.
            if (suddenInvocationResult != null) throw ForcibleExecutionFinishException
            if (++i % SPINNING_LOOP_ITERATIONS_BEFORE_YIELD == 0) Thread.yield()
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
                // all threads are suspended
                // then switch on any suspended thread to finish it and get SuspendedResult
                val nextThread = (0 until nThreads).firstOrNull { !finished[it] && isSuspended[it] }
                if (nextThread == null) {
                    // must switch not to get into a deadlock, but there are no threads to switch.
                    suddenInvocationResult = DeadlockInvocationResult()
                    // forcibly finish execution by throwing an exception.
                    throw ForcibleExecutionFinishException
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
        if (runner.currentExecutionPart == ExecutionPart.PARALLEL)
            (0 until nThreads).filter { it != iThread && isActive(it) }
        else listOf()

    private fun isTestThread(iThread: Int) = iThread < nThreads

    /**
     * The execution in an ignored section (added by transformer) or not in a test thread must not add switch points.
     * Additionally, after [ForcibleExecutionFinishException] everything is ignored.
     */
    private fun inIgnoredSection(iThread: Int): Boolean =
        !isTestThread(iThread) ||
            ignoredSectionDepth[iThread] > 0 ||
            suddenInvocationResult != null


    // == LISTENING METHODS ==

    /**
     * This method is executed before a shared variable read operation.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    internal fun beforeSharedVariableRead(iThread: Int, codeLocation: Int, tracePoint: ReadTracePoint?) {
        newSwitchPoint(iThread, codeLocation, tracePoint)
    }

    /**
     * This method is executed before a shared variable write operation.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    internal fun beforeSharedVariableWrite(iThread: Int, codeLocation: Int, tracePoint: WriteTracePoint?) {
        newSwitchPoint(iThread, codeLocation, tracePoint)
    }

    /**
     * This method is executed before an atomic method call.
     * Atomic method is a method that is marked by ManagedGuarantee to be treated as atomic.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    internal fun beforeAtomicMethodCall(iThread: Int, codeLocation: Int) {
        if (!isTestThread(iThread)) return
        // re-use last call trace point
        newSwitchPoint(iThread, codeLocation, callStackTrace[iThread].lastOrNull()?.call)
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @return whether lock should be actually acquired
     */
    internal fun beforeLockAcquire(iThread: Int, codeLocation: Int, tracePoint: MonitorEnterTracePoint?, monitor: Any): Boolean {
        if (!isTestThread(iThread)) return true
        if (inIgnoredSection(iThread)) return false
        newSwitchPoint(iThread, codeLocation, tracePoint)
        // Try to acquire the monitor
        while (!monitorTracker.acquireMonitor(iThread, monitor)) {
            failIfObstructionFreedomIsRequired {
                OBSTRUCTION_FREEDOM_LOCK_VIOLATION_MESSAGE
            }
            // Switch to another thread and wait for a moment when the monitor can be acquired
            switchCurrentThread(iThread, SwitchReason.LOCK_WAIT, true)
        }
        // The monitor is acquired, finish.
        return false
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @return whether lock should be actually released
     */
    internal fun beforeLockRelease(iThread: Int, codeLocation: Int, tracePoint: MonitorExitTracePoint?, monitor: Any): Boolean {
        if (!isTestThread(iThread)) return true
        if (inIgnoredSection(iThread)) return false
        monitorTracker.releaseMonitor(monitor)
        traceCollector?.passCodeLocation(tracePoint)
        return false
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @param withTimeout `true` if is invoked with timeout, `false` otherwise.
     * @return whether park should be executed
     */
    @Suppress("UNUSED_PARAMETER")
    internal fun beforePark(iThread: Int, codeLocation: Int, tracePoint: ParkTracePoint?, withTimeout: Boolean): Boolean {
        newSwitchPoint(iThread, codeLocation, tracePoint)
        return false
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    @Suppress("UNUSED_PARAMETER")
    internal fun afterUnpark(iThread: Int, codeLocation: Int, tracePoint: UnparkTracePoint?, thread: Any) {
        if (!isTestThread(iThread)) return
        traceCollector?.passCodeLocation(tracePoint)
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @param withTimeout `true` if is invoked with timeout, `false` otherwise.
     * @return whether `Object.wait` should be executed
     */
    internal fun beforeWait(iThread: Int, codeLocation: Int, tracePoint: WaitTracePoint?, monitor: Any, withTimeout: Boolean): Boolean {
        if (!isTestThread(iThread)) return true
        if (inIgnoredSection(iThread)) return false
        newSwitchPoint(iThread, codeLocation, tracePoint)
        failIfObstructionFreedomIsRequired {
            OBSTRUCTION_FREEDOM_WAIT_VIOLATION_MESSAGE
        }
        if (withTimeout) return false // timeouts occur instantly
        while (monitorTracker.waitOnMonitor(iThread, monitor)) {
            val mustSwitch = monitorTracker.isWaiting(iThread)
            switchCurrentThread(iThread, SwitchReason.MONITOR_WAIT, mustSwitch)
        }
        return false
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @return whether `Object.notify` should be executed
     */
    internal fun beforeNotify(iThread: Int, codeLocation: Int, tracePoint: NotifyTracePoint?, monitor: Any, notifyAll: Boolean): Boolean {
        if (!isTestThread(iThread)) return true
        if (notifyAll)
            monitorTracker.notifyAll(monitor)
        else
            monitorTracker.notify(monitor)
        traceCollector?.passCodeLocation(tracePoint)
        return false
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
     * @param iThread number of invoking thread
     */
    internal fun afterCoroutineResumed(iThread: Int) {
        check(currentThread == iThread)
        isSuspended[iThread] = false
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was cancelled.
     * @param iThread number of invoking thread
     */
    internal fun afterCoroutineCancelled(iThread: Int) {
        check(currentThread == iThread)
        isSuspended[iThread] = false
        // method will not be resumed after suspension, so clear prepared for resume call stack
        suspendedFunctionsStack[iThread].clear()
    }

    /**
     * This method is invoked by a test thread
     * before each ignored section start.
     * These sections are determined by Strategy.ignoredEntryPoints()
     * @param iThread number of invoking thread
     */
    internal fun enterIgnoredSection(iThread: Int = currentThread) {
        if (isTestThread(iThread))
            ignoredSectionDepth[iThread]++
    }

    /**
     * This method is invoked by a test thread
     * after each ignored section end.
     * @param iThread number of invoking thread
     */
    internal fun leaveIgnoredSection(iThread: Int = currentThread) {
        if (isTestThread(iThread))
            ignoredSectionDepth[iThread]--
    }

    /**
     * This method is invoked by a test thread
     * before each method invocation.
     * @param codeLocation the byte-code location identifier of this invocation
     * @param iThread number of invoking thread
     */
    @Suppress("UNUSED_PARAMETER")
    internal fun beforeMethodCall(iThread: Int, codeLocation: Int, tracePoint: MethodCallTracePoint) {
        if (isTestThread(iThread) && !inIgnoredSection(iThread)) {
            check(collectTrace) { "This method should be called only when logging is enabled" }
            val callStackTrace = callStackTrace[iThread]
            val suspendedMethodStack = suspendedFunctionsStack[iThread]
            val methodId = if (suspendedMethodStack.isNotEmpty()) {
                // if there was a suspension before, then instead of creating a new identifier
                // use the one that the suspended call had
                val lastId = suspendedMethodStack.last()
                suspendedMethodStack.removeAt(suspendedMethodStack.lastIndex)
                lastId
            } else {
                methodCallNumber++
            }
            // code location of the new method call is currently the last
            callStackTrace.add(CallStackTraceElement(tracePoint, methodId))
        }
    }

    /**
     * This method is invoked by a test thread
     * after each method invocation.
     * @param iThread number of invoking thread
     * @param tracePoint the corresponding trace point for the invocation
     */
    internal fun afterMethodCall(iThread: Int, tracePoint: MethodCallTracePoint) {
        if (isTestThread(iThread) && !inIgnoredSection(iThread)) {
            check(collectTrace) { "This method should be called only when logging is enabled" }
            val callStackTrace = callStackTrace[iThread]
            if (tracePoint.wasSuspended) {
                // if a method call is suspended, save its identifier to reuse for continuation resuming
                suspendedFunctionsStack[iThread].add(callStackTrace.last().identifier)
            }
            callStackTrace.removeAt(callStackTrace.lastIndex)
        }
    }

    // == LOGGING METHODS ==

    /**
     * Creates a new [TracePoint] for a visited code location.
     * The type of the code location is defined by the used constructor.
     * This method's invocations are inserted by transformer at each code location.
     * @param constructorId which constructor to use for creating code location
     * @return the created interleaving point
     */
    fun createTracePoint(constructorId: Int): TracePoint = doCreateTracePoint(tracePointConstructors[constructorId])

    /**
     * Creates a new [CoroutineCancellationTracePoint].
     * This method is similar to [createTracePoint] method, but also adds the new trace point to the trace.
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
        val iThread = currentThreadNumber()
        val actorId = currentActorId.getOrElse(iThread) { Int.MIN_VALUE }
        return constructor(iThread, actorId, callStackTrace.getOrNull(iThread)?.toList() ?: emptyList())
    }

    /**
     * Creates a state representation and logs it.
     * This method invocations are inserted by transformer
     * after each write operation and atomic method invocation.
     */
    fun addStateRepresentation(iThread: Int) {
        if (!inIgnoredSection(iThread)) {
            check(collectTrace) { "This method should be called only when logging is enabled" }
            traceCollector?.addStateRepresentation(iThread)
        }
    }

    // == UTILITY METHODS ==

    /**
     * This method is invoked by transformed via [ManagedStrategyTransformer] code,
     * it helps to determine the number of thread we are executing on.
     *
     * @return the number of the current thread according to the [execution scenario][ExecutionScenario].
     */
    fun currentThreadNumber(): Int {
        val t = Thread.currentThread()
        return if (t is FixedActiveThreadsExecutor.TestThread) {
            t.iThread
        } else {
            nThreads
        }
    }

    /**
     * Logs thread events such as thread switches and passed code locations.
     */
    private inner class TraceCollector {
        private val _trace = mutableListOf<TracePoint>()
        val trace: List<TracePoint> = _trace

        fun newSwitch(iThread: Int, reason: SwitchReason) {
            _trace += SwitchEventTracePoint(iThread, currentActorId[iThread], reason, callStackTrace[iThread].toList())
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
            if (tracePoint != null) _trace += tracePoint
        }

        fun addStateRepresentation(iThread: Int) {
            val stateRepresentation = runner.constructStateRepresentation()!!
            // use call stack trace of the previous trace point
            val callStackTrace = _trace.last().callStackTrace.toList()
            _trace += StateRepresentationTracePoint(iThread, currentActorId[iThread], stateRepresentation, callStackTrace)

        }

        fun passObstructionFreedomViolationTracePoint(iThread: Int) {
            _trace += ObstructionFreedomViolationExecutionAbortTracePoint(iThread, currentActorId[iThread], _trace.last().callStackTrace)
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
     * - Execution is halted after the last execution in thread 0 using [ForcibleExecutionFinishException].
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
                throw ForcibleExecutionFinishException
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
private class ManagedStrategyRunner(
    private val managedStrategy: ManagedStrategy, testClass: Class<*>, validationFunctions: List<Method>,
    stateRepresentationMethod: Method?, timeoutMs: Long, useClocks: UseClocks
) : ParallelThreadsRunner(managedStrategy, testClass, validationFunctions, stateRepresentationMethod, timeoutMs, useClocks) {
    override fun onStart(iThread: Int) {
        super.onStart(iThread)
        managedStrategy.onStart(iThread)
    }

    override fun onFinish(iThread: Int) {
        managedStrategy.onFinish(iThread)
        super.onFinish(iThread)
    }

    override fun onFailure(iThread: Int, e: Throwable) {
        managedStrategy.onFailure(iThread, e)
        super.onFailure(iThread, e)
    }

    override fun afterCoroutineSuspended(iThread: Int) {
        super.afterCoroutineSuspended(iThread)
        managedStrategy.afterCoroutineSuspended(iThread)
    }

    override fun afterCoroutineResumed(iThread: Int) {
        super.afterCoroutineResumed(iThread)
        managedStrategy.afterCoroutineResumed(iThread)
    }

    override fun afterCoroutineCancelled(iThread: Int) {
        super.afterCoroutineCancelled(iThread)
        managedStrategy.afterCoroutineCancelled(iThread)
    }

    override fun constructStateRepresentation(): String? {
        // Enter ignored section, because Runner will call transformed state representation method
        val iThread = managedStrategy.currentThreadNumber()
        managedStrategy.enterIgnoredSection(iThread)
        val stateRepresentation = super.constructStateRepresentation()
        managedStrategy.leaveIgnoredSection(iThread)
        return stateRepresentation
    }

    override fun <T> cancelByLincheck(cont: CancellableContinuation<T>, promptCancellation: Boolean): CancellationResult {
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
                managedStrategy.afterCoroutineCancelled(managedStrategy.currentThreadNumber())
            return cancellationResult
        } catch(e: Throwable) {
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
internal object ForcibleExecutionFinishException : RuntimeException() {
    // do not create a stack trace -- it simply can be unsafe
    override fun fillInStackTrace() = this
}

private const val COROUTINE_SUSPENSION_CODE_LOCATION = -1 // currently the exact place of coroutine suspension is not known

private const val SPINNING_LOOP_ITERATIONS_BEFORE_YIELD = 100_000

private const val OBSTRUCTION_FREEDOM_SPINLOCK_VIOLATION_MESSAGE =
    "The algorithm should be non-blocking, but an active lock is detected"

private const val OBSTRUCTION_FREEDOM_LOCK_VIOLATION_MESSAGE =
    "The algorithm should be non-blocking, but a lock is detected"

private const val OBSTRUCTION_FREEDOM_WAIT_VIOLATION_MESSAGE =
    "The algorithm should be non-blocking, but a wait call is detected"