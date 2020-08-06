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
package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.collectThreadDump
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTestConfiguration.*
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * This base class for managed strategies helps to handle code locations,
 * support locks and waits, and log events
 */
internal abstract class ManagedStrategyBase(
        testClass: Class<*>,
        scenario: ExecutionScenario,
        protected val verifier: Verifier,
        validationFunctions: List<Method>,
        stateRepresentation: Method?,
        private val testCfg: ManagedCTestConfiguration
) : ManagedStrategy(testClass, scenario, validationFunctions, stateRepresentation, testCfg.guarantees, testCfg.timeoutMs, testCfg.eliminateLocalObjects) {
    // whether a thread finished all its operations
    private val finished: Array<AtomicBoolean> = Array(nThreads) { AtomicBoolean(false) }
    // what thread is currently allowed to perform operations
    @Volatile
    protected var currentThread: Int = 0
    // detector of loops (i.e. active locks)
    private lateinit var loopDetector: LoopDetector
    // logger of all events in the execution such as thread switches
    private lateinit var eventCollector: ExecutionEventCollector
    // tracker of acquisitions and releases of monitors
    private lateinit var monitorTracker: MonitorTracker
    // random used for the generation of seeds and the execution tree
    protected val generationRandom = Random(0)
    // is thread suspended
    private val isSuspended: Array<AtomicBoolean> = Array(nThreads) { AtomicBoolean(false) }
    // the number of blocks that should be ignored by the strategy entered and not left for each thread
    private val ignoredSectionDepth = IntArray(nThreads) { 0 }
    // current actor id for each thread
    protected val currentActorId = IntArray(nThreads)
    // InvocationResult that was observed by the strategy in the execution (e.g. deadlock)
    @Volatile
    protected var suddenInvocationResult: InvocationResult? = null
    // stack with info about method invocations in current stack trace for each thread
    private val callStackTrace = Array(nThreads) { mutableListOf<CallStackTraceElement>() }
    // an increasing id all method invocations
    private var methodIdentifier = 0
    // stack with info about suspended method invocations for each thread
    private val suspendedMethodStack = Array(nThreads) { mutableListOf<Int>() }

    @Throws(Exception::class)
    abstract override fun runImpl(): LincheckFailure?

    override fun onStart(threadId: Int) {
        awaitTurn(threadId)
    }

    override fun onFinish(threadId: Int) {
        awaitTurn(threadId)
        finished[threadId].set(true)
        eventCollector.finishThread(threadId)
        onNewSwitch(threadId)
        doSwitchCurrentThread(threadId, true)
    }

    override fun onFailure(threadId: Int, e: Throwable) {
        if (suddenInvocationResult == null) // not a forcible execution finish
            suddenInvocationResult = UnexpectedExceptionInvocationResult(e)
    }

    /**
     * Is executed before any thread switch
     */
    protected open fun onNewSwitch(threadId: Int) {}

    override fun beforeSharedVariableRead(threadId: Int, codeLocation: Int) {
        newSwitchPoint(threadId, codeLocation)
    }

    override fun beforeSharedVariableWrite(threadId: Int, codeLocation: Int) {
        newSwitchPoint(threadId, codeLocation)
    }

    override fun beforeAtomicMethodCall(threadId: Int, codeLocation: Int) {
        newSwitchPoint(threadId, codeLocation)
    }

    override fun beforeLockAcquire(threadId: Int, codeLocation: Int, monitor: Any): Boolean {
        if (!isTestThread(threadId)) return true
        checkCanHaveObstruction { "At least obstruction freedom required, but a lock found" }
        newSwitchPoint(threadId, codeLocation)
        // check if can acquire required monitor
        if (!monitorTracker.canAcquireMonitor(monitor)) {
            monitorTracker.awaitAcquiringMonitor(threadId, monitor)
            // switch to another thread and wait for a moment the monitor can be acquired
            switchCurrentThread(threadId, SwitchReason.LOCK_WAIT, true)
        }
        // can acquire monitor now. actually does it
        monitorTracker.acquireMonitor(threadId, monitor)

        return false
    }

    override fun beforeLockRelease(threadId: Int, codeLocation: Int, monitor: Any): Boolean {
        if (!isTestThread(threadId)) return true
        monitorTracker.releaseMonitor(monitor)
        eventCollector.passCodeLocation(threadId, codeLocation)
        return false
    }

    override fun beforePark(threadId: Int, codeLocation: Int, withTimeout: Boolean): Boolean {
        if (!isTestThread(threadId)) return true
        newSwitchPoint(threadId, codeLocation)
        return false
    }

    override fun afterUnpark(threadId: Int, codeLocation: Int, thread: Any) {
        eventCollector.passCodeLocation(threadId, codeLocation)
    }

    override fun beforeWait(threadId: Int, codeLocation: Int, monitor: Any, withTimeout: Boolean): Boolean {
        if (!isTestThread(threadId)) return true

        checkCanHaveObstruction { "At least obstruction freedom required but a waiting on monitor found" }
        newSwitchPoint(threadId, codeLocation)
        if (withTimeout) return false // timeouts occur instantly
        monitorTracker.waitMonitor(threadId, monitor)
        // switch to another thread and wait till a notify event happens
        switchCurrentThread(threadId, SwitchReason.MONITOR_WAIT, true)
        return false
    }

    override fun afterNotify(threadId: Int, codeLocation: Int, monitor: Any, notifyAll: Boolean) {
        if (notifyAll)
            monitorTracker.notifyAll(monitor)
        else
            monitorTracker.notify(monitor)
        eventCollector.passCodeLocation(threadId, codeLocation)
    }

    override fun afterCoroutineSuspended(threadId: Int) {
        check(currentThread == threadId)
        isSuspended[threadId].set(true)
        if (runner.canResumeCoroutine(threadId, currentActorId[threadId])) {
            // COROUTINE_SUSPENSION_CODELOCATION, because we do not know the actual code location
            newSwitchPoint(threadId, COROUTINE_SUSPENSION_CODE_LOCATION)
        } else {
            // currently a coroutine suspension  is not supposed to violate obstruction-freedom
            // checkCanHaveObstruction { "At least obstruction freedom required but a loop found" }
            switchCurrentThread(threadId, SwitchReason.SUSPENDED, true)
        }
    }

    override fun beforeCoroutineResumed(threadId: Int) {
        check(currentThread == threadId)
        isSuspended[threadId].set(false)
    }

    override fun enterIgnoredSection(threadId: Int) {
        if (isTestThread(threadId))
            ignoredSectionDepth[threadId]++
    }

    override fun leaveIgnoredSection(threadId: Int) {
        if (isTestThread(threadId))
            ignoredSectionDepth[threadId]--
    }

    override fun beforeMethodCall(threadId: Int, codeLocation: Int) {
        if (isTestThread(threadId)) {
            check(loggingEnabled) { "This method should be called only when logging is enabled" }
            val callStackTrace = callStackTrace[threadId]
            val suspendedMethodStack = suspendedMethodStack[threadId]
            val methodId = if (suspendedMethodStack.isNotEmpty()) {
                // if there was a suspension before, then instead of creating a new identifier
                // use the one that the suspended call had
                val lastId = suspendedMethodStack.last()
                suspendedMethodStack.remove(suspendedMethodStack.lastIndex)
                lastId
            } else {
                methodIdentifier++
            }
            callStackTrace.add(CallStackTraceElement(getLocationDescription(codeLocation) as MethodCallCodeLocation, methodId))
        }
    }

    override fun afterMethodCall(threadId: Int, codeLocation: Int) {
        if (isTestThread(threadId)) {
            check(loggingEnabled) { "This method should be called only when logging is enabled" }
            val callStackTrace = callStackTrace[threadId]
            val methodCallCodeLocation = getLocationDescription(codeLocation) as MethodCallCodeLocation
            if (methodCallCodeLocation.returnedValue?.value == COROUTINE_SUSPENDED) {
                // if a method call is suspended, save its identifier to reuse for continuation resuming
                suspendedMethodStack[threadId].add(callStackTrace.lastIndex)
            }
            callStackTrace.removeAt(callStackTrace.lastIndex)
        }
    }

    override fun makeStateRepresentation(threadId: Int) {
        if (isTestThread(threadId) && !shouldBeIgnored(threadId)) {
            check(loggingEnabled) { "This method should be called only when logging is enabled" }
            eventCollector.makeStateRepresentation(threadId)
        }
    }

    private fun isTestThread(threadId: Int) = threadId < nThreads

    private fun shouldBeIgnored(threadId: Int) = ignoredSectionDepth[threadId] > 0

    /**
     * Create a new switch point, where a thread context switch can occur
     */
    protected fun newSwitchPoint(threadId: Int, codeLocation: Int) {
        if (threadId == nThreads) return // can suspend only test threads
        check(threadId == currentThread)
        if (ignoredSectionDepth[threadId] != 0) return // can not suspend in ignored sections
        awaitTurn(threadId)
        var isLoop = false
        if (loopDetector.newOperation(threadId, codeLocation)) {
            checkCanHaveObstruction { "At least obstruction freedom required, but an active lock found" }
            isLoop = true
        }
        val shouldSwitch = shouldSwitch(threadId) or isLoop
        if (shouldSwitch) {
            val reason = if (isLoop) SwitchReason.ACTIVE_LOCK else SwitchReason.STRATEGY_SWITCH
            switchCurrentThread(threadId, reason)
        }
        eventCollector.passCodeLocation(threadId, codeLocation)
        // continue operation
    }

    /**
     * Returns whether thread should switch at the suspension point
     */
    protected abstract fun shouldSwitch(threadId: Int): Boolean

    /**
     * A regular switch on another thread
     */
    protected fun switchCurrentThread(threadId: Int, reason: SwitchReason = SwitchReason.STRATEGY_SWITCH, mustSwitch: Boolean = false) {
        eventCollector.newSwitch(threadId, reason)
        onNewSwitch(threadId)
        doSwitchCurrentThread(threadId, mustSwitch)
        awaitTurn(threadId)
    }

    protected fun doSwitchCurrentThread(threadId: Int, mustSwitch: Boolean = false) {
        val switchableThreads = switchableThreads(threadId)
        if (switchableThreads.isEmpty()) {
            if (mustSwitch && !finished.all { it.get() }) {
                // all threads are suspended
                // then switch on any suspended thread to finish it and get SuspendedResult
                val nextThread = (0 until nThreads).firstOrNull { !finished[it].get() && isSuspended[it].get() }
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
     * Threads to which a thread [threadId] can switch
     */
    protected fun switchableThreads(threadId: Int) = (0 until nThreads).filter { it != threadId && canResume(it) }

    /**
     * Chooses a thread to switch among [switchableThreads] variants
     */
    protected abstract fun chooseThread(switchableThreads: Int): Int

    /**
     * Returns whether the thread could continue its execution
     */
    protected fun canResume(threadId: Int): Boolean {
        var canResume = !finished[threadId].get() && monitorTracker.canResume(threadId)
        if (isSuspended[threadId].get())
            canResume = canResume && runner.canResumeCoroutine(threadId, currentActorId[threadId])
        return canResume
    }

    /**
     * Waits until this thread is allowed to be executed.
     */
    protected fun awaitTurn(threadId: Int) {
        // wait actively until the thread is allow to execute
        while (currentThread != threadId) {
            // finish forcibly if an error occured and we already have an InvocationResult.
            if (suddenInvocationResult != null) throw ForcibleExecutionFinishException()
            Thread.yield()
        }
    }

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
            else -> {
                return results.toLincheckFailure(scenario, collectExecutionEvents(results))
            }
        }

        return null
    }

    /**
     * Runs next invocation with the same [scenario][ExecutionScenario].
     * @return invocation result for each executed actor.
     */
    fun runInvocation(): InvocationResult = doRunInvocation(false)

    /**
     * Reruns previous invocation to log all its execution events.
     */
    private fun collectExecutionEvents(previousResults: InvocationResult): List<InterleavingEvent>? {
        val detectedByStrategy = suddenInvocationResult != null
        val canCollectExecutionEvents = when {
            detectedByStrategy -> true // ObstructionFreedomViolationInvocationResult, UnexpectedExceptionInvocationResult or
            previousResults is CompletedInvocationResult -> true
            previousResults is ValidationFailureInvocationResult -> true
            else -> false
        }

        if (!canCollectExecutionEvents) {
            // runner can be broken during previous run
            // do not try to log anything
            return null
        }
        // retransform class with logging enabled
        loggingEnabled = true
        runner.transformTestClass()
        initializeManagedState()
        val loggedResults = doRunInvocation(true)
        val sameResultTypes = loggedResults.javaClass == previousResults.javaClass
        val sameExecutionResults = previousResults !is CompletedInvocationResult || loggedResults !is CompletedInvocationResult || previousResults.results == loggedResults.results
        check(sameResultTypes && sameExecutionResults) {
            StringBuilder().apply {
                appendln("Non-determinism found. Probably caused by non-deterministic code (WeakHashMap, Object.hashCode, etc).")
                appendln("Reporting scenario without execution trace.")
                appendln(loggedResults.asLincheckFailureWithoutTrace().toString())
            }.toString()
        }
        return eventCollector.interleavingEvents()
    }

    private fun InvocationResult.asLincheckFailureWithoutTrace(): LincheckFailure {
        if (this is CompletedInvocationResult)
            return IncorrectResultsFailure(scenario, results, null)
        return toLincheckFailure(scenario, null)
    }

    private fun doRunInvocation(repeatExecution: Boolean): InvocationResult {
        initializeInvocation(repeatExecution)
        val result = runner.run()
        // if strategy already determined invocation result, then return it instead
        if (suddenInvocationResult != null)
            return suddenInvocationResult!!
        return result
    }

    /**
     * Returns all data to the initial state before invocation.
     */
    protected open fun initializeInvocation(repeatExecution: Boolean = false) {
        finished.forEach { it.set(false) }
        isSuspended.forEach { it.set(false) }
        currentActorId.fill(-1)
        loopDetector = LoopDetector(testCfg.hangingDetectionThreshold)
        monitorTracker = MonitorTracker(nThreads)
        eventCollector = ExecutionEventCollector()
        suddenInvocationResult = null
        ignoredSectionDepth.fill(0)
        callStackTrace.forEach { it.clear() }
        suspendedMethodStack.forEach { it.clear() }
        ManagedStateHolder.resetState(runner.classLoader)
    }

    private fun checkCanHaveObstruction(lazyMessage: () -> String) {
        if (testCfg.checkObstructionFreedom) {
            suddenInvocationResult = ObstructionFreedomViolationInvocationResult(lazyMessage())
            // forcibly finish execution by throwing an exception.
            throw ForcibleExecutionFinishException()
        }
    }

    private fun checkLiveLockHappened(interleavingEventsCount: Int) {
        if (interleavingEventsCount > LIVELOCK_EVENTS_THRESHOLD) {
            suddenInvocationResult = DeadlockInvocationResult(collectThreadDump(runner))
            // forcibly finish execution by throwing an exception.
            throw ForcibleExecutionFinishException()
        }
    }

    override fun onActorStart(threadId: Int) {
        currentActorId[threadId]++
        loopDetector.reset(threadId) // visiting same code location in different actors is ok
    }

    /**
     * Detects loop when visiting a codeLocation too often.
     */
    private class LoopDetector(private val hangingDetectionThreshold: Int) {
        private var lastThreadId = -1 // no last thread
        private val operationCounts = mutableMapOf<Int, Int>()

        fun newOperation(threadId: Int, codeLocation: Int): Boolean {
            if (lastThreadId != threadId) {
                // if we switched threads then reset counts
                operationCounts.clear()
                lastThreadId = threadId
            }
            if (codeLocation == COROUTINE_SUSPENSION_CODE_LOCATION) return false;
            // increment the number of times that we visited a codelocation
            val count = (operationCounts[codeLocation] ?: 0) + 1
            operationCounts[codeLocation] = count
            // return true if the thread exceeded the maximum number of repetitions that we can have
            return count > hangingDetectionThreshold
        }

        fun reset(threadId: Int) {
            operationCounts.clear()
            lastThreadId = threadId
        }
    }

    /**
     * Logs thread events such as thread switches and passed code locations.
     */
    private inner class ExecutionEventCollector {
        private val interleavingEvents = mutableListOf<InterleavingEvent>()

        fun newSwitch(threadId: Int, reason: SwitchReason) {
            if (!loggingEnabled) return // check that should log thread events
            interleavingEvents.add(SwitchEvent(threadId, currentActorId[threadId], reason, callStackTrace[threadId].toList()))
            // check livelock after every switch
            checkLiveLockHappened(interleavingEvents.size)
        }

        fun finishThread(threadId: Int) {
            if (!loggingEnabled) return // check that should log thread events
            interleavingEvents.add(FinishEvent(threadId))
        }

        fun passCodeLocation(threadId: Int, codeLocation: Int) {
            if (!loggingEnabled) return // check that should log thread events
            if (codeLocation != COROUTINE_SUSPENSION_CODE_LOCATION) {
                enterIgnoredSection(threadId)
                interleavingEvents.add(PassCodeLocationEvent(
                        threadId, currentActorId[threadId],
                        getLocationDescription(codeLocation),
                        callStackTrace[threadId].toList()
                ))
                leaveIgnoredSection(threadId)
            }
        }

        fun makeStateRepresentation(threadId: Int) {
            // enter ignored section, because stateRepresentation invokes transformed method with switch points
            enterIgnoredSection(threadId)
            val stateRepresentation = runner.stateRepresentation
            leaveIgnoredSection(threadId)
            interleavingEvents.add(StateRepresentationEvent(threadId, currentActorId[threadId], stateRepresentation))
        }

        fun interleavingEvents(): List<InterleavingEvent> = interleavingEvents
    }

    /**
     * Track operations with monitor (acquire/release, wait/notify) to tell whether a thread can be executed.
     */
    private class MonitorTracker(nThreads: Int) {
        // which monitors are held by test threads
        private val acquiredMonitors = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        // which monitor a thread want to acquire (or null)
        private val acquiringMonitor = Array<Any?>(nThreads) { null }
        // whether thread is waiting for notify on the corresponding monitor
        private val needsNotification = BooleanArray(nThreads) { false }

        fun canAcquireMonitor(monitor: Any) = monitor !in acquiredMonitors

        fun acquireMonitor(threadId: Int, monitor: Any) {
            acquiredMonitors.add(monitor)
            acquiringMonitor[threadId] = null
        }

        fun releaseMonitor(monitor: Any) {
            acquiredMonitors.remove(monitor)
        }

        fun canResume(threadId: Int): Boolean {
            val monitor = acquiringMonitor[threadId] ?: return true
            return !needsNotification[threadId] && canAcquireMonitor(monitor)
        }

        fun awaitAcquiringMonitor(threadId: Int, monitor: Any) {
            acquiringMonitor[threadId] = monitor
        }

        fun waitMonitor(threadId: Int, monitor: Any) {
            // TODO: can add spurious wakeups
            check(monitor in acquiredMonitors) { "Monitor should have been acquired by this thread" }
            releaseMonitor(monitor)
            needsNotification[threadId] = true
            awaitAcquiringMonitor(threadId, monitor)
        }

        fun notify(monitor: Any) {
            // just notify all. odd threads will have a spurious wakeup
            notifyAll(monitor)
        }

        fun notifyAll(monitor: Any) {
            for (threadId in needsNotification.indices)
                if (acquiringMonitor[threadId] === monitor)
                    needsNotification[threadId] = false
        }
    }
}

private const val COROUTINE_SUSPENSION_CODE_LOCATION = -1; // currently the exact place of coroutine suspension is not known