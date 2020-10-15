/*-
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

import org.jetbrains.kotlinx.lincheck.collectThreadDump
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategyTransformer.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.objectweb.asm.*
import org.objectweb.asm.commons.*
import java.lang.Exception
import java.lang.RuntimeException
import java.lang.reflect.Method
import java.util.*

/**
 * This is an abstract class for all managed strategies.
 * This abstraction helps to choose a proper [Runner],
 * to transform byte-code in order to insert required for managing instructions,
 * to implement these managing instructions providing higher level interface,
 * to support constructing interleaving trace,
 * and to hide class loading problems from the strategy algorithm.
 */
abstract class ManagedStrategy(private val testClass: Class<*>, scenario: ExecutionScenario, private val verifier: Verifier,
                               private val validationFunctions: List<Method>, private val stateRepresentation: Method?,
                               private val testCfg: ManagedCTestConfiguration) : Strategy(scenario) {
    // the number of parallel threads
    protected val nThreads: Int = scenario.parallelExecution.size
    // runner for scenario invocations
    protected var runner: Runner

    // == EXECUTION CONTROL FIELDS ==

    // whether a thread finished all its operations
    private val finished = BooleanArray(nThreads) { false }
    // what thread is currently allowed to perform operations
    @Volatile
    protected var currentThread: Int = 0
    // detector of loops (i.e. active locks)
    private lateinit var loopDetector: LoopDetector
    // tracker of acquisitions and releases of monitors
    private lateinit var monitorTracker: MonitorTracker
    // is thread suspended
    private val isSuspended = BooleanArray(nThreads) { false }
    // the number of blocks that should be ignored by the strategy entered and not left for each thread
    private val ignoredSectionDepth = IntArray(nThreads) { 0 }
    // current actor id for each thread
    protected val currentActorId = IntArray(nThreads)
    // InvocationResult that was observed by the strategy in the execution (e.g. deadlock)
    @Volatile
    protected var suddenInvocationResult: InvocationResult? = null

    // == TRACE CONSTRUCTION FIELDS ==

    // whether additional information about events in the interleaving should be collected
    protected var constructTraceRepresentation = false
    // whether additional information about states after interleaving events should be collected
    private val collectStateRepresentation: Boolean
        get() = stateRepresentation != null
    // code location constructors for trace construction, which are invoked by transformer
    private val codeLocationConstructors: MutableList<() -> CodePoint> = ArrayList()
    // code points for trace construction
    private val codePoints: MutableList<CodePoint> = ArrayList()
    // logger of all events in the execution such as thread switches
    private lateinit var eventCollector: InterleavingEventCollector
    // stack with info about method invocations in current stack trace for each thread
    private val callStackTrace = Array(nThreads) { mutableListOf<CallStackTraceElement>() }
    // an increasing id all method invocations
    private var methodIdentifier = 0
    // stack with info about suspended method invocations for each thread
    private val suspendedMethodStack = Array(nThreads) { mutableListOf<Int>() }
    // store previous created transformer to make code location ids different for different classes
    private var previousTransformer: ManagedStrategyTransformer? = null

    init {
        runner = createRunner()
        // Managed state should be initialized before test class transformation
        initializeManagedState()
        runner.initialize()
    }

    override fun createTransformer(cv: ClassVisitor): ClassVisitor = ManagedStrategyTransformer(
        cv = cv,
        codeLocationsConstructors = codeLocationConstructors,
        guarantees = testCfg.guarantees,
        eliminateLocalObjects = testCfg.eliminateLocalObjects,
        collectStateRepresentation = collectStateRepresentation,
        constructTraceRepresentation = constructTraceRepresentation,
        previousTransformer = previousTransformer
    ).also { previousTransformer = it }

    override fun createRemapper(): Remapper? = JavaUtilRemapper()

    override fun needsTransformation(): Boolean = true

    override fun run(): LincheckFailure?
        = try {
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
        eventCollector = InterleavingEventCollector()
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
    protected fun checkResults(results: InvocationResult): LincheckFailure? {
        when (results) {
            is CompletedInvocationResult -> {
                if (!verifier.verifyResults(scenario, results.results))
                    return IncorrectResultsFailure(scenario, results.results, collectExecutionEvents(results))
            }
            else -> return results.toLincheckFailure(scenario, collectExecutionEvents(results))
        }
        return null
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
        return eventCollector.interleavingEvents()
    }

    /**
     * Runs next invocation with the same [scenario][ExecutionScenario].
     * @return invocation results for each executed actor.
     */
    protected fun runInvocation(): InvocationResult {
        initializeInvocation()
        val result = runner.run()
        // if strategy already determined invocation result, then return it instead
        if (suddenInvocationResult != null)
            return suddenInvocationResult!!
        return result
    }

    private fun failIfObstructionFreedomIsRequired(lazyMessage: () -> String) {
        if (testCfg.checkObstructionFreedom) {
            suddenInvocationResult = ObstructionFreedomViolationInvocationResult(lazyMessage())
            // forcibly finish execution by throwing an exception.
            throw ForcibleExecutionFinishException()
        }
    }

    private fun checkLiveLockHappened(interleavingEventsCount: Int) {
        if (interleavingEventsCount > ManagedCTestConfiguration.LIVELOCK_EVENTS_THRESHOLD) {
            suddenInvocationResult = DeadlockInvocationResult(collectThreadDump(runner))
            // forcibly finish execution by throwing an exception.
            throw ForcibleExecutionFinishException()
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
        val codePointId = codePoints.lastIndex
        var isLoop = false
        if (loopDetector.newOperation(iThread, codeLocation)) {
            failIfObstructionFreedomIsRequired { "Obstruction-freedom is required but an active lock has been found" }
            isLoop = true
        }
        val shouldSwitch = shouldSwitch(iThread) or isLoop
        if (shouldSwitch) {
            val reason = if (isLoop) SwitchReason.ACTIVE_LOCK else SwitchReason.STRATEGY_SWITCH
            switchCurrentThread(iThread, reason)
        }
        eventCollector.passCodeLocation(iThread, codeLocation, codePointId)
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
        eventCollector.finishThread(iThread)
        doSwitchCurrentThread(iThread, true)
    }

    /**
     * This method is executed if an exception has been thrown.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param exception the exception that was thrown
     */
    open fun onFailure(iThread: Int, exception: Throwable) {
        if (suddenInvocationResult == null) // not a forcible execution finish
            suddenInvocationResult = UnexpectedExceptionInvocationResult(exception)
    }

    /**
     * This method is executed before each actor.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     */
    override fun onActorStart(iThread: Int) {
        currentActorId[iThread]++
        callStackTrace[iThread].clear()
        suspendedMethodStack[iThread].clear()
        loopDetector.reset(iThread) // visiting same code location in different actors is ok
    }

    /**
     * Returns whether the thread can continue its execution (i.e. is not blocked/finished)
     */
    protected fun canResume(iThread: Int): Boolean {
        var canResume = !finished[iThread] && monitorTracker.canResume(iThread)
        if (isSuspended[iThread])
            canResume = canResume && runner.isCoroutineResumed(iThread, currentActorId[iThread])
        return canResume
    }

    /**
     * Waits until this thread is allowed to be executed by the strategy.
     */
    private fun awaitTurn(iThread: Int) {
        // wait actively until the thread is allow to execute
        while (currentThread != iThread) {
            // finish forcibly if an error occured and we already have an InvocationResult.
            if (suddenInvocationResult != null) throw ForcibleExecutionFinishException()
            Thread.yield()
        }
    }

    /**
     * A regular context thread switch to another thread.
     */
    private fun switchCurrentThread(iThread: Int, reason: SwitchReason = SwitchReason.STRATEGY_SWITCH, mustSwitch: Boolean = false) {
        eventCollector.newSwitch(iThread, reason)
        doSwitchCurrentThread(iThread, mustSwitch)
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
                    suddenInvocationResult = DeadlockInvocationResult(collectThreadDump(runner))
                    // forcibly finish execution by throwing an exception.
                    throw ForcibleExecutionFinishException()
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
    protected fun switchableThreads(iThread: Int) = (0 until nThreads).filter { it != iThread && canResume(it) }

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
        // check if can acquire required monitor
        if (!monitorTracker.canAcquireMonitor(iThread, monitor)) {
            failIfObstructionFreedomIsRequired { "Obstruction-freedom is required but a lock has been found" }
            monitorTracker.awaitAcquiringMonitor(iThread, monitor)
            // switch to another thread and wait for a moment the monitor can be acquired
            switchCurrentThread(iThread, SwitchReason.LOCK_WAIT, true)
        }
        // can acquire monitor now. actually does it
        monitorTracker.acquireMonitor(iThread, monitor)
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
        eventCollector.passCodeLocation(iThread, codeLocation, codePoints.lastIndex)
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
        eventCollector.passCodeLocation(iThread, codeLocation, codePoints.lastIndex)
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
        monitorTracker.waitMonitor(iThread, monitor)
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
        eventCollector.passCodeLocation(iThread, codeLocation, codePoints.lastIndex)
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
            callStackTrace.add(CallStackTraceElement(codePoints.last() as MethodCallCodePoint, methodId))
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
            val methodCallCodeLocation = getCodePoint(codePoint) as MethodCallCodePoint
            if (methodCallCodeLocation.wasSuspended) {
                // if a method call is suspended, save its identifier to reuse for continuation resuming
                suspendedMethodStack[iThread].add(callStackTrace.last().identifier)
            }
            callStackTrace.removeAt(callStackTrace.lastIndex)
        }
    }

    // == LOGGING METHODS ==

    /**
     * Returns a [CodePoint] which describes the specified visit to a code location.
     * This method's invocations are inserted by transformer for adding non-trivial code point information.
     * @param codePoint code point identifier
     */
    fun getCodePoint(codePoint: Int): CodePoint = codePoints[codePoint]

    /**
     * Creates a new [CodePoint].
     * The type of the created code location is defined by the used constructor.
     * This method's invocations are inserted by transformer at each code location.
     * @param constructorId which constructor to use for createing code location
     * @return index of the created code location
     */
    fun createCodePoint(constructorId: Int): Int {
        codePoints.add(codeLocationConstructors[constructorId]())
        return codePoints.size - 1
    }

    /**
     * Creates a state representation and logs it.
     * This method are inserted by transformer after each write or atomic method invocation.
     */
    fun makeStateRepresentation(iThread: Int) {
        if (!inIgnoredSection(iThread)) {
            check(constructTraceRepresentation) { "This method should be called only when logging is enabled" }
            eventCollector.makeStateRepresentation(iThread)
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

    private fun createRunner(): Runner =
        ManagedStrategyRunner(this, testClass, validationFunctions, stateRepresentation, testCfg.timeoutMs, UseClocks.ALWAYS)

    private fun initializeManagedState() {
        ManagedStrategyStateHolder.setState(runner.classLoader, this)
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
        private val interleavingEvents = mutableListOf<InterleavingEvent>()

        fun newSwitch(iThread: Int, reason: SwitchReason) {
            if (!constructTraceRepresentation) return // check that should log thread events
            interleavingEvents.add(SwitchEvent(iThread, currentActorId[iThread], reason, callStackTrace[iThread].toList()))
            // check livelock after every switch
            checkLiveLockHappened(interleavingEvents.size)
        }

        fun finishThread(iThread: Int) {
            if (!constructTraceRepresentation) return // check that should log thread events
            interleavingEvents.add(FinishEvent(iThread))
        }

        fun passCodeLocation(iThread: Int, codeLocation: Int, codePoint: Int) {
            if (!constructTraceRepresentation) return // check that should log thread events
            if (codeLocation != COROUTINE_SUSPENSION_CODE_LOCATION) {
                interleavingEvents.add(PassCodeLocationEvent(
                    iThread, currentActorId[iThread],
                    getCodePoint(codePoint),
                    callStackTrace[iThread].toList()
                ))
            }
        }

        fun makeStateRepresentation(iThread: Int) {
            if (!constructTraceRepresentation) return // check that should log thread events
            // enter ignored section, because stateRepresentation invokes transformed method with switch points
            enterIgnoredSection(iThread)
            val stateRepresentation = runner.constructStateRepresentation()
            leaveIgnoredSection(iThread)
            interleavingEvents.add(StateRepresentationEvent(iThread, currentActorId[iThread], stateRepresentation!!, callStackTrace[iThread].toList()))
        }

        fun interleavingEvents(): List<InterleavingEvent> = interleavingEvents
    }
}

/**
 * This class is a [ParallelThreadsRunner] with some overrides that add callbacks
 * to strategy so that strategy can learn about execution events.
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
 * Detects loop when visiting a codeLocation too often.
 */
private class LoopDetector(private val hangingDetectionThreshold: Int) {
    private var lastIThread = -1 // no last thread
    private val operationCounts = mutableMapOf<Int, Int>()

    fun newOperation(iThread: Int, codeLocation: Int): Boolean {
        if (lastIThread != iThread) {
            // if we switched threads then reset counts
            operationCounts.clear()
            lastIThread = iThread
        }
        if (codeLocation == COROUTINE_SUSPENSION_CODE_LOCATION) return false
        // increment the number of times that we visited a code location
        val count = (operationCounts[codeLocation] ?: 0) + 1
        operationCounts[codeLocation] = count
        // return true if the thread exceeded the maximum number of repetitions that we can have
        return count > hangingDetectionThreshold
    }

    fun reset(iThread: Int) {
        operationCounts.clear()
        lastIThread = iThread
    }
}

/**
 * Track operations with monitor (acquire/release, wait/notify) to tell whether a thread can be executed.
 */
private class MonitorTracker(nThreads: Int) {
    // which monitors are held by test threads
    private val acquiredMonitors = IdentityHashMap<Any, LockAcquiringInfo>()
    // which monitor a thread want to acquire (or null)
    private val acquiringMonitor = Array<Any?>(nThreads) { null }
    // whether thread is waiting for notify on the corresponding monitor
    private val needsNotification = BooleanArray(nThreads) { false }

    fun canAcquireMonitor(iThread: Int, monitor: Any) = acquiredMonitors[monitor]?.iThread?.equals(iThread) ?: true

    fun acquireMonitor(iThread: Int, monitor: Any) {
        // increment the number of times the monitor was acquired
        acquiredMonitors.compute(monitor) { _, previousValue ->
            previousValue?.apply { timesAcquired++ } ?: LockAcquiringInfo(iThread, 1)
        }
        acquiringMonitor[iThread] = null
    }

    fun releaseMonitor(monitor: Any) {
        // decrement the number of times the monitor was acquired
        // remove if necessary
        acquiredMonitors.compute(monitor) { _, previousValue ->
            check(previousValue != null) { "Tried to release not acquired lock" }
            if (previousValue.timesAcquired == 1)
                null
            else
                previousValue.apply { timesAcquired-- }
        }
    }

    fun canResume(iThread: Int): Boolean {
        val monitor = acquiringMonitor[iThread] ?: return true
        return !needsNotification[iThread] && canAcquireMonitor(iThread, monitor)
    }

    fun awaitAcquiringMonitor(iThread: Int, monitor: Any) {
        acquiringMonitor[iThread] = monitor
    }

    fun waitMonitor(iThread: Int, monitor: Any) {
        // TODO: can add spurious wakeups
        check(monitor in acquiredMonitors) { "Monitor should have been acquired by this thread" }
        releaseMonitor(monitor)
        needsNotification[iThread] = true
        awaitAcquiringMonitor(iThread, monitor)
    }

    fun notify(monitor: Any) {
        // just notify all thread. Odd threads will have a spurious wakeup
        notifyAll(monitor)
    }

    fun notifyAll(monitor: Any) {
        for (iThread in needsNotification.indices)
            if (acquiringMonitor[iThread] === monitor)
                needsNotification[iThread] = false
    }

    /**
     * Info about a certain monitor with who and how many times acquired it without releasing.
     */
    private class LockAcquiringInfo(val iThread: Int, var timesAcquired: Int)
}

/**
 * This exception is used to finish the execution correctly for managed strategies.
 * Otherwise, there is no way to do it in case of (e.g.) deadlocks.
 * If we just leave it, then the execution will not be halted.
 * If we forcibly pass through all barriers, then we can get another exception due to being in an incorrect state.
 */
internal class ForcibleExecutionFinishException : RuntimeException()

private const val COROUTINE_SUSPENSION_CODE_LOCATION = -1 // currently the exact place of coroutine suspension is not known