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

import kotlinx.atomicfu.*
import org.jetbrains.kotlinx.lincheck.collectThreadDump
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration.Companion.LIVELOCK_EVENTS_THRESHOLD
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.lang.RuntimeException
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
    private val isSuspended = AtomicBooleanArray(nThreads)
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

    override fun onStart(iThread: Int) {
        awaitTurn(iThread)
    }

    override fun onFinish(iThread: Int) {
        awaitTurn(iThread)
        finished[iThread].set(true)
        eventCollector.finishThread(iThread)
        doSwitchCurrentThread(iThread, true)
    }

    override fun onFailure(iThread: Int, e: Throwable) {
        if (suddenInvocationResult == null) // not a forcible execution finish
            suddenInvocationResult = UnexpectedExceptionInvocationResult(e)
    }

    /**
     * Is executed before any thread switch
     */
    protected open fun onNewSwitch(iThread: Int, mustSwitch: Boolean) {}

    override fun beforeSharedVariableRead(iThread: Int, codeLocation: Int) {
        newSwitchPoint(iThread, codeLocation)
    }

    override fun beforeSharedVariableWrite(iThread: Int, codeLocation: Int) {
        newSwitchPoint(iThread, codeLocation)
    }

    override fun beforeAtomicMethodCall(iThread: Int, codeLocation: Int) {
        newSwitchPoint(iThread, codeLocation)
    }

    override fun beforeLockAcquire(iThread: Int, codeLocation: Int, monitor: Any): Boolean {
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

    override fun beforeLockRelease(iThread: Int, codeLocation: Int, monitor: Any): Boolean {
        if (!isTestThread(iThread)) return true
        monitorTracker.releaseMonitor(monitor)
        eventCollector.passCodeLocation(iThread, codeLocation, codePoints.lastIndex)
        return false
    }

    override fun beforePark(iThread: Int, codeLocation: Int, withTimeout: Boolean): Boolean {
        if (!isTestThread(iThread)) return true
        newSwitchPoint(iThread, codeLocation)
        return false
    }

    override fun afterUnpark(iThread: Int, codeLocation: Int, thread: Any) {
        eventCollector.passCodeLocation(iThread, codeLocation, codePoints.lastIndex)
    }

    override fun beforeWait(iThread: Int, codeLocation: Int, monitor: Any, withTimeout: Boolean): Boolean {
        if (!isTestThread(iThread)) return true
        failIfObstructionFreedomIsRequired { "Obstruction-freedom is required but a waiting on monitor block has been found" }
        newSwitchPoint(iThread, codeLocation)
        if (withTimeout) return false // timeouts occur instantly
        monitorTracker.waitMonitor(iThread, monitor)
        // switch to another thread and wait till a notify event happens
        switchCurrentThread(iThread, SwitchReason.MONITOR_WAIT, true)
        return false
    }

    override fun afterNotify(iThread: Int, codeLocation: Int, monitor: Any, notifyAll: Boolean) {
        if (notifyAll)
            monitorTracker.notifyAll(monitor)
        else
            monitorTracker.notify(monitor)
        eventCollector.passCodeLocation(iThread, codeLocation, codePoints.lastIndex)
    }

    override fun afterCoroutineSuspended(iThread: Int) {
        check(currentThread == iThread)
        isSuspended[iThread].value = true
        if (runner.canResumeCoroutine(iThread, currentActorId[iThread])) {
            // COROUTINE_SUSPENSION_CODELOCATION, because we do not know the actual code location
            newSwitchPoint(iThread, COROUTINE_SUSPENSION_CODE_LOCATION)
        } else {
            // coroutine suspension does not violate obstruction-freedom
            switchCurrentThread(iThread, SwitchReason.SUSPENDED, true)
        }
    }

    override fun afterCoroutineResumed(iThread: Int) {
        check(currentThread == iThread)
        isSuspended[iThread].value = false
    }

    override fun afterCoroutineCancelled(iThread: Int) {
        check(currentThread == iThread)
        isSuspended[iThread].value = false
        // method will not be resumed after suspension, so clear prepared for resume call stack
        suspendedMethodStack[iThread].clear()
    }

    override fun enterIgnoredSection(iThread: Int) {
        if (isTestThread(iThread))
            ignoredSectionDepth[iThread]++
    }

    override fun leaveIgnoredSection(iThread: Int) {
        if (isTestThread(iThread))
            ignoredSectionDepth[iThread]--
    }

    override fun beforeMethodCall(iThread: Int, codeLocation: Int) {
        if (isTestThread(iThread)) {
            check(loggingEnabled) { "This method should be called only when logging is enabled" }
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

    override fun afterMethodCall(iThread: Int, codePoint: Int) {
        if (isTestThread(iThread)) {
            check(loggingEnabled) { "This method should be called only when logging is enabled" }
            val callStackTrace = callStackTrace[iThread]
            val methodCallCodeLocation = getCodePoint(codePoint) as MethodCallCodePoint
            if (methodCallCodeLocation.returnedValue?.value == COROUTINE_SUSPENDED) {
                // if a method call is suspended, save its identifier to reuse for continuation resuming
                suspendedMethodStack[iThread].add(callStackTrace.last().identifier)
            }
            callStackTrace.removeAt(callStackTrace.lastIndex)
        }
    }

    override fun makeStateRepresentation(iThread: Int) {
        if (!inIgnoredSection(iThread)) {
            check(loggingEnabled) { "This method should be called only when logging is enabled" }
            eventCollector.makeStateRepresentation(iThread)
        }
    }

    private fun isTestThread(iThread: Int) = iThread < nThreads

    private fun inIgnoredSection(iThread: Int): Boolean = !isTestThread(iThread) || ignoredSectionDepth[iThread] > 0

    /**
     * Create a new switch point, where a thread context switch can occur
     */
    protected fun newSwitchPoint(iThread: Int, codeLocation: Int) {
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
     * Returns whether thread should switch at the switch point
     */
    protected abstract fun shouldSwitch(iThread: Int): Boolean

    /**
     * A regular switch on another thread
     */
    protected fun switchCurrentThread(iThread: Int, reason: SwitchReason = SwitchReason.STRATEGY_SWITCH, mustSwitch: Boolean = false) {
        eventCollector.newSwitch(iThread, reason)
        doSwitchCurrentThread(iThread, mustSwitch)
        awaitTurn(iThread)
    }

    protected fun doSwitchCurrentThread(iThread: Int, mustSwitch: Boolean = false) {
        onNewSwitch(iThread, mustSwitch)
        val switchableThreads = switchableThreads(iThread)
        if (switchableThreads.isEmpty()) {
            if (mustSwitch && !finished.all { it.get() }) {
                // all threads are suspended
                // then switch on any suspended thread to finish it and get SuspendedResult
                val nextThread = (0 until nThreads).firstOrNull { !finished[it].get() && isSuspended[it].value }
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

    /**
     * Chooses a thread to switch among [switchableThreads] variants
     */
    protected abstract fun chooseThread(switchableThreads: Int): Int

    /**
     * Returns whether the thread could continue its execution
     */
    protected fun canResume(iThread: Int): Boolean {
        var canResume = !finished[iThread].get() && monitorTracker.canResume(iThread)
        if (isSuspended[iThread].value)
            canResume = canResume && runner.canResumeCoroutine(iThread, currentActorId[iThread])
        return canResume
    }

    /**
     * Waits until this thread is allowed to be executed.
     */
    protected fun awaitTurn(iThread: Int) {
        // wait actively until the thread is allow to execute
        while (currentThread != iThread) {
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
            detectedByStrategy -> true // ObstructionFreedomViolationInvocationResult or UnexpectedExceptionInvocationResult
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
        runner.createClassLoader()
        initializeManagedState()
        runner.transformTestClass()
        val loggedResults = doRunInvocation(true)
        val sameResultTypes = loggedResults.javaClass == previousResults.javaClass
        // cannot check whether the results are exactly the same because of retransformation
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
        (0 until nThreads).forEach { isSuspended[it].value = false }
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

    private fun failIfObstructionFreedomIsRequired(lazyMessage: () -> String) {
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

    override fun onActorStart(iThread: Int) {
        currentActorId[iThread]++
        callStackTrace[iThread].clear()
        suspendedMethodStack[iThread].clear()
        loopDetector.reset(iThread) // visiting same code location in different actors is ok
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
            // increment the number of times that we visited a codelocation
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
     * Logs thread events such as thread switches and passed code locations.
     */
    private inner class ExecutionEventCollector {
        private val interleavingEvents = mutableListOf<InterleavingEvent>()

        fun newSwitch(iThread: Int, reason: SwitchReason) {
            if (!loggingEnabled) return // check that should log thread events
            interleavingEvents.add(SwitchEvent(iThread, currentActorId[iThread], reason, callStackTrace[iThread].toList()))
            // check livelock after every switch
            checkLiveLockHappened(interleavingEvents.size)
        }

        fun finishThread(iThread: Int) {
            if (!loggingEnabled) return // check that should log thread events
            interleavingEvents.add(FinishEvent(iThread))
        }

        fun passCodeLocation(iThread: Int, codeLocation: Int, codePoint: Int) {
            if (!loggingEnabled) return // check that should log thread events
            if (codeLocation != COROUTINE_SUSPENSION_CODE_LOCATION) {
                interleavingEvents.add(PassCodeLocationEvent(
                        iThread, currentActorId[iThread],
                        getCodePoint(codePoint),
                        callStackTrace[iThread].toList()
                ))
            }
        }

        fun makeStateRepresentation(iThread: Int) {
            if (!loggingEnabled) return // check that should log thread events
            // enter ignored section, because stateRepresentation invokes transformed method with switch points
            enterIgnoredSection(iThread)
            val stateRepresentation = runner.constructStateRepresentation()
            leaveIgnoredSection(iThread)
            interleavingEvents.add(StateRepresentationEvent(iThread, currentActorId[iThread], stateRepresentation, callStackTrace[iThread].toList()))
        }

        fun interleavingEvents(): List<InterleavingEvent> = interleavingEvents
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
}

/**
 * This exception is used to finish the execution correctly for managed strategies.
 * Otherwise, there is no way to do it in case of (e.g.) deadlocks.
 * If we just leave it, then the execution will not be halted.
 * If we forcibly pass through all barriers, then we can get another exception due to being in an incorrect state.
 */
internal class ForcibleExecutionFinishException : RuntimeException()

private const val COROUTINE_SUSPENSION_CODE_LOCATION = -1; // currently the exact place of coroutine suspension is not known