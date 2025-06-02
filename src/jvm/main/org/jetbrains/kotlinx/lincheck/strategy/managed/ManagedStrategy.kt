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

import sun.nio.ch.lincheck.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.CancellationResult.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.runner.ExecutionPart.*
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicFieldUpdaterNames.getAtomicFieldUpdaterDescriptor
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicReferenceMethodType.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.UnsafeName.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.VarHandleMethodType.*
import org.jetbrains.kotlinx.lincheck.strategy.native_calls.*
import org.jetbrains.kotlinx.lincheck.beforeEvent as ideaPluginBeforeEvent
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.jetbrains.kotlinx.lincheck.trace.*
import org.jetbrains.kotlinx.lincheck.util.*
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import java.lang.invoke.CallSite
import java.lang.reflect.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellableContinuation
import org.jetbrains.lincheck.GeneralPurposeModelCheckingWrapper
import org.jetbrains.kotlinx.lincheck.traceagent.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck.tracedata.CodeLocations
import org.jetbrains.kotlinx.lincheck.tracedata.FieldDescriptor
import org.jetbrains.kotlinx.lincheck.tracedata.TRACE_CONTEXT
import org.jetbrains.kotlinx.lincheck.tracedata.Types
import org.jetbrains.kotlinx.lincheck.util.isArraysCopyOfIntrinsic
import org.jetbrains.kotlinx.lincheck.util.isArraysCopyOfRangeIntrinsic
import org.jetbrains.lincheck.datastructures.ManagedStrategyGuarantee
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.Result as KResult
import org.objectweb.asm.commons.Method.getMethod as getAsmMethod

/**
 * This is an abstraction for all managed strategies, which encapsulated
 * the required byte-code transformation and [running][Runner] logic and provides
 * a high-level level interface to implement the strategy logic.
 *
 * It is worth noting that here we also solve all the transformation
 * and class loading problems.
 */
