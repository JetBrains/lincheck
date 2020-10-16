/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategyTransformer.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.objectweb.asm.*
import org.objectweb.asm.commons.*
import java.lang.reflect.Method
import java.util.*
import kotlin.collections.set

/**
 * This is an abstract class for all managed strategies.
 * This abstraction helps to choose a proper [Runner],
 * to transform byte-code in order to insert required for managing instructions,
 * to implement these managing instructions providing higher level interface,
 * to support constructing interleaving trace,
 * and to hide class loading problems from the strategy algorithm.
 */
abstract class ManagedStrategy(
    private val testClass: Class<*>,
    scenario: ExecutionScenario,
    private val verifier: Verifier,
    private val validationFunctions: List<Method>,
    private val stateRepresentationFunction: Method?,
    private val testCfg: ManagedCTestConfiguration
) : Strategy(scenario) {
    // the number of parallel threads
    protected val nThreads: Int = scenario.parallelExecution.size
    // runner for scenario invocations
    protected var runner: Runner

    // == EXECUTION CONTROL FIELDS ==

    // what thread is currently allowed to perform operations
    @Volatile
    protected var currentThread: Int = 0
    // whether a thread finished all its operations
    private val finished = BooleanArray(nThreads) { false }
    // is thread suspended
    private val isSuspended = BooleanArray(nThreads) { false }
    // current actor id for each thread
    protected val currentActorId = IntArray(nThreads)
    // the number of blocks that should be ignored by the strategy entered and not left for each thread
    private val ignoredSectionDepth = IntArray(nThreads) { 0 }
    // detector of loops (i.e. active locks)
    private lateinit var loopDetector: LoopDetector
    // tracker of acquisitions and releases of monitors
    private lateinit var monitorTracker: MonitorTracker

    // InvocationResult that was observed by the strategy in the execution (e.g. deadlock)
    @Volatile
    protected var suddenInvocationResult: InvocationResult? = null

    // == TRACE CONSTRUCTION FIELDS ==

    // whether additional information about events in the interleaving should be collected
    private var constructTraceRepresentation = false
    // whether additional information about states after interleaving events should be collected
    private val collectStateRepresentation get() = constructTraceRepresentation && stateRepresentationFunction != null
    // interleaving point constructors, where `interleavingPointConstructors[id]` stores
    // a constructor for the corresponding code location.
    private val interleavingPointConstructors: MutableList<() -> InterleavingPoint> = ArrayList()
    // code points for trace construction
    private val trace: MutableList<InterleavingPoint> = ArrayList()
    // logger of all events in the execution such as thread switches
    private var eventCollector: InterleavingEventCollector? = null // null when `constructStateRepresentation` is false
    // stack with info about method invocations in current stack trace for each thread
    private val callStackTrace = Array(nThreads) { mutableListOf<CallStackTraceElement>() }
    // an increasing id all method invocations
    private var methodIdentifier = 0
    // stack with info about suspended method invocations for each thread
    private val suspendedMethodStack = Array(nThreads) { mutableListOf<Int>() }
    // store previous created transformer to make code location ids different for different classes

    init {
        runner = createRunner()
        // Managed state should be initialized before test class transformation
        initializeManagedState()
        runner.initialize()
    }

    private fun createRunner(): Runner =
        ManagedStrategyRunner(this, testClass, validationFunctions, stateRepresentationFunction, testCfg.timeoutMs, UseClocks.ALWAYS)

    private fun initializeManagedState() {
        ManagedStrategyStateHolder.setState(runner.classLoader, this)
    }

    override fun createTransformer(cv: ClassVisitor): ClassVisitor = ManagedStrategyTransformer(
        cv = cv,
        codeLocationsConstructors = interleavingPointConstructors,
        guarantees = testCfg.guarantees,
        eliminateLocalObjects = testCfg.eliminateLocalObjects,
        collectStateRepresentation = collectStateRepresentation,
        constructTraceRepresentation = constructTraceRepresentation
    )

    override fun needsTransformation(): Boolean = true

    override fun createRemapper(): Remapper? = JavaUtilRemapper()

    override fun run(): LincheckFailure? = runner.use { runImpl() }

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
     * Choose a thread to switch among [switchableThreads] variants.
     * @return id the chosen variant
     */
    protected abstract fun chooseThread(switchableThreads: Int): Int

    /**
     * Returns all data to the initial state.
     */
    protected open fun initializeInvocation() {
        finished.fill(false)
        isSuspended.fill(false)
        currentActorId.fill(-1)
        loopDetector = LoopDetector(testCfg.hangingDetectionThreshold)
        monitorTracker = MonitorTracker(nThreads)
        eventCollector = if (constructTraceRepresentation) InterleavingEventCollector() else null
        suddenInvocationResult = null
        ignoredSectionDepth.fill(0)
        callStackTrace.forEach { it.clear() }
        suspendedMethodStack.forEach { it.clear() }
        ManagedStrategyStateHolder.resetState(runner.classLoader)
    }

    // == BASIC STRATEGY METHODS ==

    /**
     * Verifies results and if there are incorrect results then re-runs with
     * logging of all thread events.
     */
    protected fun checkResults(result: InvocationResult): LincheckFailure? = when (result) {
        is CompletedInvocationResult -> {
            if (verifier.verifyResults(scenario, result.results)) null
            else IncorrectResultsFailure(scenario, result.results, collectExecutionEvents(result))
        }
        else -> result.toLincheckFailure(scenario, collectExecutionEvents(result))
    }

    /**
     * Reruns previous invocation to log all its execution events.
     */
    private fun collectExecutionEvents(previousResults: InvocationResult): List<InterleavingEvent>? {
        val detectedByStrategy = suddenInvocationResult != null
        val canCollectInterleavingEvents = when {
            detectedByStrategy -> true // ObstructionFreedomViolationInvocationResult or UnexpectedExceptionInvocationResult
            previousResults is CompletedInvocationResult -> true
            previousResults is ValidationFailureInvocationResult -> true
            else -> false
        }

        if (!canCollectInterleavingEvents) {
            // interleaving events can be collected almost always,
            // except for strange cases such as Runner timeout or LinChecker exceptions.
            return null
        }
        // re-transform class constructing trace
        constructTraceRepresentation = true
        runner = createRunner()
        initializeManagedState()
        runner.initialize()
        val loggedResults = runInvocation()
        val sameResultTypes = loggedResults.javaClass == previousResults.javaClass
        // cannot check whether the results are exactly the same because of re-transformation
        // so just check that types are the same
        check(sameResultTypes) {
            StringBuilder().apply {
                appendln("Non-determinism found. Probably caused by non-deterministic code (WeakHashMap, Object.hashCode, etc).")
                appendln("Reporting scenario without execution trace.")
                appendln(loggedResults.asLincheckFailureWithoutTrace().toString())
            }.toString()
        }
        return eventCollector!!.interleavingEvents
    }

    /**
     * Runs next invocation with the same [scenario][ExecutionScenario].
     * @return invocation results for each executed actor.
     */
    protected fun runInvocation(): InvocationResult {
        initializeInvocation()
        val result = runner.run()
        // if strategy already determined invocation result, then return it instead
        suddenInvocationResult?.let { return it  }
        return result
    }

    private fun failIfObstructionFreedomIsRequired(lazyMessage: () -> String) {
        if (testCfg.checkObstructionFreedom) {
            suddenInvocationResult = ObstructionFreedomViolationInvocationResult(lazyMessage())
            // forcibly finish execution by throwing an exception.
            throw ForcibleExecutionFinishException
        }
    }

    private fun checkLiveLockHappened(interleavingEventsCount: Int) {
        if (interleavingEventsCount > ManagedCTestConfiguration.LIVELOCK_EVENTS_THRESHOLD) {
            suddenInvocationResult = DeadlockInvocationResult(collectThreadDump(runner))
            // forcibly finish execution by throwing an exception.
            throw ForcibleExecutionFinishException
        }
    }

    // == EXECUTION CONTROL METHODS ==

    /**
     * Create a new switch point, where a thread context switch can occur.
     * @param iThread the current thread
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of the point in code.
     */
    private fun newSwitchPoint(iThread: Int, codeLocation: Int) {
        if (iThread == nThreads) return // can switch only test threads
        check(iThread == currentThread)
        if (ignoredSectionDepth[iThread] != 0) return // can not suspend in ignored sections
        // save code location description corresponding to the current switch point,
        // it is last code point now, but will be not last after a possible switch
        val codePointId = trace.lastIndex
        var isLoop = false
        if (loopDetector.visitCodeLocation(iThread, codeLocation)) {
            failIfObstructionFreedomIsRequired { "Obstruction-freedom is required but an active lock has been found" }
            isLoop = true
        }
        val shouldSwitch = shouldSwitch(iThread) or isLoop
        if (shouldSwitch) {
            val reason = if (isLoop) SwitchReason.ACTIVE_LOCK else SwitchReason.STRATEGY_SWITCH
            switchCurrentThread(iThread, reason)
        }
        eventCollector?.passCodeLocation(iThread, codeLocation, codePointId)
        // continue operation
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
        eventCollector?.finishThread(iThread)
        doSwitchCurrentThread(iThread, true)
    }

    /**
     * This method is executed if an exception has been thrown.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param exception the exception that was thrown
     */
    open fun onFailure(iThread: Int, exception: Throwable) {
        // Despite the fact that the corresponding failure will be detected by the runner,
        // the managed strategy can construct an interleaving to produce the error. Let's
        // then store the corresponding failing result and construct the trace then.
        if (exception is ForcibleExecutionFinishException) return // not a forcible execution finish
        suddenInvocationResult = UnexpectedExceptionInvocationResult(exception)
    }

    override fun onActorStart(iThread: Int) {
        currentActorId[iThread]++
        callStackTrace[iThread].clear()
        suspendedMethodStack[iThread].clear()
        loopDetector.reset(iThread)
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
        while (currentThread != iThread) {
            // Finish forcibly if an error was occured and we already have an `InvocationResult`.
            if (suddenInvocationResult != null) throw ForcibleExecutionFinishException
            Thread.yield()
        }
    }

    /**
     * A regular context thread switch to another thread.
     */
    private fun switchCurrentThread(iThread: Int, reason: SwitchReason = SwitchReason.STRATEGY_SWITCH, mustSwitch: Boolean = false) {
        eventCollector?.newSwitch(iThread, reason)
        doSwitchCurrentThread(iThread, mustSwitch)
        awaitTurn(iThread)
    }

    private fun doSwitchCurrentThread(iThread: Int, mustSwitch: Boolean = false) {
        onNewSwitch(iThread, mustSwitch)
        val switchableThreads = threadsToSwitch(iThread)
        if (switchableThreads.isEmpty()) {
            if (mustSwitch && !finished.all { it }) {
                // all threads are suspended
                // then switch on any suspended thread to finish it and get SuspendedResult
                val nextThread = (0 until nThreads).firstOrNull { !finished[it] && isSuspended[it] }
                if (nextThread == null) {
                    // must switch not to get into a deadlock, but there are no threads to switch.
                    suddenInvocationResult = DeadlockInvocationResult(collectThreadDump(runner))
                    // forcibly finish execution by throwing an exception.
                    throw ForcibleExecutionFinishException
                }
                currentThread = nextThread
            }
            return // ignore switch, because there is no one to switch to
        }
        val nextThreadNumber = chooseThread(switchableThreads.size)
        currentThread = switchableThreads[nextThreadNumber]
    }

    /**
     * Threads to which a thread [iThread] can switch
     */
    protected fun threadsToSwitch(iThread: Int) = (0 until nThreads).filter { it != iThread && isActive(it) }

    private fun isTestThread(iThread: Int) = iThread < nThreads

    private fun inIgnoredSection(iThread: Int): Boolean = !isTestThread(iThread) || ignoredSectionDepth[iThread] > 0

    // == LISTENING METHODS ==

    /**
     * This method is executed before a shared variable read operation.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    fun beforeSharedVariableRead(iThread: Int, codeLocation: Int) {
        newSwitchPoint(iThread, codeLocation)
    }

    /**
     * This method is executed before a shared variable write operation.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    fun beforeSharedVariableWrite(iThread: Int, codeLocation: Int) {
        newSwitchPoint(iThread, codeLocation)
    }

    /**
     * This method is executed before an atomic method call.
     * Atomic method is a method that is marked by ManagedGuarantee to be treated as atomic.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    fun beforeAtomicMethodCall(iThread: Int, codeLocation: Int) {
        newSwitchPoint(iThread, codeLocation)
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @return whether lock should be actually acquired
     */
    fun beforeLockAcquire(iThread: Int, codeLocation: Int, monitor: Any): Boolean {
        if (!isTestThread(iThread)) return true
        newSwitchPoint(iThread, codeLocation)
        // Try to acquire the monitor
        if (!monitorTracker.acquireMonitor(iThread, monitor)) {
            failIfObstructionFreedomIsRequired { "Obstruction-freedom is required but a lock has been found" }
            // Switch to another thread and wait for a moment when the monitor can be acquired
            switchCurrentThread(iThread, SwitchReason.LOCK_WAIT, true)
            // Now it is possible to acquire the monitor, do it then.
            monitorTracker.acquireMonitor(iThread, monitor)
        }
        // The monitor is acquired, finish.
        return false
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @return whether lock should be actually released
     */
    fun beforeLockRelease(iThread: Int, codeLocation: Int, monitor: Any): Boolean {
        if (!isTestThread(iThread)) return true
        monitorTracker.releaseMonitor(monitor)
        eventCollector?.passCodeLocation(iThread, codeLocation, trace.lastIndex)
        return false
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @param withTimeout `true` if is invoked with timeout, `false` otherwise.
     * @return whether park should be executed
     */
    fun beforePark(iThread: Int, codeLocation: Int, @Suppress("UNUSED_PARAMETER") withTimeout: Boolean): Boolean {
        if (!isTestThread(iThread)) return true
        newSwitchPoint(iThread, codeLocation)
        return false
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    fun afterUnpark(iThread: Int, codeLocation: Int, @Suppress("UNUSED_PARAMETER") thread: Any) {
        eventCollector?.passCodeLocation(iThread, codeLocation, trace.lastIndex)
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @param withTimeout `true` if is invoked with timeout, `false` otherwise.
     * @return whether wait should be executed
     */
    fun beforeWait(iThread: Int, codeLocation: Int, monitor: Any, withTimeout: Boolean): Boolean {
        if (!isTestThread(iThread)) return true
        failIfObstructionFreedomIsRequired { "Obstruction-freedom is required but a waiting on monitor block has been found" }
        newSwitchPoint(iThread, codeLocation)
        if (withTimeout) return false // timeouts occur instantly
        monitorTracker.waitOnMonitor(iThread, monitor)
        // switch to another thread and wait till a notify event happens
        switchCurrentThread(iThread, SwitchReason.MONITOR_WAIT, true)
        return false
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    fun afterNotify(iThread: Int, codeLocation: Int, monitor: Any, notifyAll: Boolean) {
        if (notifyAll)
            monitorTracker.notifyAll(monitor)
        else
            monitorTracker.notify(monitor)
        eventCollector?.passCodeLocation(iThread, codeLocation, trace.lastIndex)
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was suspended.
     * @param iThread number of invoking thread
     */
    fun afterCoroutineSuspended(iThread: Int) {
        check(currentThread == iThread)
        isSuspended[iThread] = true
        if (runner.isCoroutineResumed(iThread, currentActorId[iThread])) {
            // `COROUTINE_SUSPENSION_CODE_LOCATION`, because we do not know the actual code location
            newSwitchPoint(iThread, COROUTINE_SUSPENSION_CODE_LOCATION)
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
    fun afterCoroutineResumed(iThread: Int) {
        check(currentThread == iThread)
        isSuspended[iThread] = false
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was cancelled.
     * @param iThread number of invoking thread
     */
    fun afterCoroutineCancelled(iThread: Int) {
        check(currentThread == iThread)
        isSuspended[iThread] = false
        // method will not be resumed after suspension, so clear prepared for resume call stack
        suspendedMethodStack[iThread].clear()
    }

    /**
     * This method is invoked by a test thread
     * before each ignored section start.
     * These sections are determined by Strategy.ignoredEntryPoints()
     * @param iThread number of invoking thread
     */
    fun enterIgnoredSection(iThread: Int) {
        if (isTestThread(iThread))
            ignoredSectionDepth[iThread]++
    }

    /**
     * This method is invoked by a test thread
     * after each ignored section end.
     * @param iThread number of invoking thread
     */
    fun leaveIgnoredSection(iThread: Int) {
        if (isTestThread(iThread))
            ignoredSectionDepth[iThread]--
    }

    /**
     * This method is invoked by a test thread
     * before each method invocation.
     * @param codeLocation the byte-code location identifier of this invocation
     * @param iThread number of invoking thread
     */
    fun beforeMethodCall(iThread: Int, @Suppress("UNUSED_PARAMETER") codeLocation: Int) {
        if (isTestThread(iThread)) {
            check(constructTraceRepresentation) { "This method should be called only when logging is enabled" }
            val callStackTrace = callStackTrace[iThread]
            val suspendedMethodStack = suspendedMethodStack[iThread]
            val methodId = if (suspendedMethodStack.isNotEmpty()) {
                // if there was a suspension before, then instead of creating a new identifier
                // use the one that the suspended call had
                val lastId = suspendedMethodStack.last()
                suspendedMethodStack.removeAt(suspendedMethodStack.lastIndex)
                lastId
            } else {
                methodIdentifier++
            }
            // code location of the new method call is currently the last
            callStackTrace.add(CallStackTraceElement(trace.last() as MethodCallInterleavingPoint, methodId))
        }
    }

    /**
     * This method is invoked by a test thread
     * after each method invocation.
     * @param iThread number of invoking thread
     * @param codePoint the identifier of the call code point for the invocation
     */
    fun afterMethodCall(iThread: Int, codePoint: Int) {
        if (isTestThread(iThread)) {
            check(constructTraceRepresentation) { "This method should be called only when logging is enabled" }
            val callStackTrace = callStackTrace[iThread]
            val methodCallCodeLocation = getCodePoint(codePoint) as MethodCallInterleavingPoint
            if (methodCallCodeLocation.wasSuspended) {
                // if a method call is suspended, save its identifier to reuse for continuation resuming
                suspendedMethodStack[iThread].add(callStackTrace.last().identifier)
            }
            callStackTrace.removeAt(callStackTrace.lastIndex)
        }
    }

    // == LOGGING METHODS ==

    /**
     * Returns a [InterleavingPoint] which describes the specified visit to a code location.
     * This method's invocations are inserted by transformer for adding non-trivial code point information.
     * @param codePoint code point identifier
     */
    fun getCodePoint(codePoint: Int): InterleavingPoint = trace[codePoint]

    /**
     * Creates a new [InterleavingPoint].
     * The type of the created code location is defined by the used constructor.
     * This method's invocations are inserted by transformer at each code location.
     * @param constructorId which constructor to use for createing code location
     * @return index of the created code location
     */
    fun createCodePoint(constructorId: Int): Int {
        trace.add(interleavingPointConstructors[constructorId]())
        return trace.size - 1
    }

    /**
     * Creates a state representation and logs it.
     * This method invocations are inserted by transformer
     * after each write operation and atomic method invocation.
     */
    fun addStateRepresentation(iThread: Int) {
        if (!inIgnoredSection(iThread)) {
            check(constructTraceRepresentation) { "This method should be called only when logging is enabled" }
            eventCollector?.addStateRepresentation(iThread)
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

    private fun InvocationResult.asLincheckFailureWithoutTrace(): LincheckFailure {
        if (this is CompletedInvocationResult)
            return IncorrectResultsFailure(scenario, results, null)
        return toLincheckFailure(scenario, null)
    }

    /**
     * Logs thread events such as thread switches and passed code locations.
     */
    private inner class InterleavingEventCollector {
        private val _interleavingEvents = mutableListOf<InterleavingEvent>()
        val interleavingEvents: List<InterleavingEvent> = _interleavingEvents

        fun newSwitch(iThread: Int, reason: SwitchReason) {
            _interleavingEvents += SwitchEvent(iThread, currentActorId[iThread], reason, callStackTrace[iThread].toList())
            // check livelock after every switch
            checkLiveLockHappened(_interleavingEvents.size)
        }

        fun finishThread(iThread: Int) {
            _interleavingEvents += FinishEvent(iThread)
        }

        fun passCodeLocation(iThread: Int, codeLocation: Int, codePoint: Int) {
            // Ignore coroutine suspensions - they are processed in another place.
            if (codeLocation == COROUTINE_SUSPENSION_CODE_LOCATION) return
            _interleavingEvents += PassCodeLocationEvent(
                iThread, currentActorId[iThread],
                getCodePoint(codePoint),
                callStackTrace[iThread].toList() // we need a copy of the current call stack.
            )
        }

        fun addStateRepresentation(iThread: Int) {
            // enter ignored section, because stateRepresentation invokes transformed method with switch points
            enterIgnoredSection(iThread)
            val stateRepresentation = runner.constructStateRepresentation()!!
            leaveIgnoredSection(iThread)
            _interleavingEvents += StateRepresentationEvent(iThread, currentActorId[iThread], stateRepresentation, callStackTrace[iThread].toList())
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
}

/**
 * Detects loops ans active locks when the same code location is visited too often.
 */
private class LoopDetector(private val hangingDetectionThreshold: Int) {
    private var lastIThread = -1 // no last thread
    private val operationCounts = mutableMapOf<Int, Int>()

    /**
     * Returns `true` if a loop or a hang is detected,
     * `false` otherwise.
     */
    fun visitCodeLocation(iThread: Int, codeLocation: Int): Boolean {
        // Have the thread changed? Reset the counters in this case.
        if (lastIThread != iThread) reset(iThread)
        // Ignore coroutine suspension code locations.
        if (codeLocation == COROUTINE_SUSPENSION_CODE_LOCATION) return false
        // Increment the number of times the specified code location is visited.
        val count = operationCounts.getOrDefault(codeLocation, 0) + 1
        operationCounts[codeLocation] = count
        // Check whether the count exceeds the maximum number of repetitions for loop/hang detection.
        return count > hangingDetectionThreshold
    }

    /**
     * Resets the counters for the specified thread.
     */
    fun reset(iThread: Int) {
        operationCounts.clear()
        lastIThread = iThread
    }
}

/**
 * Tracks synchronization operations with monitors (acquire/release, wait/notify) to maintain a set of active threads.
 */
private class MonitorTracker(nThreads: Int) {
    // Maintains a set of acquired monitors with an information on which thread
    // performed the acquisition and the the reentrancy depth.
    private val acquiredMonitors = IdentityHashMap<Any, MonitorAcquiringInfo>()
    // Maintains a set of monitors on which each thread is waiting.
    // Note, that a thread can wait on a free monitor if it is waiting for
    // a `notify` call.
    // Stores `null` if thread is not waiting on any monitor.
    private val acquiringMonitors = Array<Any?>(nThreads) { null }
    // Stores `true` for the threads which are waiting for a
    // `notify` call on the monitor stored in `acquiringMonitor`.
    private val waitForNotify = BooleanArray(nThreads) { false }

    /**
     * Performs a logical acquisition.
     */
    fun acquireMonitor(iThread: Int, monitor: Any): Boolean {
        // Increment the reentrant depth and store the
        // acquisition info if needed.
        val ai = acquiredMonitors.computeIfAbsent(monitor) { MonitorAcquiringInfo(iThread, 0) }
        if (ai.iThread != iThread) {
            acquiringMonitors[iThread] = monitor
            return false
        }
        ai.timesAcquired++
        acquiringMonitors[iThread] = null // re-set
        return true
    }

    /**
     * Performs a logical release.
     */
    fun releaseMonitor(monitor: Any) {
        // Decrement the reentrancy depth and remove the acquisition info
        // if the monitor becomes free to acquire by another thread.
        val ai = acquiredMonitors[monitor]!!
        ai.timesAcquired--
        if (ai.timesAcquired == 0) acquiredMonitors.remove(monitor)
    }

    /**
     * Returns `true` if the corresponding threads is waiting on some monitor.
     */
    fun isWaiting(iThread: Int): Boolean {
        val monitor = acquiringMonitors[iThread] ?: return false
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
     * returns `true` until the corresponding [notify] or [notifyAll]
     * is invoked.
     */
    fun waitOnMonitor(iThread: Int, monitor: Any) {
        // TODO: we can add spurious wakeups here
        check(monitor in acquiredMonitors) { "Monitor should have been acquired by this thread" }
        releaseMonitor(monitor)
        waitForNotify[iThread] = true
        acquiringMonitors[iThread] = monitor
    }

    /**
     * Just notify all thread. Odd threads will have a spurious wakeup
     */
    fun notify(monitor: Any) = notifyAll(monitor)

    /**
     * Performs the logical `notifyAll`.
     */
    fun notifyAll(monitor: Any): Unit = acquiringMonitors.forEachIndexed { iThread, m ->
        if (monitor === m) waitForNotify[iThread] = false
    }

    /**
     * Stores the number of reentrant acquisitions ([timesAcquired])
     * and the number of thread ([iThread]) that holds the monitor.
     */
    private class MonitorAcquiringInfo(val iThread: Int, var timesAcquired: Int)
}

/**
 * This exception is used to finish the execution correctly for managed strategies.
 * Otherwise, there is no way to do it in case of (e.g.) deadlocks.
 * If we just leave it, then the execution will not be halted.
 * If we forcibly pass through all barriers, then we can get another exception due to being in an incorrect state.
 */
internal object ForcibleExecutionFinishException : RuntimeException()

private const val COROUTINE_SUSPENSION_CODE_LOCATION = -1 // currently the exact place of coroutine suspension is not known