internal abstract class ManagedStrategy(
    private val testClass: Class<*>,
    scenario: ExecutionScenario,
    private val validationFunction: Actor?,
    private val stateRepresentationFunction: Method?,
    internal val settings: ManagedStrategySettings,
) : Strategy(scenario), EventTracker {

    val executionMode: ExecutionMode = when {
        testClass == GeneralPurposeModelCheckingWrapper::class.java -> ExecutionMode.GENERAL_PURPOSE_MODEL_CHECKER
        isInTraceDebuggerMode -> ExecutionMode.TRACE_DEBUGGER
        else -> ExecutionMode.DATA_STRUCTURES
    }

    // The flag to enable IntelliJ IDEA plugin mode
    var inIdeaPluginReplayMode: Boolean = false
        private set

    // The number of parallel threads.
    protected val nThreads: Int = scenario.nThreads

    // Runner for scenario invocations,
    // can be replaced with a new one for trace construction.
    override var runner = createRunner()

    // == EXECUTION CONTROL FIELDS ==

    protected val threadScheduler = ManagedThreadScheduler()

    // Which test threads are suspended?
    private val isSuspended = mutableThreadMapOf<Boolean>()

    // Current actor id for each thread.
    protected val currentActorId = mutableThreadMapOf<Int>()

    // Detector of loops or hangs (i.e. active locks).
    internal val loopDetector: LoopDetector = LoopDetector(settings.hangingDetectionThreshold)

    // Tracker of objects' allocations and object graph topology.
    protected abstract val objectTracker: ObjectTracker
    // Tracker of objects' identity hash codes.
    private val identityHashCodeTracker = ObjectIdentityHashCodeTracker()
    // Tracker of native method call states.
    private val nativeMethodCallStatesTracker = NativeMethodCallStatesTracker()

    internal val traceDebuggerEventTrackers: Map<TraceDebuggerTracker, AbstractTraceDebuggerEventTracker> = mapOf(
        TraceDebuggerTracker.IdentityHashCode to identityHashCodeTracker,
        TraceDebuggerTracker.NativeMethodCall to nativeMethodCallStatesTracker,
    )

    // Cache for evaluated invoke dynamic call sites
    private val invokeDynamicCallSites = ConcurrentHashMap<ConstantDynamic, CallSite>()
    // Tracker of the monitors' operations.
    protected abstract val monitorTracker: MonitorTracker
    // Tracker of the thread parking.
    protected abstract val parkingTracker: ParkingTracker

    // Snapshot of the memory, which will be restored between invocations
    protected val memorySnapshot = SnapshotTracker().apply {
        if (isGeneralPurposeModelCheckingScenario(scenario)) {
            // save fields referenced by lambda for restoring by the snapshot tracker
            val actor = scenario.parallelExecution.getOrNull(0)?.getOrNull(0)
            val lambdaBlock = actor?.arguments?.firstOrNull()
            lambdaBlock?.javaClass?.declaredFields
                ?.mapNotNull { readFieldSafely(lambdaBlock, it).getOrNull() }
                ?.forEach(::trackObjectAsRoot)
        }
    }

    // Tracks content of constants (i.e., static final fields).
    // Stores a map `object -> fieldName`,
    // mapping an object to a constant name referencing this object.
    private val constants = IdentityHashMap<Any, String>()

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

    // Random instances with fixed seeds to replace random calls in instrumented code.
    private var randoms = mutableThreadMapOf<InjectedRandom>()

    // User-specified guarantees on specific function, which can be considered as atomic or ignored.
    private val userDefinedGuarantees: List<ManagedStrategyGuarantee>? = settings.guarantees

    // Utility class for the plugin integration to provide ids for each trace point
    private var eventIdProvider = EventIdProvider()

    protected var replayNumber = 0L

    /**
     * For each thread, represents a shadow stack used to reflect the program's actual stack.
     *
     * Collected and used only in the trace collecting stage.
     */
    // TODO: unify with `callStackTrace`
    // TODO: handle coroutine resumptions (i.e., unify with `suspendedFunctionsStack`)
    // TODO: extract into separate class
    private val shadowStack = mutableThreadMapOf<ArrayList<ShadowStackFrame>>()

    /**
     * For each thread, stores a stack of entered analysis sections.
     */
    // TODO: unify with `shadowStack`
    // TODO: handle coroutine resumptions (i.e., unify with `suspendedFunctionsStack`)
    private val analysisSectionStack = mutableThreadMapOf<MutableList<AnalysisSectionType>>()

    /**
     * In case when the plugin is enabled, we also enable [eventIdStrictOrderingCheck] property and check
     * that event ids provided to the [beforeEvent] method
     * and corresponding trace points are sequentially ordered.
     * But we do not add a [MethodCallTracePoint] for the coroutine resumption.
     * So this field just tracks if the last [onMethodCall] invocation was actually a coroutine resumption.
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

    // Symbolizes that the `SpinCycleStartTracePoint` was added into the trace.
    private var spinCycleStartAdded = false
    // Stores the accumulated call stack after the start of spin cycle
    private val spinCycleMethodCallsStackTraces: MutableList<List<CallStackTraceElement>> = mutableListOf()

    internal val analysisProfile: AnalysisProfile = AnalysisProfile(
        analyzeStdLib = settings.analyzeStdLib
    )

    override fun close() {
        super.close()
        closeTraceDebuggerTrackers()
    }

    internal fun resetTraceDebuggerTrackerIds() {
        traceDebuggerEventTrackers.values.forEach { it.resetIds() }
    }

    internal fun closeTraceDebuggerTrackers() {
        traceDebuggerEventTrackers.values.forEach { it.close() }
    }

    // Is first replay within one invocation
    private val isFirstReplay get() = replayNumber == 1L

    private fun createRunner(): ManagedStrategyRunner =
        ManagedStrategyRunner(
            managedStrategy = this,
            testClass = testClass,
            validationFunction = validationFunction,
            stateRepresentationMethod = stateRepresentationFunction,
            timeoutMs = getTimeOutMs(this, settings.timeoutMs),
            useClocks = UseClocks.ALWAYS
        )

    // == STRATEGY INTERFACE METHODS ==

    /**
     * This method is invoked before every thread context switch.
     * @param iThread current thread that is about to be switched
     */
    protected open fun onSwitchPoint(iThread: Int) {}

    /**
     * Returns whether thread should switch at the switch point.
     */
    protected abstract fun shouldSwitch(): Boolean

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
        loopDetector.reset()
        objectTracker.reset()
        monitorTracker.reset()
        parkingTracker.reset()
        constants.clear()
        resetThreads()
    }

    /**
     * Restores recorded values of memory snapshot.
     */
    internal fun restoreMemorySnapshot() {
        memorySnapshot.restoreValues()
    }

    /**
     * Runs the current invocation.
     */
    override fun runInvocation(): InvocationResult {
        initializeInvocation()
        val result: InvocationResult = try {
            runner.run()
        } finally {
            restoreMemorySnapshot()
        }
        // In case the runner detects a deadlock, some threads can still manipulate the current strategy,
        // so we're not interested in suddenInvocationResult in this case
        // and immediately return RunnerTimeoutInvocationResult.
        if (result is RunnerTimeoutInvocationResult) {
            return result
        }
        // If strategy has not detected a sudden invocation result,
        // then return, otherwise process the sudden result.
        val suddenResult = suddenInvocationResult ?: return result
        // Check if an invocation replay is required
        val isReplayRequired = (suddenResult is SpinCycleFoundAndReplayRequired)
        if (isReplayRequired) {
            enableSpinCycleReplay()
            // TODO: returning here instead of continuing in a cycle leads to an issue:
            //  see https://github.com/JetBrains/lincheck/issues/590
            return suddenResult
        }
        // Otherwise return the sudden result
        return suddenResult
    }

    protected open fun enableSpinCycleReplay() {}

    protected open fun initializeReplay() {
        resetTraceDebuggerTrackerIds()
        resetEventIdProvider()
    }

    internal fun doReplay(): InvocationResult {
        initializeReplay()
        return runInvocation()
    }

    // == BASIC STRATEGY METHODS ==

    override fun beforePart(part: ExecutionPart) = runInsideIgnoredSection {
        traceCollector?.addTracePointInternal(SectionDelimiterTracePoint(part))
        val nextThread = when (part) {
            INIT        -> 0
            PARALLEL    -> {
                // initialize artificial switch point to choose among available threads
                onSwitchPoint(iThread = -1)
                chooseThread(iThread = -1)
            }
            POST        -> 0
            VALIDATION  -> 0
        }
        loopDetector.beforePart(part, nextThread)
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
        resetTraceDebuggerTrackerIds()

        runner.close()
        runner = createRunner()

        val loggedResults = runInvocation()
        // In case the runner detects a deadlock, some threads can still be in an active state,
        // simultaneously adding events to the TraceCollector, which leads to an inconsistent trace.
        // Therefore, if the runner detects deadlock, we don't even try to collect trace.
        if (loggedResults is RunnerTimeoutInvocationResult) return null

        val threadNames = MutableList<String>(threadScheduler.nThreads) { "" }
        getRegisteredThreads().forEach { (threadId, thread) ->
            val threadNumber = objectTracker.getObjectDisplayNumber(thread)
            when (threadNumber) {
                0 -> threadNames[threadId] = "Main Thread"
                else -> threadNames[threadId] = "Thread $threadNumber"
            }
        }
        val trace = Trace(traceCollector!!.trace, threadNames)

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
                appendLine(result.toLincheckFailure(scenario, null, analysisProfile))
                appendLine("== Reporting the second execution ==")
                appendLine(loggedResults.toLincheckFailure(scenario, trace, analysisProfile).toString())
            }.toString()
        }

        return trace
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
        if (settings.checkObstructionFreedom && !currentActorIsBlocking && !concurrentActorCausesBlocking) {
            failDueToLivelock(lazyMessage)
        }
    }

    private val currentActorIsBlocking: Boolean get() {
        val threadId = threadScheduler.getCurrentThreadId()
        val actorId = currentActorId[threadId] ?: -1
        // Handle the case when the first actor has not yet started,
        // see https://github.com/JetBrains/lincheck/pull/277
        if (actorId < 0) return false
        val currentActor = scenario.threads[threadId].getOrNull(actorId)
        return currentActor?.blocking ?: false
    }

    private val concurrentActorCausesBlocking: Boolean get() {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        val currentActiveActorIds = currentActorId.values.mapIndexed { iThread, actorId ->
            if (iThread != currentThreadId && actorId >= 0 && !threadScheduler.isFinished(iThread)) {
                scenario.threads[iThread].getOrNull(actorId)
            } else null
        }.filterNotNull()
        return currentActiveActorIds.any { it.causesBlocking }
    }


    // == THREAD SCHEDULING METHODS ==

    /**
     * Create a new switch point, where a thread context switch can occur.
     * @param threadId the current thread id.
     * @param codeLocation the byte-code location identifier of the point in code.
     */
    private fun newSwitchPoint(threadId: Int, codeLocation: Int, beforeMethodCallSwitch: Boolean = false) {
        // re-throw abort error if the thread was aborted
        if (threadScheduler.isAborted(threadId)) {
            threadScheduler.abortCurrentThread()
        }
        // check we are in the right thread
        check(threadId == threadScheduler.scheduledThreadId)
        // check if live-lock is detected
        val decision = loopDetector.visitCodeLocation(threadId, codeLocation)
        if (decision != LoopDetector.Decision.Idle) {
            processLoopDetectorDecision(threadId, codeLocation, decision, beforeMethodCallSwitch = beforeMethodCallSwitch)
            loopDetector.afterCodeLocation(codeLocation)
            return
        }
        // check if we need to switch
        val shouldSwitch = when {
            // check if a switch is required by the loop detector replay mode
            loopDetector.replayModeEnabled -> loopDetector.shouldSwitch()
            // do not make thread switches inside a silent section
            inSilentSection(threadId) -> false
            // otherwise, follow the strategy decision
            else -> {
                // inform strategy that we reached new execution position
                onSwitchPoint(threadId)
                shouldSwitch()
            }
        }
        // if strategy requested thread switch, then do it
        if (shouldSwitch) {
            val switchHappened = switchCurrentThread(threadId, beforeMethodCallSwitch = beforeMethodCallSwitch)
            if (switchHappened) {
                loopDetector.afterThreadSwitch(codeLocation)
            }
        }
        loopDetector.afterCodeLocation(codeLocation)
    }

    private fun processLoopDetectorDecision(
        iThread: Int,
        codeLocation: Int,
        decision: LoopDetector.Decision,
        beforeMethodCallSwitch: Boolean = false,
    ) {
        check(decision != LoopDetector.Decision.Idle)
        // if we reached maximum number of events threshold, then fail immediately
        if (decision == LoopDetector.Decision.EventsThresholdReached) {
            failDueToDeadlock()
        }
        // if any kind of live-lock was detected, check for obstruction-freedom violation
        if (decision.isLivelockDetected) {
            failIfObstructionFreedomIsRequired {
                traceCollector?.passObstructionFreedomViolationTracePoint(
                    beforeMethodCall = beforeMethodCallSwitch
                )
                OBSTRUCTION_FREEDOM_SPINLOCK_VIOLATION_MESSAGE
            }
        }
        // if live-lock failure was detected, then fail immediately
        if (decision is LoopDetector.Decision.LivelockFailureDetected) {
            traceCollector?.newSwitch(
                SwitchReason.ActiveLock,
                beforeMethodCallSwitch = beforeMethodCallSwitch
            )
            failDueToDeadlock()
        }
        // if live-lock was detected, and replay was requested,
        // then abort current execution and start the replay
        if (decision.isReplayRequired) {
            abortWithSuddenInvocationResult(SpinCycleFoundAndReplayRequired)
        }
        // if the current thread in a live-lock, then try to switch to another thread
        if (decision is LoopDetector.Decision.LivelockThreadSwitch) {
            // in case of live-lock, try to abort execution
            tryAbortingUserThreads(iThread, BlockingReason.LiveLocked)
            onSwitchPoint(iThread)
            val switchHappened = switchCurrentThread(iThread, BlockingReason.LiveLocked,
                beforeMethodCallSwitch = beforeMethodCallSwitch
            )
            if (switchHappened) {
                loopDetector.afterThreadSwitch(codeLocation)
            }
        }
    }

    /**
     * Returns whether in test thread [iThread] coroutine has been suspended but not yet resumed.
     */
    private fun isTestThreadCoroutineSuspended(iThread: Int): Boolean =
        (
            isTestThread(iThread) &&
            // TODO: coroutine suspensions are currently handled separately from `ThreadScheduler`
            isSuspended[iThread]!! &&
            !runner.isCoroutineResumed(iThread, currentActorId[iThread]!!)
        )

    /**
     * Returns whether the specified thread is active and
     * can continue its execution (i.e., is not blocked/finished).
     */
    private fun isActive(iThread: Int): Boolean =
        threadScheduler.isSchedulable(iThread) && !isTestThreadCoroutineSuspended(iThread)

    /**
     * A regular thread switch to another thread.
     *
     * @return true if this thread actually switched to another thread, false otherwise.
     */
    private fun switchCurrentThread(
        iThread: Int,
        blockingReason: BlockingReason? = null,
        beforeMethodCallSwitch: Boolean = false,
    ): Boolean {
        val switchReason = blockingReason.toSwitchReason(::getThreadDisplayNumber)
        // we create switch point on detected live-locks,
        // but that switch is not mandatory in case if there are no available threads
        val mustSwitch = (blockingReason != null) && (blockingReason !is BlockingReason.LiveLocked)
        val nextThread = chooseThreadSwitch(iThread, mustSwitch)
        val switchHappened = (iThread != nextThread)
        if (switchHappened) {
            if (blockingReason != null &&
                // TODO: coroutine suspensions are currently handled separately from `ThreadScheduler`
                blockingReason !is BlockingReason.Suspended
            ) {
                blockThread(iThread, blockingReason)
            }
            traceCollector?.newSwitch(
                switchReason,
                beforeMethodCallSwitch
            )
            setCurrentThread(nextThread)
        }
        threadScheduler.awaitTurn(iThread)
        return switchHappened
    }

    private fun chooseThreadSwitch(iThread: Int, mustSwitch: Boolean = false): Int {
        // in case threads were aborted via `tryAbortingUserThreads`, no switching required
        if (threadScheduler.areAllThreadsFinishedOrAborted()) {
            return iThread
        }
        if (inIdeaPluginReplayMode && collectTrace) {
            onThreadSwitchesOrActorFinishes()
        }
        // do the switch if there is an available thread
        val nextThread = chooseThread(iThread)
        if (nextThread != -1) {
            // in case we resume live-locked thread, we need to unblock it manually
            if (threadScheduler.isLiveLocked(nextThread)) {
                threadScheduler.unblockThread(nextThread)
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
        failDueToDeadlock()
    }

    @JvmName("setNextThread")
    private fun setCurrentThread(nextThread: Int) {
        loopDetector.beforeThreadSwitch(nextThread)
        threadScheduler.scheduleThread(nextThread)
    }

    private fun throwIfInterrupted() {
        if (Thread.interrupted()) {
            throw InterruptedException()
        }
    }

    /**
     * Iterates through all blocked threads and unblocks each thread
     * that was interrupted (i.e., the interrupted flag is set), and
     * its blocking reason is interruptible.
     * Also, notifies the respective tracker about interruption.
     */
    protected fun unblockInterruptedThreads() {
        for ((threadId, thread) in getRegisteredThreads()) {
            if (threadScheduler.isBlocked(threadId) && thread.isInterrupted) {
                val blockingReason = threadScheduler.getBlockingReason(threadId)
                if (blockingReason != null && blockingReason.isInterruptible()) {
                    threadScheduler.unblockThread(threadId)
                    when (blockingReason) {
                        is BlockingReason.Parked -> {
                            parkingTracker.interruptPark(threadId)
                        }
                        is BlockingReason.Waiting -> {
                            monitorTracker.interruptWait(threadId)
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun abortWithSuddenInvocationResult(invocationResult: InvocationResult): Nothing {
        suddenInvocationResult = invocationResult
        threadScheduler.abortOtherThreads()
        threadScheduler.abortCurrentThread()
    }

    /**
     * Threads to which an execution can be switched from thread [iThread].
     */
    private fun switchableThreads(iThread: Int) =
        if (runner.currentExecutionPart == PARALLEL) {
            (0 until threadScheduler.nThreads).filter { it != iThread && isActive(it) }
        } else {
            emptyList()
        }

    /**
     * Threads which can be resumed when no `switchableThread` left.
     *
     * Right now only live-locked threads are considered to be resumable.
     *
     * TODO: somehow refactor suspended TestThread and add them here as well.
     */
    private fun resumableThreads(iThread: Int): List<Int> =
        if (runner.currentExecutionPart == PARALLEL) {
            (0 until threadScheduler.nThreads).filter { it != iThread && threadScheduler.isLiveLocked(it) }
        } else {
            emptyList()
        }

    /**
     * Returns threads that could be switched to directly from thread [iThread]
     * or those that could be resumed in case there are no direct options.
     */
    protected fun availableThreads(iThread: Int): List<Int> {
        val threads = switchableThreads(iThread)
        return if (threads.isEmpty()) resumableThreads(iThread)
               else threads
    }

    /**
     * Converts lincheck threadId to a displayable thread number for the trace.
     */
    private fun getThreadDisplayNumber(iThread: Int): Int =
        threadScheduler.getThread(iThread)?.let { objectTracker.getObjectDisplayNumber(it) } ?: -1

    // == LISTENING METHODS ==

    override fun beforeThreadFork(thread: Thread, descriptor: ThreadDescriptor) = runInsideIgnoredSection {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        // do not track threads forked from unregistered threads
        if (currentThreadId < 0) return
        // scenario threads are handled separately by the runner itself
        if (thread is TestThread) return
        val forkedThreadId = registerThread(thread, descriptor)
        if (collectTrace) {
            val tracePoint = ThreadStartTracePoint(
                iThread = currentThreadId,
                actorId = currentActorId[currentThreadId]!!,
                startedThreadDisplayNumber = getThreadDisplayNumber(forkedThreadId),
                callStackTrace = callStackTrace[currentThreadId]!!,
            )
            traceCollector!!.addTracePointInternal(tracePoint)
        }
    }

    override fun beforeThreadStart() = runInsideIgnoredSection {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        // do not track unregistered threads
        if (currentThreadId < 0) return
        // scenario threads are handled separately
        if (isTestThread(currentThreadId)) return

        val methodDescriptor = getAsmMethod("void run()").descriptor
        addBeforeMethodCallTracePoint(
            owner = runner.testInstance,
            className = "java.lang.Thread",
            methodName = "run",
            codeLocation = UNKNOWN_CODE_LOCATION,
            methodId = TRACE_CONTEXT.getOrCreateMethodId(
                className = "java.lang.Thread",
                methodName = "run",
                desc = methodDescriptor
            ),
            threadId = currentThreadId,
            methodParams = emptyArray(),
            atomicMethodDescriptor = null,
            callType = MethodCallTracePoint.CallType.THREAD_RUN
        )
        onThreadStart(currentThreadId)
        enableAnalysis()
    }

    override fun afterThreadFinish() = runInsideIgnoredSection {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        // do not track unregistered threads
        if (currentThreadId < 0) return
        // scenario threads are handled separately by the runner itself
        if (isTestThread(currentThreadId)) return
        disableAnalysis()
        onThreadFinish(currentThreadId)
    }

    /**
     * Handles exceptions that occur in a specific thread.
     * This method is called when a thread finishes with an exception.
     *
     * @param exception The exception that was thrown within the thread.
     */
    override fun onThreadRunException(exception: Throwable) = runInsideIgnoredSection {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        // do not track unregistered threads
        if (currentThreadId < 0) return
        // scenario threads are handled separately by the runner itself
        if (isTestThread(currentThreadId)) return
        // check if the exception is internal
        if (isInternalException(exception)) {
            onInternalException(currentThreadId, exception)
        } else {
            // re-throw any non-internal exception,
            // so it will be treated as the final result of `Thread::run`.
            disableAnalysis()
            Logger.info { "Exception was thrown in user thread \"Thread-$currentThreadId\":" }
            Logger.info(exception)
            // the current thread will not be put in the ABORTED state,
            // like in the ` onInternalException ` case,
            // thus, it is still executed in isolation, and we can finish it properly
            onThreadFinish(currentThreadId)
            throw exception
        }
    }

    override fun threadJoin(thread: Thread?, withTimeout: Boolean) = runInsideIgnoredSection {
        if (withTimeout) return // timeouts occur instantly
        val currentThreadId = threadScheduler.getCurrentThreadId()
        val joinThreadId = threadScheduler.getThreadId(thread!!)
        while (threadScheduler.getThreadState(joinThreadId) != ThreadState.FINISHED) {
            throwIfInterrupted()
            // TODO: should wait on thread-join be considered an obstruction-freedom violation?
            onSwitchPoint(currentThreadId)
            // Switch to another thread and wait for a moment when the thread is finished
            switchCurrentThread(currentThreadId, BlockingReason.ThreadJoin(joinThreadId))
        }
        if (collectTrace) {
            val tracePoint = ThreadJoinTracePoint(
                iThread = currentThreadId,
                actorId = currentActorId[currentThreadId]!!,
                joinedThreadDisplayNumber = getThreadDisplayNumber(joinThreadId),
                callStackTrace = callStackTrace[currentThreadId]!!,
            )
            traceCollector!!.addTracePointInternal(tracePoint)
        }
    }

    fun registerThread(thread: Thread, descriptor: ThreadDescriptor): ThreadId {
        val threadId = threadScheduler.registerThread(thread, descriptor)
        isSuspended[threadId] = false
        currentActorId[threadId] = if (isTestThread(threadId)) -1 else 0
        callStackTrace[threadId] = mutableListOf()
        suspendedFunctionsStack[threadId] = mutableListOf()
        shadowStack[threadId] = arrayListOf(ShadowStackFrame(runner.testInstance))
        analysisSectionStack[threadId] = arrayListOf()
        randoms[threadId] = InjectedRandom(threadId + 239L)
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
        shadowStack.clear()
        analysisSectionStack.clear()
        randoms.clear()
    }

    override fun awaitUserThreads(timeoutNano: Long): Long {
        var remainingTime = timeoutNano
        for ((threadId, _) in getRegisteredThreads()) {
            if (isTestThread(threadId)) continue // do not wait for Lincheck threads
            val elapsedTime = threadScheduler.awaitThreadFinish(threadId, remainingTime)
            if (elapsedTime < 0) {
                remainingTime = -1
                break
            }
            remainingTime -= elapsedTime
        }
        if (remainingTime < 0) throw TimeoutException()
        val elapsedTime = timeoutNano - remainingTime
        return elapsedTime
    }

    fun getRegisteredThreads(): ThreadMap<Thread> =
        threadScheduler.getRegisteredThreads()

    fun getUserThreadIds() = getRegisteredThreads().mapNotNull {
        if (isTestThread(it.key)) null
        else it.key
    }

    protected fun isRegisteredThread(): Boolean {
        val threadDescriptor = ThreadDescriptor.getCurrentThreadDescriptor()
            ?: return false
        return (threadDescriptor.eventTracker === this)
    }

    /**
     * Aborts all threads in case if all `TestThread`s are in state `FINISHED`
     * and all running user threads are `LiveLocked` or `Parked`.
     *
     * This method is expected to be called before [onSwitchPoint] method in
     * order to abort execution and not to create unnecessary switch points.
     *
     * Since this method only can abort executions with `LiveLocked` or `Parked`
     * user-threads and `Finished` test threads, then invocations of this method are
     * only meaningful near the corresponding strategy hooks
     * ([ManagedStrategy.processLoopDetectorDecision], [ManagedStrategy.park],
     * and [ManagedStrategy.onThreadFinish] where potentially test thread could finish).
     *
     * @param threadId id of thread that invoked this method.
     * @param blockingReason blocking reason of invoking thread (determined by strategy) if exists.
     */
    private fun tryAbortingUserThreads(threadId: Int, blockingReason: BlockingReason?) {
        val userThreadsAbortionPossible =
            // all `TestThread`s are finished (including main: with id of zero)
            (0 ..< scenario.nThreads).all(threadScheduler::isFinished) &&
            // The main thread finished its execution (actually all `TestThread`s did): successfully or not, we don't care.
            // If all user threads (those that are not `TestThread` instances) are not blocked, then abort
            // the running user threads. Essentially treating them as "daemons", which completion we do not wait for.
            // Thus, abort all of them and allow `runner` to process the invocation result accordingly.
            getUserThreadIds()
                .filter { it != threadId } // we check invoking thread separately
                .all { threadScheduler.isLiveLocked(it) || threadScheduler.isParked(it) }
                .and(
                    threadScheduler.isFinished(threadId) ||
                    // if invoking thread is not finished, then it must execute strategy hook,
                    // in which it will become LiveLocked or Parked
                    blockingReason is BlockingReason.LiveLocked ||
                    blockingReason is BlockingReason.Parked
                )

        if (userThreadsAbortionPossible) {
            threadScheduler.abortAllThreads()
        }
    }

    /**
     * This method is executed as the first thread action.
     *
     * @param threadId the thread id of the started thread.
     */
    open fun onThreadStart(threadId: ThreadId) {
        threadScheduler.awaitTurn(threadId)
        threadScheduler.startThread(threadId)
    }

    /**
     * This method is executed as the last thread action.
     *
     * @param threadId the thread id of the finished thread.
     */
    open fun onThreadFinish(threadId: ThreadId) {
        threadScheduler.awaitTurn(threadId)
        threadScheduler.finishThread(threadId)
        loopDetector.onThreadFinish(threadId)
        traceCollector?.onThreadFinish()
        unblockJoiningThreads(threadId)
        tryAbortingUserThreads(threadId, blockingReason = null)
        onSwitchPoint(threadId)
        val nextThread = chooseThreadSwitch(threadId, true)
        setCurrentThread(nextThread)
    }

    /**
     * This method is executed if an internal exception has been thrown (see [isInternalException]).
     *
     * @param threadId the thread id of the thread where exception was thrown.
     * @param exception the exception that was thrown.
     */
    open fun onInternalException(threadId: Int, exception: Throwable) {
        check(isInternalException(exception))
        // This method is called only if the exception cannot be treated as a normal result,
        // so we exit testing code to avoid trace collection resume or some bizarre bugs
        disableAnalysis()
        // suppress `ThreadAbortedError`
        if (exception is LincheckAnalysisAbortedError) return
        // Though the corresponding failure will be detected by the runner,
        // the managed strategy can construct a trace to reproduce this failure.
        // Let's then store the corresponding failing result and construct the trace.
        suddenInvocationResult = UnexpectedExceptionInvocationResult(
            exception,
            runner.collectExecutionResults()
        )
        threadScheduler.abortAllThreads()
        throw exception
    }

    override fun onActorStart(iThread: Int) {
        val actorId = 1 + currentActorId[iThread]!!
        currentActorId[iThread] = actorId
        callStackTrace[iThread]!!.clear()
        suspendedFunctionsStack[iThread]!!.clear()
        loopDetector.onActorStart(iThread)

        val actor = if (actorId < scenario.threads[iThread].size) scenario.threads[iThread][actorId]
        else validationFunction
        check(actor != null) { "Could not find current actor" }

        val methodDescriptor = getAsmMethod(actor.method).descriptor
        addBeforeMethodCallTracePoint(
            owner = runner.testInstance,
            // TODO Setting this to "" somehow fixes failing MulitpleSpension....TraceRepresentationTest
            className = actor.method.declaringClass.name,
            methodName = actor.method.name,
            codeLocation = UNKNOWN_CODE_LOCATION,
            methodId = TRACE_CONTEXT.getOrCreateMethodId(
                className = actor.method.declaringClass.name.toCanonicalClassName(),
                methodName = actor.method.name,
                desc = methodDescriptor
            ),
            threadId = iThread,
            methodParams = actor.arguments.toTypedArray(),
            atomicMethodDescriptor = null,
            callType = MethodCallTracePoint.CallType.ACTOR,
        )
        traceCollector?.addTracePointInternal(callStackTrace[iThread]!!.first().tracePoint)
        enableAnalysis()
    }

    override fun onActorFinish() {
        // This is a hack to guarantee correct stepping in the plugin.
        // When stepping out to the TestThreadExecution class, stepping continues unproductively.
        // With this method, we force the debugger to stop at the beginning of the next actor.
        onThreadSwitchesOrActorFinishes()
        disableAnalysis()
    }

    override fun beforeLock(codeLocation: Int): Unit = runInsideIgnoredSection {
        val iThread = threadScheduler.getCurrentThreadId()
        val tracePoint = if (collectTrace) {
            MonitorEnterTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread]!!,
                callStackTrace = callStackTrace[iThread]!!,
                codeLocation = codeLocation
            )
        } else {
            null
        }
        newSwitchPoint(iThread, codeLocation)
        traceCollector?.addTracePointInternal(tracePoint)
    }

    /*
     * TODO: Here Lincheck performs in-optimal switching.
     *
     * Firstly an optional switch point is added before lock,
     * and then adds force switches in case execution cannot continue in this thread.
     * More effective way would be to do force switch in case the thread is blocked
     * (smart order of thread switching is needed),
     * or create a switch point if the switch is really optional.
     *
     * Because of this additional switching we had to split this method into two,
     * as the `beforeEvent` method must be called right after the switch point is created.
     */
    override fun lock(monitor: Any): Unit = runInsideIgnoredSection {
        val iThread = threadScheduler.getCurrentThreadId()
        // Try to acquire the monitor
        while (!monitorTracker.acquireMonitor(iThread, monitor)) {
            onSwitchPoint(iThread)
            // Switch to another thread and wait for a moment when the monitor can be acquired
            switchCurrentThread(iThread, BlockingReason.Locked)
        }
    }

    override fun unlock(monitor: Any, codeLocation: Int): Unit = runInsideIgnoredSection {
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
                codeLocation = codeLocation
            )
            traceCollector!!.addTracePointInternal(tracePoint)
        }
    }

    override fun beforePark(codeLocation: Int): Unit = runInsideIgnoredSection {
        val threadId = threadScheduler.getCurrentThreadId()
        val tracePoint = if (collectTrace) {
            ParkTracePoint(
                iThread = threadId,
                actorId = currentActorId[threadId]!!,
                callStackTrace = callStackTrace[threadId]!!,
                codeLocation = codeLocation
            )
        } else {
            null
        }
        // Instead of fairly supporting the park/unpark semantics,
        // we simply add a new switch point here, thus, also
        // emulating spurious wake-ups.
        newSwitchPoint(threadId, codeLocation)
        traceCollector?.addTracePointInternal(tracePoint)
    }

    /*
     * TODO: Here Lincheck performs in-optimal switching.
     *
     * Firstly an optional switch point is added before park,
     * and then adds force switches in case execution cannot continue in this thread.
     * More effective way would be to do force switch in case the thread is blocked
     * (smart order of thread switching is needed),
     * or create a switch point if the switch is really optional.
     *
     * Because of this additional switching we had to split this method into two,
     * as the `beforeEvent` method must be called right after the switch point is created.
     */
    override fun park(codeLocation: Int): Unit = runInsideIgnoredSection {
        val threadId = threadScheduler.getCurrentThreadId()
        // Do not park and exit immediately if the thread's interrupted flag set.
        if (Thread.currentThread().isInterrupted) return
        // Park otherwise.
        parkingTracker.park(threadId)
        // Forbid spurious wake-ups if inside silent sections.
        val allowSpuriousWakeUp = shouldAllowSpuriousUnpark(threadId, codeLocation)
        while (parkingTracker.waitUnpark(threadId, allowSpuriousWakeUp) && !threadScheduler.areAllThreadsFinishedOrAborted()) {
            tryAbortingUserThreads(threadId, BlockingReason.Parked)
            onSwitchPoint(threadId)
            // Switch to another thread and wait till an unpark event happens.
            switchCurrentThread(threadId, BlockingReason.Parked)
        }
    }

    private fun shouldAllowSpuriousUnpark(threadId: ThreadId, codeLocation: Int): Boolean {
        val stackTraceElement = CodeLocations.stackTrace(codeLocation)
        val analysisSectionStack = this.analysisSectionStack[threadId]!!
        // TODO: refactor, track LockSupport.park directly instead
        val section = if (
            stackTraceElement.className == "java/util/concurrent/locks/LockSupport" &&
            stackTraceElement.methodName == "park"
        ) {
            analysisSectionStack.getOrNull(analysisSectionStack.size - 2)
        } else {
            analysisSectionStack.lastOrNull()
        }
        // allow spurious wake-up, unless inside a silent section
        return !(section != null && section.isSilent())
    }

    override fun unpark(thread: Thread, codeLocation: Int): Unit = runInsideIgnoredSection {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        val unparkedThreadId = threadScheduler.getThreadId(thread)
        parkingTracker.unpark(currentThreadId, unparkedThreadId)
        unblockParkedThread(unparkedThreadId)
        if (collectTrace) {
            val tracePoint = UnparkTracePoint(
                iThread = currentThreadId,
                actorId = currentActorId[currentThreadId]!!,
                callStackTrace = callStackTrace[currentThreadId]!!,
                codeLocation = codeLocation
            )
            traceCollector?.addTracePointInternal(tracePoint)
        }
    }

    override fun beforeWait(codeLocation: Int): Unit = runInsideIgnoredSection {
        val iThread = threadScheduler.getCurrentThreadId()
        val tracePoint = if (collectTrace) {
            WaitTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread]!!,
                callStackTrace = callStackTrace[iThread]!!,
                codeLocation = codeLocation
            )
        } else {
            null
        }
        newSwitchPoint(iThread, codeLocation)
        traceCollector?.addTracePointInternal(tracePoint)
    }

    /*
     * TODO: Here Lincheck performs in-optimal switching.
     *
     * Firstly an optional switch point is added before wait,
     * and then adds force switches in case execution cannot continue in this thread.
     * More effective way would be to do force switch in case the thread is blocked
     * (smart order of thread switching is needed),
     * or create a switch point if the switch is really optional.
     *
     * Because of this additional switching we had to split this method into two,
     * as the `beforeEvent` method must be called right after the switch point is created.
     */
    override fun wait(monitor: Any, withTimeout: Boolean): Unit = runInsideIgnoredSection {
        if (withTimeout) return // timeouts occur instantly
        // we check the interruption flag both before entering `wait` and after,
        // to ensure the monitor is acquired when `InterruptionException` is thrown
        throwIfInterrupted()
        val iThread = threadScheduler.getCurrentThreadId()
        while (monitorTracker.waitOnMonitor(iThread, monitor)) {
            unblockAcquiringThreads(iThread, monitor)
            onSwitchPoint(iThread)
            switchCurrentThread(iThread, BlockingReason.Waiting)
        }
        throwIfInterrupted()
    }

    override fun notify(monitor: Any, codeLocation: Int, notifyAll: Boolean): Unit = runInsideIgnoredSection {
        val iThread = threadScheduler.getCurrentThreadId()
        monitorTracker.notify(iThread, monitor, notifyAll = notifyAll)
        if (collectTrace) {
            val tracePoint = NotifyTracePoint(
                iThread = iThread,
                actorId = currentActorId[iThread]!!,
                callStackTrace = callStackTrace[iThread]!!,
                codeLocation = codeLocation
            )
            traceCollector?.addTracePointInternal(tracePoint)
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
            val blockingReason = threadScheduler.getBlockingReason(threadId)
            if (blockingReason is BlockingReason.ThreadJoin && blockingReason.joinedThreadId == finishedThreadId) {
                threadScheduler.unblockThread(threadId)
            }
        }
    }

    private fun unblockParkedThread(unparkedThreadId: Int) {
        if (threadScheduler.getBlockingReason(unparkedThreadId) == BlockingReason.Parked) {
            threadScheduler.unblockThread(unparkedThreadId)
        }
    }

    private fun unblockAcquiringThreads(iThread: Int, monitor: Any) {
        monitorTracker.acquiringThreads(monitor).forEach { threadId ->
            if (iThread == threadId) return@forEach
            val blockingReason = threadScheduler.getBlockingReason(threadId)?.ensure {
                it == BlockingReason.Locked || it == BlockingReason.Waiting
            }
            // do not wake up thread waiting for notification
            if (blockingReason == BlockingReason.Waiting && monitorTracker.isWaiting(threadId))
                return@forEach
            threadScheduler.unblockThread(threadId)
        }
    }

    /**
     * Returns `true` if a switch point is created.
     */
    override fun beforeReadField(obj: Any?, codeLocation: Int, fieldId: Int): Boolean = runInsideIgnoredSection {
        val fieldDescriptor = TRACE_CONTEXT.getFieldDescriptor(fieldId)
        if (!fieldDescriptor.isStatic && obj == null) {
            // Ignore, NullPointerException will be thrown
            return false
        }
        updateSnapshotOnFieldAccess(obj, fieldDescriptor.className, fieldDescriptor.fieldName)
        // We need to ensure all the classes related to the reading object are instrumented.
        // The following call checks all the static fields.
        if (fieldDescriptor.isStatic) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(fieldDescriptor.className)
        }
        // Do not track accesses to untracked objects
        if (!shouldTrackFieldAccess(obj, fieldDescriptor)) {
            return false
        }
        val iThread = threadScheduler.getCurrentThreadId()
        newSwitchPoint(iThread, codeLocation)
        loopDetector.beforeReadField(obj)
        return true
    }

    /** Returns <code>true</code> if a switch point is created. */
    override fun beforeReadArrayElement(array: Any, index: Int, codeLocation: Int): Boolean = runInsideIgnoredSection {
        updateSnapshotOnArrayElementAccess(array, index)
        if (!shouldTrackArrayAccess(array)) {
            return false
        }
        val iThread = threadScheduler.getCurrentThreadId()
        newSwitchPoint(iThread, codeLocation)
        loopDetector.beforeReadArrayElement(array, index)
        return true
    }

    override fun afterReadField(obj: Any?, codeLocation: Int, fieldId: Int, value: Any?) = runInsideIgnoredSection {
        if (collectTrace) {
            val iThread = threadScheduler.getCurrentThreadId()
            val fieldDescriptor = TRACE_CONTEXT.getFieldDescriptor(fieldId)
            if (value != null) {
                constants[value] = fieldDescriptor.fieldName
            }
            val valueRepresentation = objectTracker.getObjectRepresentation(value)
            val typeRepresentation = objectFqTypeName(value)
            if (shouldTrackFieldAccess(obj, fieldDescriptor)) {
                val tracePoint = ReadTracePoint(
                    ownerRepresentation = getFieldOwnerName(obj, fieldDescriptor),
                    iThread = iThread,
                    actorId = currentActorId[iThread]!!,
                    callStackTrace = callStackTrace[iThread]!!,
                    fieldName = fieldDescriptor.fieldName,
                    codeLocation = codeLocation,
                    isLocal = false,
                    valueRepresentation = valueRepresentation,
                    valueType = typeRepresentation,
                )
                traceCollector?.addTracePointInternal(tracePoint)
            }
        }
        loopDetector.afterRead(value)
    }

    override fun afterReadArrayElement(array: Any, index: Int, codeLocation: Int, value: Any?) = runInsideIgnoredSection {
        if (collectTrace) {
            val iThread = threadScheduler.getCurrentThreadId()
            val valueRepresentation = objectTracker.getObjectRepresentation(value)
            val typeRepresentation = objectFqTypeName(value)
            if (shouldTrackArrayAccess(array)) {
                val tracePoint = ReadTracePoint(
                    ownerRepresentation = null,
                    iThread = iThread,
                    actorId = currentActorId[iThread]!!,
                    callStackTrace = callStackTrace[iThread]!!,
                    fieldName = "${objectTracker.getObjectRepresentation(array)}[$index]",
                    codeLocation = codeLocation,
                    isLocal = false,
                    valueRepresentation = valueRepresentation,
                    valueType = typeRepresentation,
                )
                traceCollector?.addTracePointInternal(tracePoint)
            }
        }
        loopDetector.afterRead(value)
    }

    override fun beforeWriteField(obj: Any?, value: Any?, codeLocation: Int, fieldId: Int): Boolean = runInsideIgnoredSection {
        val fieldDescriptor = TRACE_CONTEXT.getFieldDescriptor(fieldId)
        if (!fieldDescriptor.isStatic && obj == null) {
            // Ignore, NullPointerException will be thrown
            return false
        }
        updateSnapshotOnFieldAccess(obj, fieldDescriptor.className, fieldDescriptor.fieldName)
        objectTracker.registerObjectLink(fromObject = obj, toObject = value)
        if (!shouldTrackFieldAccess(obj, fieldDescriptor)) {
            return false
        }
        val iThread = threadScheduler.getCurrentThreadId()
        val tracePoint = if (collectTrace) {
            WriteTracePoint(
                ownerRepresentation = getFieldOwnerName(obj, fieldDescriptor),
                iThread = iThread,
                actorId = currentActorId[iThread]!!,
                callStackTrace = callStackTrace[iThread]!!,
                fieldName = fieldDescriptor.fieldName,
                codeLocation = codeLocation,
                isLocal = false,
            ).also {
                it.initializeWrittenValue(objectTracker.getObjectRepresentation(value), objectFqTypeName(value))
            }
        } else {
            null
        }
        newSwitchPoint(iThread, codeLocation)
        traceCollector?.addTracePointInternal(tracePoint)
        loopDetector.beforeWriteField(obj, value)
        return true
    }

    override fun beforeWriteArrayElement(array: Any, index: Int, value: Any?, codeLocation: Int): Boolean = runInsideIgnoredSection {
        updateSnapshotOnArrayElementAccess(array, index)
        objectTracker.registerObjectLink(fromObject = array, toObject = value)

        if (!shouldTrackArrayAccess(array)) {
            return false
        }
        val iThread = threadScheduler.getCurrentThreadId()
        val tracePoint = if (collectTrace) {
            WriteTracePoint(
                ownerRepresentation = null,
                iThread = iThread,
                actorId = currentActorId[iThread]!!,
                callStackTrace = callStackTrace[iThread]!!,
                fieldName = "${objectTracker.getObjectRepresentation(array)}[$index]",
                codeLocation = codeLocation,
                isLocal = false,
            ).also {
                it.initializeWrittenValue(objectTracker.getObjectRepresentation(value), objectFqTypeName(value))
            }
        } else {
            null
        }
        newSwitchPoint(iThread, codeLocation)
        traceCollector?.addTracePointInternal(tracePoint)
        loopDetector.beforeWriteArrayElement(array, index, value)
        true
    }

    override fun afterWrite() {
        if (collectTrace) {
            runInsideIgnoredSection {
                traceCollector?.addStateRepresentation()
            }
        }
    }

    override fun afterLocalRead(codeLocation: Int, variableId: Int, value: Any?) = runInsideIgnoredSection {
        val variableDescriptor = TRACE_CONTEXT.getVariableDescriptor(variableId)
        if (!collectTrace) return
        val iThread = threadScheduler.getCurrentThreadId()
        val shadowStackFrame = shadowStack[iThread]!!.last()
        shadowStackFrame.setLocalVariable(variableDescriptor.name, value)
        // TODO: enable local vars tracking in the trace after further polishing
        // TODO: add a flag to enable local vars tracking in the trace conditionally
        // val tracePoint = if (collectTrace) {
        //     ReadTracePoint(
        //         ownerRepresentation = null,
        //         iThread = iThread,
        //         actorId = currentActorId[iThread]!!,
        //         callStackTrace = callStackTrace[iThread]!!,
        //         fieldName = variableDescriptor.name,
        //         codeLocation = codeLocation,
        //         isLocal = true,
        //     ).also { it.initializeReadValue(adornedStringRepresentation(value), objectFqTypeName(value)) }
        // } else {
        //     null
        // }
        // traceCollector!!.passCodeLocation(tracePoint)
    }

    override fun afterLocalWrite(codeLocation: Int, variableId: Int, value: Any?) = runInsideIgnoredSection {
        val variableDescriptor = TRACE_CONTEXT.getVariableDescriptor(variableId)
        if (!collectTrace) return
        val iThread = threadScheduler.getCurrentThreadId()
        val shadowStackFrame = shadowStack[iThread]!!.last()
        shadowStackFrame.setLocalVariable(variableDescriptor.name, value)
        // TODO: enable local vars tracking in the trace after further polishing
        // TODO: add a flag to enable local vars tracking in the trace conditionally
        // val tracePoint = if (collectTrace) {
        //     WriteTracePoint(
        //         ownerRepresentation = null,
        //         iThread = iThread,
        //         actorId = currentActorId[iThread]!!,
        //         callStackTrace = callStackTrace[iThread]!!,
        //         fieldName = variableDescriptor.name,
        //         codeLocation = codeLocation,
        //         isLocal = true,
        //     ).also { it.initializeWrittenValue(adornedStringRepresentation(value), objectFqTypeName(value)) }
        // } else {
        //     null
        // }
        // traceCollector!!.passCodeLocation(tracePoint)
    }

    override fun beforeNewObjectCreation(className: String) = runInsideIgnoredSection {
        LincheckJavaAgent.ensureClassHierarchyIsTransformed(className)
    }

    override fun afterNewObjectCreation(obj: Any) {
        if (obj.isImmutable) return
        runInsideIgnoredSection {
            identityHashCodeTracker.afterNewTrackedObjectCreation(obj)
            objectTracker.registerNewObject(obj)
        }
    }

    private fun shouldTrackArrayAccess(obj: Any?): Boolean = shouldTrackObjectAccess(obj)

    private fun shouldTrackFieldAccess(obj: Any?, fieldDescriptor: FieldDescriptor): Boolean =
      shouldTrackObjectAccess(obj) && !isStackRecoveryFieldAccess(obj, fieldDescriptor.fieldName) && !fieldDescriptor.isFinal

    private fun shouldTrackObjectAccess(obj: Any?): Boolean {
        // by default, we track accesses to all objects
        return objectTracker.shouldTrackObjectAccess(obj)
    }

    private fun isStackRecoveryFieldAccess(obj: Any?, fieldName: String?): Boolean =
        obj is Continuation<*> && (fieldName == "label" || fieldName?.startsWith("L$") == true)

    override fun getThreadLocalRandom(): InjectedRandom = runInsideIgnoredSection {
        return randoms[threadScheduler.getCurrentThreadId()]!!
    }

    override fun randomNextInt(): Int = runInsideIgnoredSection {
        getThreadLocalRandom().nextInt()
    }

    override fun getCachedInvokeDynamicCallSite(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Injections.HandlePojo,
        bootstrapMethodArguments: Array<Any>
    ): CallSite? {
        val trueBootstrapMethodHandle = bootstrapMethodHandle.toAsmHandle()
        val invokeDynamic = ConstantDynamic(name, descriptor, trueBootstrapMethodHandle, *bootstrapMethodArguments)
        return invokeDynamicCallSites[invokeDynamic]
    }

    override fun cacheInvokeDynamicCallSite(
        name: String,
        descriptor: String,
        bootstrapMethodHandle: Injections.HandlePojo,
        bootstrapMethodArguments: Array<Any>,
        callSite: CallSite,
    ) {
        val trueBootstrapMethodHandle = bootstrapMethodHandle.toAsmHandle()
        val invokeDynamic = ConstantDynamic(name, descriptor, trueBootstrapMethodHandle, *bootstrapMethodArguments)
        invokeDynamicCallSites[invokeDynamic] = callSite
    }

    private fun Injections.HandlePojo.toAsmHandle(): Handle =
        Handle(tag, owner, name, desc, isInterface)

    override fun getNextTraceDebuggerEventTrackerId(tracker: TraceDebuggerTracker): TraceDebuggerEventId = runInsideIgnoredSection {
        traceDebuggerEventTrackers[tracker]?.getNextId() ?: 0
    }

    override fun advanceCurrentTraceDebuggerEventTrackerId(tracker: TraceDebuggerTracker, oldId: TraceDebuggerEventId): Unit = runInsideIgnoredSection {
        traceDebuggerEventTrackers[tracker]?.advanceCurrentId(oldId)
    }

    /**
     * Tracks a specific field of an [obj], if the [obj] is either `null` (which means that field is static),
     * or one this objects which contains it is already stored.
     *
     * *Must be called from [runInsideIgnoredSection].*
     */
    private fun updateSnapshotOnFieldAccess(obj: Any?, className: String, fieldName: String) {
        memorySnapshot.trackField(obj, className, fieldName)
    }

    /**
     * Tracks a specific [array] element at [index], if the [array] is already tracked.
     *
     * *Must be called from [runInsideIgnoredSection].*
     */
    private fun updateSnapshotOnArrayElementAccess(array: Any, index: Int) {
        memorySnapshot.trackArrayCell(array, index)
    }

    /**
     * Tracks all objects in [objs] eagerly.
     * Required as a trick to overcome issue with leaking this in constructors, see https://github.com/JetBrains/lincheck/issues/424.
     */
    override fun updateSnapshotBeforeConstructorCall(objs: Array<Any?>) = runInsideIgnoredSection {
        memorySnapshot.trackObjects(objs)
    }

    /**
     * Tracks fields that are accessed via System.arraycopy, Unsafe API, VarHandle API, Java AFU API, and kotlinx.atomicfu.
     *
     * *Must be called from [runInsideIgnoredSection].*
     */
    private fun processMethodEffectOnStaticSnapshot(
        owner: Any?,
        params: Array<Any?>
    ) {
        when {
            // Unsafe API
            isUnsafe(owner) -> {
                val methodType: UnsafeName = UnsafeNames.getMethodCallType(params)
                when (methodType) {
                    is UnsafeInstanceMethod -> {
                        memorySnapshot.trackField(methodType.owner, methodType.owner.javaClass, methodType.fieldName)
                    }
                    is UnsafeStaticMethod -> {
                        memorySnapshot.trackField(null, methodType.clazz, methodType.fieldName)
                    }
                    is UnsafeArrayMethod -> {
                        memorySnapshot.trackArrayCell(methodType.array, methodType.index)
                    }
                    else -> {}
                }
            }
            // VarHandle API
            isVarHandle(owner) -> {
                val methodType: VarHandleMethodType = VarHandleNames.varHandleMethodType(owner, params)
                when (methodType) {
                    is InstanceVarHandleMethod -> {
                        memorySnapshot.trackField(methodType.owner, methodType.owner.javaClass, methodType.fieldName)
                    }
                    is StaticVarHandleMethod -> {
                        memorySnapshot.trackField(null, methodType.ownerClass, methodType.fieldName)
                    }
                    is ArrayVarHandleMethod -> {
                        memorySnapshot.trackArrayCell(methodType.array, methodType.index)
                    }
                    else -> {}
                }
            }
            // Java AFU (this also automatically handles the `kotlinx.atomicfu`, since they are compiled to Java AFU + Java atomic arrays)
            isAtomicFieldUpdater(owner) -> {
                val obj = params[0]
                val afuDesc: AtomicFieldUpdaterDescriptor? = AtomicFieldUpdaterNames.getAtomicFieldUpdaterDescriptor(owner!!)
                check(afuDesc != null) { "Cannot extract field name referenced by Java AFU object $owner" }

                memorySnapshot.trackField(obj, afuDesc.targetType, afuDesc.fieldName)
            }
            // TODO: System.arraycopy
            // TODO: reflection
        }
    }

    /**
     * Propagates the modification done by intrinsic calls to the strategy.
     * This functionality is required, because we cannot instrument intrinsic methods directly.
     *
     * *Must be called from [runInsideIgnoredSection].*
     */
    private fun processIntrinsicMethodEffects(
        methodId: Int,
        result: Any?
    ) {
        val intrinsicDescriptor = TRACE_CONTEXT.getMethodDescriptor(methodId)
        check(intrinsicDescriptor.isIntrinsic) { "Processing intrinsic method effect of non-intrinsic call" }

        if (
            intrinsicDescriptor.isArraysCopyOfIntrinsic() ||
            intrinsicDescriptor.isArraysCopyOfRangeIntrinsic()
        ) {
            result?.let { afterNewObjectCreation(it) }
        }
    }

    override fun onMethodCall(
        codeLocation: Int,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>
    ): Any? = runInsideIgnoredSection {
        val methodDescriptor = TRACE_CONTEXT.getMethodDescriptor(methodId)
        // process method effect on the static memory snapshot
        processMethodEffectOnStaticSnapshot(receiver, params)
        // re-throw abort error if the thread was aborted
        val threadId = threadScheduler.getCurrentThreadId()
        if (threadScheduler.isAborted(threadId)) {
            threadScheduler.abortCurrentThread()
        }
        // check if the called method is an atomics API method
        // (e.g., Atomic classes, AFU, VarHandle memory access API, etc.)
        val atomicMethodDescriptor = getAtomicMethodDescriptor(receiver, methodDescriptor.methodName)
        // obtain deterministic method descriptor if required
        val methodCallInfo = MethodCallInfo(
            ownerType = Types.ObjectType(methodDescriptor.className),
            methodSignature = methodDescriptor.methodSignature,
            codeLocation = codeLocation,
            methodId = methodId,
        )
        val deterministicMethodDescriptor = getDeterministicMethodDescriptorOrNull(receiver, params, methodCallInfo)
        // get method's analysis section type
        val methodSection = methodAnalysisSectionType(
            receiver,
            methodDescriptor.className,
            methodDescriptor.methodName,
            atomicMethodDescriptor,
            deterministicMethodDescriptor,
        )
        // in case if a static method is called, ensure its class is instrumented
        if (receiver == null && methodSection < AnalysisSectionType.ATOMIC) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(methodDescriptor.className)
        }
        // in case of atomics API setter method call, notify the object tracker about a new link between objects
        if (atomicMethodDescriptor != null && atomicMethodDescriptor.kind.isSetter) {
            objectTracker.registerObjectLink(
                fromObject = atomicMethodDescriptor.getAccessedObject(receiver!!, params),
                toObject = atomicMethodDescriptor.getSetValue(receiver, params)
            )
        }
        // in case of an atomic method, we create a switch point before the method call;
        // note that in case we resume atomic method there is no need to create the switch point,
        // since there is already a switch point between the suspension point and resumption
        if (methodSection == AnalysisSectionType.ATOMIC &&
            // do not create a trace point on resumption
            !isResumptionMethodCall(threadId, methodDescriptor.className,
                methodDescriptor.methodName, params, atomicMethodDescriptor)
        ) {
            // create a trace point
            val tracePoint = if (collectTrace)
                addBeforeMethodCallTracePoint(threadId, receiver, codeLocation, methodId,
                    methodDescriptor.className, methodDescriptor.methodName, params,
                    atomicMethodDescriptor,
                    MethodCallTracePoint.CallType.NORMAL,
                )
            else null
            // create a switch point
            newSwitchPoint(threadId, codeLocation, beforeMethodCallSwitch = true)
            // add trace point to the trace
            traceCollector?.addTracePointInternal(tracePoint)
            // notify loop detector
            loopDetector.beforeAtomicMethodCall(codeLocation, params)
        } else {
            // handle non-atomic methods
            if (collectTrace) {
                // check for livelock and create the method call trace point
                traceCollector?.checkActiveLockDetected()
                addBeforeMethodCallTracePoint(threadId, receiver, codeLocation, methodId,
                    methodDescriptor.className, methodDescriptor.methodName, params,
                    atomicMethodDescriptor,
                    MethodCallTracePoint.CallType.NORMAL,
                )
            }
            // notify loop detector about the method call
            if (methodSection < AnalysisSectionType.ATOMIC) {
                loopDetector.beforeMethodCall(codeLocation, params)
            }
        }
        // if the method has certain guarantees, enter the corresponding section
        enterAnalysisSection(threadId, methodSection)
        return deterministicMethodDescriptor
    }

    override fun onMethodCallReturn(
        descriptorId: Long,
        deterministicMethodDescriptor: Any?,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
        result: Any?
    ): Any? = runInsideIgnoredSection {
        val methodDescriptor = TRACE_CONTEXT.getMethodDescriptor(methodId)
        var newResult = result
        if (deterministicMethodDescriptor != null) {
            Logger.debug { "On method return with descriptor $deterministicMethodDescriptor: $result" }
        }

        require(deterministicMethodDescriptor is DeterministicMethodDescriptor<*, *>?)
        // process intrinsic candidate methods
        if (methodDescriptor.isIntrinsic) {
            processIntrinsicMethodEffects(methodId, result)
        }

        if (isInTraceDebuggerMode && isFirstReplay && deterministicMethodDescriptor != null) {
            newResult = deterministicMethodDescriptor.saveFirstResultWithCast(receiver, params, KResult.success(result)) {
                nativeMethodCallStatesTracker.setState(descriptorId, deterministicMethodDescriptor.methodCallInfo, it)
            }.getOrElse { error("Unexpected replacement success -> failure:\n$result\n${KResult.failure<Any?>(it)}") }
        }
        val threadId = threadScheduler.getCurrentThreadId()
        // check if the called method is an atomics API method
        // (e.g., Atomic classes, AFU, VarHandle memory access API, etc.)
        val atomicMethodDescriptor = getAtomicMethodDescriptor(receiver, methodDescriptor.methodName)
        // get method's analysis section type
        val methodSection = methodAnalysisSectionType(
            receiver,
            methodDescriptor.className,
            methodDescriptor.methodName,
            atomicMethodDescriptor,
            deterministicMethodDescriptor,
        )
        if (collectTrace) {
            // an empty stack trace case is possible and can occur when we resume the coroutine,
            // and it results in a call to a top-level actor `suspend` function;
            // currently top-level actor functions are not represented in the `callStackTrace`,
            // we should probably refactor and fix that, because it is very inconvenient
            if (callStackTrace[threadId]!!.isNotEmpty()) {
                val tracePoint = callStackTrace[threadId]!!.last().tracePoint
                when (result) {
                    Unit -> tracePoint.initializeVoidReturnedValue()
                    Injections.VOID_RESULT -> tracePoint.initializeVoidReturnedValue()
                    COROUTINE_SUSPENDED -> tracePoint.initializeCoroutineSuspendedResult()
                    else -> tracePoint.initializeReturnedValue(objectTracker.getObjectRepresentation(result), objectFqTypeName(result))
                }
                afterMethodCall(threadId, tracePoint)
                traceCollector?.addStateRepresentation()
            }
        }
        // if the method has certain guarantees, leave the corresponding section
        leaveAnalysisSection(threadId, methodSection)
        return newResult
    }

    override fun onMethodCallException(
        descriptorId: Long,
        deterministicMethodDescriptor: Any?,
        methodId: Int,
        receiver: Any?,
        params: Array<Any?>,
        throwable: Throwable
    ): Throwable = runInsideIgnoredSection {
        var newThrowable = throwable
        val methodDescriptor = TRACE_CONTEXT.getMethodDescriptor(methodId)
        if (deterministicMethodDescriptor != null) {
            Logger.debug { "On method exception with descriptor $deterministicMethodDescriptor:\n${throwable.stackTraceToString()}" }
        }
        require(deterministicMethodDescriptor is DeterministicMethodDescriptor<*, *>?)
        if (isInTraceDebuggerMode && isFirstReplay && deterministicMethodDescriptor != null) {
            newThrowable = deterministicMethodDescriptor.saveFirstResult(receiver, params, KResult.failure(throwable)) {
                nativeMethodCallStatesTracker.setState(descriptorId, deterministicMethodDescriptor.methodCallInfo, it)
            }.let { newResult ->
                newResult.exceptionOrNull() ?: error("Unexpected replacement failure -> success:\n$throwable\n$newResult")
            }
        }
        val threadId = threadScheduler.getCurrentThreadId()
        // check if the called method is an atomics API method
        // (e.g., Atomic classes, AFU, VarHandle memory access API, etc.)
        val atomicMethodDescriptor = getAtomicMethodDescriptor(receiver, methodDescriptor.methodName)
        // get method's analysis section type
        val methodSection = methodAnalysisSectionType(
            receiver,
            methodDescriptor.className,
            methodDescriptor.methodName,
            atomicMethodDescriptor,
            deterministicMethodDescriptor,
        )
        if (collectTrace) {
            // this case is possible and can occur when we resume the coroutine,
            // and it results in a call to a top-level actor `suspend` function;
            // currently top-level actor functions are not represented in the `callStackTrace`,
            // we should probably refactor and fix that, because it is very inconvenient
            if (callStackTrace[threadId]!!.isEmpty()) return newThrowable
            val tracePoint = callStackTrace[threadId]!!.last().tracePoint
            if (!tracePoint.isActor) tracePoint.initializeThrownException(throwable)
            afterMethodCall(threadId, tracePoint)
            traceCollector?.addStateRepresentation()
        }
        // if the method has certain guarantees, leave the corresponding section
        leaveAnalysisSection(threadId, methodSection)
        newThrowable
    }

    override fun onInlineMethodCall(
        methodId: Int,
        codeLocation: Int,
        owner: Any?,
    ) = runInsideIgnoredSection {
        val methodDescriptor = TRACE_CONTEXT.getMethodDescriptor(methodId)
        val threadId = threadScheduler.getCurrentThreadId()
        if (threadScheduler.isAborted(threadId)) {
            threadScheduler.abortCurrentThread()
        }
        if (collectTrace) {
            traceCollector!!.checkActiveLockDetected()
            addBeforeMethodCallTracePoint(
                threadId = threadId,
                owner = owner,
                codeLocation = codeLocation,
                methodId = methodId,
                className = methodDescriptor.className,
                methodName = methodDescriptor.methodName,
                methodParams = emptyArray(),
                atomicMethodDescriptor = null,
                callType = MethodCallTracePoint.CallType.NORMAL
            )
        }
    }

    override fun onInlineMethodCallReturn(
        methodId: Int,
    ) = runInsideIgnoredSection {
        val threadId = threadScheduler.getCurrentThreadId()
        if (collectTrace) {
            // an empty stack trace case is possible and can occur when we resume the coroutine,
            // and it results in a call to a top-level actor `suspend` function;
            // currently top-level actor functions are not represented in the `callStackTrace`,
            // we should probably refactor and fix that, because it is very inconvenient
            if (callStackTrace[threadId]!!.isNotEmpty()) {
                val tracePoint = callStackTrace[threadId]!!.last().tracePoint
                tracePoint.initializeVoidReturnedValue()
                afterMethodCall(threadId, tracePoint)
                traceCollector!!.addStateRepresentation()
            }
        }
    }

    private fun <T> KResult<T>.toBootstrapResult() =
        if (isSuccess) BootstrapResult.fromSuccess(getOrThrow())
        else BootstrapResult.fromFailure(exceptionOrNull()!!)

    override fun invokeDeterministicallyOrNull(
        descriptorId: Long,
        descriptor: Any?,
        receiver: Any?,
        params: Array<Any?>
    ): BootstrapResult<*>? = runInsideIgnoredSection {
        when {
            descriptor !is DeterministicMethodDescriptor<*, *> -> null
            !isInTraceDebuggerMode -> descriptor.runFake(receiver, params).toBootstrapResult()
            isFirstReplay -> null
            else -> {
                val state = nativeMethodCallStatesTracker.getState(descriptorId, descriptor.methodCallInfo)
                descriptor.runFromStateWithCast(receiver, params, state).toBootstrapResult()
            }
        }
    }

    private fun methodAnalysisSectionType(
        owner: Any?,
        className: String,
        methodName: String,
        atomicMethodDescriptor: AtomicMethodDescriptor?,
        deterministicMethodDescriptor: DeterministicMethodDescriptor<*, *>?,
    ): AnalysisSectionType {
        val ownerName = owner?.javaClass?.canonicalName ?: className
        if (atomicMethodDescriptor != null) {
            return AnalysisSectionType.ATOMIC
        }
        // TODO: decide if we need to introduce special `DETERMINISTIC` guarantee?
        if (deterministicMethodDescriptor != null) {
            return AnalysisSectionType.IGNORED
        }
        // Ignore methods called on standard I/O streams
        when (owner) {
            System.`in`, System.out, System.err -> return AnalysisSectionType.IGNORED
        }
        val section = analysisProfile.getAnalysisSectionFor(ownerName, methodName)
        userDefinedGuarantees?.forEach { guarantee ->
            if (guarantee.classPredicate(ownerName) && guarantee.methodPredicate(methodName)) {
                return guarantee.type
            }
        }
        return section
    }

    private fun enterAnalysisSection(threadId: ThreadId, section: AnalysisSectionType) {
        val analysisSectionStack = this.analysisSectionStack[threadId]!!
        val currentSection = analysisSectionStack.lastOrNull()
        if (currentSection != null && currentSection.isCallStackPropagating() && section < currentSection) {
            analysisSectionStack.add(currentSection)
        } else {
            analysisSectionStack.add(section)
        }
        if (section == AnalysisSectionType.IGNORED ||
            // TODO: atomic should have different semantics compared to ignored
            section == AnalysisSectionType.ATOMIC
        ) {
            enterIgnoredSection()
        }
    }

    private fun leaveAnalysisSection(threadId: ThreadId, section: AnalysisSectionType) {
        if (section == AnalysisSectionType.IGNORED ||
            // TODO: atomic should have different semantics compared to ignored
            section == AnalysisSectionType.ATOMIC
        ) {
            leaveIgnoredSection()
        }
        val analysisSectionStack = this.analysisSectionStack[threadId]!!
        analysisSectionStack.removeLast().ensure { currentSection ->
            currentSection == section || (currentSection.isCallStackPropagating() && section < currentSection)
        }
    }

    protected fun inSilentSection(threadId: ThreadId): Boolean {
        return (analysisSectionStack[threadId]!!.lastOrNull()?.isSilent() ?: false)
    }

    private fun isResumptionMethodCall(
        threadId: Int,
        className: String,
        methodName: String,
        methodParams: Array<Any?>,
        atomicMethodDescriptor: AtomicMethodDescriptor?,
    ): Boolean {
        // special coroutines handling methods can only be called from test threads
        if (!isTestThread(threadId)) return false
        // optimization - first quickly check if the method is atomics API method,
        // in which case it cannot be suspended/resumed method
        if (atomicMethodDescriptor != null) return false
        val suspendedMethodStack = suspendedFunctionsStack[threadId]!!
        return suspendedMethodStack.isNotEmpty() && isSuspendFunction(className, methodName, methodParams)
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was suspended.
     * @param iThread number of invoking thread
     */
    internal fun afterCoroutineSuspended(iThread: Int) {
        check(threadScheduler.getCurrentThreadId() == iThread)
        check(isTestThread(iThread)) {
            "Special coroutines handling methods should only be called from test threads"
        }
        isSuspended[iThread] = true
        if (runner.isCoroutineResumed(iThread, currentActorId[iThread]!!)) {
            // `UNKNOWN_CODE_LOCATION`, because we do not know the actual code location
            newSwitchPoint(iThread, UNKNOWN_CODE_LOCATION)
        } else {
            onSwitchPoint(iThread)
            // coroutine suspension does not violate obstruction-freedom
            switchCurrentThread(iThread, BlockingReason.Suspended)
        }
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was resumed.
     */
    internal fun afterCoroutineResumed() {
        val iThread = threadScheduler.getCurrentThreadId()
        check(isTestThread(iThread)) {
            "Special coroutines handling methods should only be called from test threads"
        }
        isSuspended[iThread] = false
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was cancelled.
     */
    internal fun afterCoroutineCancelled() {
        val iThread = threadScheduler.getCurrentThreadId()
        check(isTestThread(iThread)) {
            "Special coroutines handling methods should only be called from test threads"
        }
        isSuspended[iThread] = false
        // method will not be resumed after suspension, so clear prepared for resume call stack
        suspendedFunctionsStack[iThread]!!.clear()
    }

    private fun addBeforeMethodCallTracePoint(
        threadId: Int,
        owner: Any?,
        codeLocation: Int,
        methodId: Int,
        className: String,
        methodName: String,
        methodParams: Array<Any?>,
        atomicMethodDescriptor: AtomicMethodDescriptor?,
        callType: MethodCallTracePoint.CallType,
    ): MethodCallTracePoint? {
        val callStackTrace = callStackTrace[threadId]!!
        if (isTestThread(threadId) && isResumptionMethodCall(threadId, className, methodName, methodParams, atomicMethodDescriptor)) {
            val suspendedMethodStack = suspendedFunctionsStack[threadId]!!
            // In case of resumption, we need to find a call stack frame corresponding to the resumed function
            var elementIndex = suspendedMethodStack.indexOfFirst {
                it.tracePoint.className == className && it.tracePoint.methodName == methodName
            }
            if (elementIndex == -1) {
                // this case is possible and can occur when we resume the coroutine,
                // and it results in a call to a top-level actor `suspend` function;
                // currently top-level actor functions are not represented in the `callStackTrace`,
                // we should probably refactor and fix that, because it is very inconvenient
                val actor = scenario.threads[threadId][currentActorId[threadId]!!]
                check(methodName == actor.method.name)
                check(className == actor.method.declaringClass.name)
                elementIndex = suspendedMethodStack.size
            }
            // get suspended stack trace elements to restore
            val resumedStackTrace = suspendedMethodStack
                .subList(elementIndex, suspendedMethodStack.size)
                .reversed()
            // we assume that all methods lying below the resumed one in stack trace
            // have empty resumption part or were already resumed before,
            // so we remove them from the suspended methods stack.
            suspendedMethodStack.subList(0, elementIndex + 1).clear()
            // we need to restore suspended stack trace elements
            // if they are not on the top of the current stack trace
            if (!resumedStackTrace.isSuffixOf(callStackTrace)) {
                // restore resumed stack trace elements
                callStackTrace.addAll(resumedStackTrace)
                resumedStackTrace.forEach { pushShadowStackFrame(it.instance) }
            }
            // since we are in resumption, skip the next ` beforeEvent ` call
            skipNextBeforeEvent = true
            return null
        }
        val callId = callStackTraceElementId++
        // The code location of the new method call is currently the last one
        val tracePoint = createBeforeMethodCallTracePoint(
            iThread = threadId,
            owner = owner,
            className = className,
            methodName = methodName,
            params = methodParams,
            codeLocation = codeLocation,
            atomicMethodDescriptor = atomicMethodDescriptor,
            callType = callType,
        )
        traceCollector?.addTracePointInternal(tracePoint)
        // Method invocation id used to calculate spin cycle start label call depth.
        // Two calls are considered equals if two same methods were called with the same parameters.
        val methodInvocationId = Objects.hash(methodId,
            methodParams.map { primitiveOrIdentityHashCode(it) }.toTypedArray().contentHashCode()
        )
        val stackTraceElement = CallStackTraceElement(
            id = callId,
            tracePoint = tracePoint,
            instance = owner,
            methodInvocationId = methodInvocationId
        )
        callStackTrace.add(stackTraceElement)
        pushShadowStackFrame(owner)
        return tracePoint
    }

    private fun createBeforeMethodCallTracePoint(
        iThread: Int,
        owner: Any?,
        className: String,
        methodName: String,
        params: Array<Any?>,
        codeLocation: Int,
        atomicMethodDescriptor: AtomicMethodDescriptor?,
        callType: MethodCallTracePoint.CallType,
    ): MethodCallTracePoint {
        val callStackTrace = callStackTrace[iThread]!!
        val tracePoint = MethodCallTracePoint(
            iThread = iThread,
            actorId = currentActorId[iThread]!!,
            className = className,
            methodName = methodName,
            callStackTrace = callStackTrace,
            codeLocation = codeLocation,
            isStatic = (owner == null),
            callType = callType,
            isSuspend = isSuspendFunction(className, methodName, params)
        )
        // handle non-atomic methods
        if (atomicMethodDescriptor == null) {
            val ownerName = if (owner != null) findOwnerName(owner) else className.toSimpleClassName()
            if (!ownerName.isNullOrEmpty()) {
                tracePoint.initializeOwnerName(ownerName)
            }
            tracePoint.initializeParameters(params.toList())
            return tracePoint
        }
        // handle atomic methods
        if (isVarHandle(owner)) {
            return initializeVarHandleMethodCallTracePoint(iThread, tracePoint, owner, params)
        }
        if (isAtomicFieldUpdater(owner)) {
            return initializeAtomicUpdaterMethodCallTracePoint(tracePoint, owner!!, params)
        }
        if (isAtomic(owner) || isAtomicArray(owner)) {
            return initializeAtomicReferenceMethodCallTracePoint(iThread, tracePoint, owner!!, params)
        }
        if (isUnsafe(owner)) {
            return initializeUnsafeMethodCallTracePoint(tracePoint, owner!!, params)
        }
        error("Unknown atomic method $className::$methodName")
    }

    private fun objectFqTypeName(obj: Any?): String {
        val typeName = obj?.javaClass?.name ?: "null"
        // Note: `if` here is important for performance reasons.
        // In common case we want to return just `typeName` without using string templates
        // to avoid redundant string allocation.
        if (obj?.javaClass?.isEnum == true) {
            return "Enum:$typeName"
        }
        return typeName
    }

    private fun initializeUnsafeMethodCallTracePoint(
        tracePoint: MethodCallTracePoint,
        receiver: Any,
        params: Array<Any?>
    ): MethodCallTracePoint {
        when (val unsafeMethodName = UnsafeNames.getMethodCallType(params)) {
            is UnsafeArrayMethod -> {
                val owner = "${objectTracker.getObjectRepresentation(unsafeMethodName.array)}[${unsafeMethodName.index}]"
                tracePoint.initializeOwnerName(owner)
                tracePoint.initializeParameters(unsafeMethodName.parametersToPresent)
            }
            is UnsafeName.TreatAsDefaultMethod -> {
                tracePoint.initializeOwnerName(objectTracker.getObjectRepresentation(receiver))
                tracePoint.initializeParameters(params.toList())
            }
            is UnsafeInstanceMethod -> {
                val ownerName = findOwnerName(unsafeMethodName.owner)
                val owner = ownerName?.let { "$ownerName.${unsafeMethodName.fieldName}" } ?: unsafeMethodName.fieldName
                tracePoint.initializeOwnerName(owner)
                tracePoint.initializeParameters(unsafeMethodName.parametersToPresent)
            }
            is UnsafeStaticMethod -> {
                tracePoint.initializeOwnerName("${unsafeMethodName.clazz.simpleName}.${unsafeMethodName.fieldName}")
                tracePoint.initializeParameters(unsafeMethodName.parametersToPresent)
            }
        }

        return tracePoint
    }

    private fun initializeAtomicReferenceMethodCallTracePoint(
        threadId: ThreadId,
        tracePoint: MethodCallTracePoint,
        receiver: Any,
        params: Array<Any?>
    ): MethodCallTracePoint {
        val shadowStackFrame = shadowStack[threadId]!!.last()
        val atomicReferenceInfo = AtomicReferenceNames.getMethodCallType(shadowStackFrame, receiver, params)
        when (atomicReferenceInfo) {
            is AtomicReferenceInstanceMethod -> {
                val receiverName = findOwnerName(atomicReferenceInfo.owner)
                tracePoint.initializeOwnerName(receiverName?.let { "$it.${atomicReferenceInfo.fieldName}" } ?: atomicReferenceInfo.fieldName)
                tracePoint.initializeParameters(params.toList())
            }
            is AtomicReferenceStaticMethod -> {
                val clazz = atomicReferenceInfo.ownerClass
                val thisClassName = shadowStackFrame.instance?.javaClass?.name
                val ownerName = if (thisClassName == clazz.name) "" else "${clazz.simpleName}."
                tracePoint.initializeOwnerName("${ownerName}${atomicReferenceInfo.fieldName}")
                tracePoint.initializeParameters(params.toList())
            }
            is AtomicReferenceInLocalVariable -> {
                tracePoint.initializeOwnerName("${atomicReferenceInfo.localVariable}.${atomicReferenceInfo.fieldName}")
                tracePoint.initializeParameters(params.toList())
            }
            is AtomicArrayMethod -> {
                tracePoint.initializeOwnerName("${objectTracker.getObjectRepresentation(atomicReferenceInfo.atomicArray)}[${atomicReferenceInfo.index}]")
                tracePoint.initializeParameters(params.drop(1))
            }
            is InstanceFieldAtomicArrayMethod -> {
                val receiverName = findOwnerName(atomicReferenceInfo.owner)
                tracePoint.initializeOwnerName((receiverName?.let { "$it." } ?: "") + "${atomicReferenceInfo.fieldName}[${atomicReferenceInfo.index}]")
                tracePoint.initializeParameters(params.drop(1))
            }
            is StaticFieldAtomicArrayMethod -> {
                val clazz = atomicReferenceInfo.ownerClass
                val thisClassName = shadowStackFrame.instance?.javaClass?.name
                val ownerName = if (thisClassName == clazz.name) "" else "${clazz.simpleName}."
                tracePoint.initializeOwnerName("${ownerName}${atomicReferenceInfo.fieldName}[${atomicReferenceInfo.index}]")
                tracePoint.initializeParameters(params.drop(1))
            }
            is AtomicArrayInLocalVariable -> {
                tracePoint.initializeOwnerName("${atomicReferenceInfo.localVariable}.${atomicReferenceInfo.fieldName}[${atomicReferenceInfo.index}]")
                tracePoint.initializeParameters(params.drop(1))
            }
            is AtomicReferenceMethodType.TreatAsDefaultMethod -> {
                tracePoint.initializeOwnerName(objectTracker.getObjectRepresentation(receiver))
                tracePoint.initializeParameters(params.toList())
            }
        }
        return tracePoint
    }

    private fun initializeVarHandleMethodCallTracePoint(
        threadId: Int,
        tracePoint: MethodCallTracePoint,
        varHandle: Any, // for Java 8, the VarHandle class does not exist
        parameters: Array<Any?>,
    ): MethodCallTracePoint {
        val shadowStackFrame = shadowStack[threadId]!!.last()
        val varHandleMethodType = VarHandleNames.varHandleMethodType(varHandle, parameters)
        when (varHandleMethodType) {
            is ArrayVarHandleMethod -> {
                tracePoint.initializeOwnerName("${objectTracker.getObjectRepresentation(varHandleMethodType.array)}[${varHandleMethodType.index}]")
                tracePoint.initializeParameters(varHandleMethodType.parameters)
            }
            is InstanceVarHandleMethod -> {
                val receiverName = findOwnerName(varHandleMethodType.owner)
                tracePoint.initializeOwnerName(receiverName?.let { "$it.${varHandleMethodType.fieldName}" } ?: varHandleMethodType.fieldName)
                tracePoint.initializeParameters(varHandleMethodType.parameters)
            }
            is StaticVarHandleMethod -> {
                val clazz = varHandleMethodType.ownerClass
                val thisClassName = shadowStackFrame.instance?.javaClass?.name
                val ownerName = if (thisClassName == clazz.name) "" else "${clazz.simpleName}."
                tracePoint.initializeOwnerName("${ownerName}${varHandleMethodType.fieldName}")
                tracePoint.initializeParameters(varHandleMethodType.parameters)
            }
            VarHandleMethodType.TreatAsDefaultMethod -> {
                tracePoint.initializeOwnerName(objectTracker.getObjectRepresentation(varHandle))
                tracePoint.initializeParameters(parameters.toList())
            }
        }

        return tracePoint
    }

    private fun initializeAtomicUpdaterMethodCallTracePoint(
        tracePoint: MethodCallTracePoint,
        atomicUpdater: Any,
        parameters: Array<Any?>,
    ): MethodCallTracePoint {
        getAtomicFieldUpdaterDescriptor(atomicUpdater)?.let { tracePoint.initializeOwnerName(it.fieldName) }
        tracePoint.initializeParameters(parameters.drop(1))
        return tracePoint
    }

    private fun MethodCallTracePoint.initializeParameters(parameters: List<Any?>) =
        initializeParameters(parameters.map { objectTracker.getObjectRepresentation(it) }, parameters.map { objectFqTypeName(it) })


    /**
     * Returns string representation of the field owner based on the provided parameters.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun getFieldOwnerName(obj: Any?, fieldDescriptor: FieldDescriptor): String? {
        if (fieldDescriptor.isStatic) {
            val threadId = threadScheduler.getCurrentThreadId()
            val stackTraceElement = shadowStack[threadId]!!.last()
            if (stackTraceElement.instance?.javaClass?.name == fieldDescriptor.className) {
                return null
            }
            return fieldDescriptor.className.toSimpleClassName()
        }
        return findOwnerName(obj!!)
    }

    /**
     * Returns beautiful string representation of the [owner].
     * If the [owner] is `this` of the current method, then returns `null`.
     */
    private fun findOwnerName(owner: Any): String? {
        val threadId = threadScheduler.getCurrentThreadId()
        // if the current owner is `this` - no owner needed
        if (isCurrentStackFrameReceiver(owner)) return null
        // do not prettify thread names
        if (owner is Thread) {
            return objectTracker.getObjectRepresentation(owner)
        }
        // lookup for the object in local variables and use the local variable name if found
        val shadowStackFrame = shadowStack[threadId]!!.last()
        shadowStackFrame.getLastAccessVariable(owner)?.let { return it }
        // lookup for a field name in the current stack frame `this`
        shadowStackFrame.instance
            ?.findInstanceFieldReferringTo(owner)
            ?.let { return it.name }
        // lookup for the constant referencing the object
        constants[owner]?.let { return it }
        // otherwise return object's string representation
        return objectTracker.getObjectRepresentation(owner)
    }

    /**
     * Checks if [owner] is the `this` object (i.e., receiver) of the currently executed method call.
     */
    private fun isCurrentStackFrameReceiver(owner: Any): Boolean {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        val stackTraceElement = shadowStack[currentThreadId]!!.last()
        return (owner === stackTraceElement.instance)
    }

    /* Methods to control the current call context. */

    private fun pushShadowStackFrame(owner: Any?) {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        val shadowStack = shadowStack[currentThreadId]!!
        val stackFrame = ShadowStackFrame(owner)
        shadowStack.add(stackFrame)
    }

    private fun popShadowStackFrame() {
        val currentThreadId = threadScheduler.getCurrentThreadId()
        val shadowStack = shadowStack[currentThreadId]!!
        shadowStack.removeLast()
        check(shadowStack.isNotEmpty()) {
            "Shadow stack cannot be empty"
        }
    }

    /**
     * This method is invoked by a test thread
     * after each method invocation.
     * @param iThread number of invoking thread
     * @param tracePoint the corresponding trace point for the invocation
     */
    private fun afterMethodCall(iThread: Int, tracePoint: MethodCallTracePoint) {
        val callStackTrace = callStackTrace[iThread]!!
        if (tracePoint.wasSuspended) {
            // if a method call is suspended, save its call stack element to reuse for continuation resuming
            suspendedFunctionsStack[iThread]!!.add(callStackTrace.last())
            popShadowStackFrame()
            callStackTrace.removeLast()

            // Hack to include actor
            if (callStackTrace.size == 1 && callStackTrace.first().tracePoint.isRootCall) {
                suspendedFunctionsStack[iThread]!!.add(callStackTrace.first())
                popShadowStackFrame()
                callStackTrace.removeLast()
            }

            return
        }

        popShadowStackFrame()
        callStackTrace.removeLast()
    }

    // == LOGGING METHODS ==

    /**
     * Creates a new [CoroutineCancellationTracePoint].
     */
    internal fun createAndLogCancellationTracePoint(): CoroutineCancellationTracePoint? {
        if (collectTrace) {
            val iThread = threadScheduler.getCurrentThreadId()
            val actorId = currentActorId[iThread] ?: Int.MIN_VALUE
            val cancellationTracePoint = CoroutineCancellationTracePoint(
                iThread,
                actorId,
                callStackTrace[iThread]?.toList() ?: emptyList()
            )
            traceCollector?.addTracePointInternal(cancellationTracePoint)
            return cancellationTracePoint
        }
        return null
    }

    fun enableReplayModeForIdeaPlugin() {
        inIdeaPluginReplayMode = true
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

    override fun shouldInvokeBeforeEvent(): Boolean {
        // We do not check `inIgnoredSection` here because this method is called from instrumented code
        // that should be invoked only outside the ignored section.
        // However, we cannot add `!inIgnoredSection` check here
        // as the instrumented code might call `enterIgnoredSection` just before this call.
        return inIdeaPluginReplayMode && collectTrace &&
                suddenInvocationResult == null &&
                isRegisteredThread() &&
                !shouldSkipNextBeforeEvent()
    }

    /**
     * Indicates if the next [beforeEvent] method call should be skipped.
     *
     * @see skipNextBeforeEvent
     */
    private fun shouldSkipNextBeforeEvent(): Boolean {
        val skipBeforeEvent = skipNextBeforeEvent
        if (skipNextBeforeEvent) {
            skipNextBeforeEvent = false
        }
        return skipBeforeEvent
    }

    protected fun resetEventIdProvider() {
        eventIdProvider = EventIdProvider()
    }

    fun enumerateObjects(): Map<Any, Int> {
        return objectTracker.enumerateAllObjects()
    }

    // == UTILITY METHODS ==

    /**
     * Checks if [threadId] is a `TestThread` instance (threads created and managed by lincheck itself).
     */
    private fun isTestThread(threadId: Int): Boolean {
        return threadId in (0 ..< nThreads)
    }

    // == TRACE COLLECTOR EXTENSION METHODS ==
    private fun TraceCollector.addTracePointInternal(tracePoint: TracePoint?) {
        // tracePoint can be null here if trace is not available, e.g. in case of suspension
        if (tracePoint == null) return

        if (tracePoint !is SectionDelimiterTracePoint && !tracePoint.isActorMethodCallTracePoint()) {
            checkActiveLockDetected()
        }

        addTracePoint(tracePoint)

        if (!tracePoint.isActorMethodCallTracePoint()) {
            setBeforeEventId(tracePoint)
        }
    }

    private fun TraceCollector.newSwitch(reason: SwitchReason, beforeMethodCallSwitch: Boolean) {
        val threadId = threadScheduler.getCurrentThreadId()
        if (reason == SwitchReason.ActiveLock) {
            afterSpinCycleTraceCollected(
                trace = trace,
                callStackTrace = callStackTrace[threadId]!!,
                spinCycleMethodCallsStackTraces = spinCycleMethodCallsStackTraces,
                iThread = threadId,
                beforeMethodCallSwitch = beforeMethodCallSwitch
            )
        }
        addTracePoint(
            SwitchEventTracePoint(
                iThread = threadId,
                actorId = currentActorId[threadId]!!,
                reason = reason,
                callStackTrace = when (reason) {
                    SwitchReason.Suspended -> suspendedFunctionsStack[threadId]!!.reversed()
                    else -> callStackTrace[threadId]!!
                },
            )
        )
        spinCycleStartAdded = false
    }

    private fun TraceCollector.onThreadFinish() {
        spinCycleStartAdded = false
    }

    private fun TraceCollector.checkActiveLockDetected() {
        if (!loopDetector.replayModeCurrentlyInSpinCycle) return

        val threadId = threadScheduler.getCurrentThreadId()
        if (spinCycleStartAdded) {
            spinCycleMethodCallsStackTraces += callStackTrace[threadId]!!.toList()
        } else {
            addTracePoint(
                SpinCycleStartTracePoint(
                    iThread = threadId,
                    actorId = currentActorId[threadId]!!,
                    callStackTrace = callStackTrace[threadId]!!,
                )
            )
            spinCycleStartAdded = true
            spinCycleMethodCallsStackTraces.clear()
        }
    }

    private fun TraceCollector.addStateRepresentation() {
        val stateRepresentation = runner.constructStateRepresentation() ?: return
        val threadId = threadScheduler.getCurrentThreadId()
        // use call stack trace of the previous trace point
        traceCollector?.addTracePoint(
            StateRepresentationTracePoint(
                iThread = threadId,
                actorId = currentActorId[threadId]!!,
                stateRepresentation = stateRepresentation,
                callStackTrace = callStackTrace[threadId]!!,
            )
        )
    }

    private fun TraceCollector.passObstructionFreedomViolationTracePoint(beforeMethodCall: Boolean) {
        val threadId = threadScheduler.getCurrentThreadId()
        afterSpinCycleTraceCollected(
            trace = trace,
            callStackTrace = callStackTrace[threadId]!!,
            spinCycleMethodCallsStackTraces = spinCycleMethodCallsStackTraces,
            iThread = threadId,
            beforeMethodCallSwitch = beforeMethodCall
        )
        addTracePoint(
            ObstructionFreedomViolationExecutionAbortTracePoint(
                iThread = threadId,
                actorId = currentActorId[threadId]!!,
                callStackTrace = trace.last().callStackTrace
            )
        )
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

    override fun onThreadStart(iThread: Int) = runInsideIgnoredSection {
        if (currentExecutionPart !== PARALLEL) return
        managedStrategy.onThreadStart(iThread)
    }

    override fun onThreadFinish(iThread: Int) = runInsideIgnoredSection {
        if (currentExecutionPart !== PARALLEL) return
        managedStrategy.onThreadFinish(iThread)
    }

    override fun onActorFailure(iThread: Int, throwable: Throwable) = runInsideIgnoredSection {
        if (isInternalException(throwable)) {
            managedStrategy.onInternalException(iThread, throwable)
        }
    }

    override fun afterCoroutineSuspended(iThread: Int) = runInsideIgnoredSection {
        super.afterCoroutineSuspended(iThread)
        managedStrategy.afterCoroutineSuspended(iThread)
    }

    override fun afterCoroutineResumed(iThread: Int) = runInsideIgnoredSection {
        managedStrategy.afterCoroutineResumed()
    }

    override fun afterCoroutineCancelled(iThread: Int) = runInsideIgnoredSection {
        managedStrategy.afterCoroutineCancelled()
    }

    override fun constructStateRepresentation(): String? {
        if (stateRepresentationFunction == null) return null
        // Enter ignored section, because Runner will call transformed state representation method
        return runInsideIgnoredSection {
            super.constructStateRepresentation()
        }
    }

    override fun <T> cancelByLincheck(cont: CancellableContinuation<T>, promptCancellation: Boolean): CancellationResult = runInsideIgnoredSection {
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

private fun TracePoint.isActorMethodCallTracePoint() =
    (this is MethodCallTracePoint && this.isRootCall)

// represents an unknown code location
internal const val UNKNOWN_CODE_LOCATION = -1

private val BlockingReason.obstructionFreedomViolationMessage: String get() = when (this) {
    is BlockingReason.Locked       -> OBSTRUCTION_FREEDOM_LOCK_VIOLATION_MESSAGE
    is BlockingReason.LiveLocked   -> OBSTRUCTION_FREEDOM_SPINLOCK_VIOLATION_MESSAGE
    is BlockingReason.Waiting      -> OBSTRUCTION_FREEDOM_WAIT_VIOLATION_MESSAGE
    is BlockingReason.Parked       -> OBSTRUCTION_FREEDOM_PARK_VIOLATION_MESSAGE
    is BlockingReason.ThreadJoin   -> OBSTRUCTION_FREEDOM_THREAD_JOIN_VIOLATION_MESSAGE
    is BlockingReason.Suspended    -> OBSTRUCTION_FREEDOM_SUSPEND_VIOLATION_MESSAGE
}

/**
 * @param id specifies the string literal that will be parsed on the plugin side,
 * thus, it should never be changed unconsciously. The plugin will use this values
 * to determine what kind of UI to show to the user.
 */
enum class ExecutionMode(val id: String) {
    DATA_STRUCTURES("DATA_STRUCTURES"),
    GENERAL_PURPOSE_MODEL_CHECKER("GENERAL_PURPOSE_MODEL_CHECKER"),
    TRACE_DEBUGGER("TRACE_DEBUGGER")
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
    if (strategy.inIdeaPluginReplayMode) INFINITE_TIMEOUT else defaultTimeOutMs
