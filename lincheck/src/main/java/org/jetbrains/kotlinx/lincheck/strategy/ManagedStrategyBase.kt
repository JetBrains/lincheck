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
package org.jetbrains.kotlinx.lincheck.strategy

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.util.PseudoRandom
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.lang.IllegalStateException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.IdentityHashMap

abstract class ManagedStrategyBase(
        testClass: Class<*>,
        scenario: ExecutionScenario,
        verifier: Verifier,
        reporter: Reporter,
        maxRepetitions: Int,
        protected val requireObstructionFreedom: Boolean
) : ManagedStrategy(testClass, scenario, verifier, reporter) {
    protected val parallelActors: List<List<Actor>> = scenario.parallelExecution

    // whether a thread finished all its operations
    protected val finished: Array<AtomicBoolean> = Array(nThreads) { AtomicBoolean(false) }
    // what thread is currently allowed to perform operations
    @Volatile
    protected var currentThread: Int = 0
    // detector of loops (i.e. active locks)
    protected val loopDetector = LoopDetector(maxRepetitions)
    // logger of all events in the execution such as thread switches
    protected var eventLogger: ThreadEventLogger = DummyEventLogger
    // tracker of acquisitions and releases of monitors
    protected val monitorTracker = MonitorTracker(nThreads)
    // random used for execution
    protected var executionRandom = PseudoRandom()
    // copy of random used for execution for reproducability of results
    protected var executionRandomCopy = executionRandom
    // unhandled exception thrown by testClass
    @Volatile
    protected var exception: Throwable? = null
    // is thread suspended
    protected val isSuspended: Array<AtomicBoolean> = Array(nThreads) { AtomicBoolean(false) }
    // number of clinit blocks entered and not leaved for each thread
    protected val classInitializationLevel = IntArray(nThreads) { 0 }
    // current actor id for each thread
    protected val currentActorId = IntArray(nThreads)

    @Throws(Exception::class)
    abstract override fun run(): Unit

    override fun onStart(threadId: Int) {
        awaitTurn(threadId)
    }

    override fun onFinish(threadId: Int) {
        awaitTurn(threadId)
        finishThread(threadId)
    }

    override fun onFailure(threadId: Int, e: Throwable) {
        exception = e
    }

    /**
     * Is executed before any thread switch
     */
    protected open fun onNewSwitch() {}

    override fun beforeSharedVariableRead(threadId: Int, codeLocation: Int) {
        newSuspensionPoint(threadId, codeLocation)
    }

    override fun beforeSharedVariableWrite(threadId: Int, codeLocation: Int) {
        newSuspensionPoint(threadId, codeLocation)
    }

    override fun beforeLockAcquire(threadId: Int, codeLocation: Int, monitor: Any): Boolean {
        if (threadId == nThreads) return true

        checkCanHaveObstruction { "At least obstruction freedom required but a lock found" }

        newSuspensionPoint(threadId, codeLocation)

        awaitTurn(threadId)
        if (!monitorTracker.canAcquireMonitor(monitor)) {
            monitorTracker.awaitAcquiringMonitor(threadId, monitor)
            // switch to another thread and wait for a moment the monitor can be acquired
            switchCurrentThread(threadId, codeLocation, SwitchReason.LOCK_WAIT)
        }

        monitorTracker.acquireMonitor(threadId, monitor)

        return false
    }

    override fun beforeLockRelease(threadId: Int, codeLocation: Int, monitor: Any): Boolean {
        if (threadId == nThreads) return true

        awaitTurn(threadId)
        monitorTracker.releaseMonitor(monitor)
        return false
    }

    override fun beforePark(threadId: Int, codeLocation: Int, withTimeout: Boolean): Boolean {
        return threadId == nThreads
    }

    override fun afterUnpark(threadId: Int, codeLocation: Int, thread: Any) {}

    override fun beforeWait(threadId: Int, codeLocation: Int, monitor: Any, withTimeout: Boolean): Boolean {
        if (threadId == nThreads) return true

        checkCanHaveObstruction { "At least obstruction freedom required but a waiting on monitor found" }

        newSuspensionPoint(threadId, codeLocation)

        if (withTimeout) return false // timeout occur instantly

        awaitTurn(threadId)
        monitorTracker.waitMonitor(threadId, monitor)
        // switch to another thread and wait till a notify event happens
        switchCurrentThread(threadId, codeLocation, SwitchReason.MONITOR_WAIT)

        return false
    }

    override fun afterNotify(threadId: Int, codeLocation: Int, monitor: Any, notifyAll: Boolean) {
        if (notifyAll)
            monitorTracker.notifyAll(monitor)
        else
            monitorTracker.notify(monitor)
    }

    override fun afterThreadInterrupt(threadId: Int, codeLocation: Int, iInterruptedThread: Int) {}

    override fun afterCoroutineSuspended(threadId: Int) {
        check(currentThread == threadId)
        isSuspended[threadId].set(true)
        if (runner.canResumeCoroutine(threadId, currentActorId[threadId])) {
            newSuspensionPoint(threadId, -1)
        } else {
            // Currently a suspension point does not supposed to violate obstruction-freedom
            // checkCanHaveObstruction { "At least obstruction freedom required but a loop found" }
            switchCurrentThread(threadId, -1)
        }
    }

    override fun beforeCoroutineResumed(threadId: Int) {
        check(currentThread == threadId)
        isSuspended[threadId].set(false)
    }

    override fun beforeClassInitialization(threadId: Int) {
        if (threadId < nThreads)
            classInitializationLevel[threadId]++
    }

    override fun afterClassInitialization(threadId: Int) {
        if (threadId < nThreads)
            classInitializationLevel[threadId]--
    }

    protected fun newSuspensionPoint(threadId: Int, codeLocation: Int) {
        if (threadId == nThreads) return // can suspend only test threads
        if (classInitializationLevel[threadId] != 0) return // can not suspend in static initialization blocks

        awaitTurn(threadId)

        var isLoop = false

        if (loopDetector.newOperation(threadId, codeLocation)) {
            checkCanHaveObstruction { "At least obstruction freedom required but an active lock found" }
            isLoop = true
        }

        val shouldSwitch = isLoop or shouldSwitch(threadId)

        if (shouldSwitch) {
            val reason = if (isLoop) SwitchReason.ACTIVE_LOCK else SwitchReason.STRATEGY_SWITCH
            switchCurrentThread(threadId, codeLocation, reason)
        }

        eventLogger.passCodeLocation(threadId, codeLocation)

        // continue operation
    }

    /**
     * Returns whether thread should switch at the suspension point
     */
    protected abstract fun shouldSwitch(threadId: Int): Boolean

    /**
     * Switch due to thread finish
     */
    protected fun finishThread(threadId: Int) {
        finished[threadId].set(true)

        eventLogger.finishThread(threadId)
        onNewSwitch()

        doSwitchCurrentThread(threadId, true)
    }

    /**
     * Regular switch on another thread
     */
    protected fun switchCurrentThread(threadId: Int, codeLocation: Int, reason: SwitchReason = SwitchReason.STRATEGY_SWITCH) {
        eventLogger.newSwitch(threadId, codeLocation, reason)
        onNewSwitch()
        doSwitchCurrentThread(threadId)

        awaitTurn(threadId)
    }

    private fun doSwitchCurrentThread(threadId: Int, mustSwitch: Boolean = false) {
        var switchableThreads = 0

        for (i in 0 until nThreads)
            if (i != threadId && canResume(i))
                switchableThreads++

        if (switchableThreads == 0) {
            if (mustSwitch && !finished.all { it.get() }) {
                // all threads are suspended
                // then switch on any suspended thread to finish it and get SuspendedResult
                val nextThread = (0 until nThreads).filter { !finished[it].get() && isSuspended[it].get() }.firstOrNull()

                if (nextThread == null) {
                    val exception = AssertionError("Must switch not to get into deadlock, but there are no threads to switch")
                    onFailure(threadId, exception)
                    throw exception
                }

                currentThread = nextThread
            }
            return // ignore switch, because there is no one to switch to
        }

        var nextThreadNumber = executionRandom.nextInt(switchableThreads)

        for (i in 0 until nThreads)
            if (i != threadId && canResume(i)) {
                nextThreadNumber--

                if (nextThreadNumber < 0) {
                    currentThread = i
                    break
                }
            }
    }

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
     * Waits until this thread is allowed to be executed
     */
    protected fun awaitTurn(threadId: Int) {
        while (currentThread != threadId) {
            // just wait actively
            // check to avoid deadlock due to unhandled exception
            if (exception != null) throw exception!!
        }
    }

    /**
     * Verifies results and if there are incorrect results then re-runs logging
     * all thread events
     */
    protected fun checkResults(results: ExecutionResult) {
        if (!verifier.verifyResults(results)) {
            // re-run execution to get all thread events
            eventLogger = SimpleEventLogger()
            restoreRandom()
            val repeatedResults = runInvocation()
            require(repeatedResults == results) {
                "Indeterminism found. The execution should have returned the same result, but did not"
            }

            verifyResults(results, eventLogger.threadEvents())

            throw IllegalStateException("Should not reach. verifyResults should throw AssertionError")
        }
    }

    /**
     * Runs invocation using runner
     */
    protected fun runInvocation(): ExecutionResult {
        initializeInvocation()
        return runner.run()
    }

    /**
     * Returns all data to the initial state before invocation
     */
    protected open fun initializeInvocation() {
        finished.forEach { it.set(false) }
        isSuspended.forEach { it.set(false) }
        // save previous random state
        executionRandomCopy = executionRandom.copy()
        // start from random thread
        currentThread = executionRandom.nextInt(nThreads)
        currentActorId.fill(-1)
        loopDetector.reset()
        exception = null
    }

    protected fun restoreRandom() {
        executionRandom = executionRandomCopy;
    }

    protected fun checkCanHaveObstruction(lazyMessage: () -> String) {
        if (requireObstructionFreedom)
            throw IntendedExecutionException(AssertionError(lazyMessage()))
    }

    override fun onActorStart(threadId: Int) {
        currentActorId[threadId]++
    }

    /**
     * Detects loop when visiting a codeLocation too often
     */
    protected class LoopDetector(val maxRepetitions: Int) {
        private var lastIThread = Int.MIN_VALUE
        private val operationCounts = mutableMapOf<Int, Int>()

        fun newOperation(threadId: Int, codeLocation: Int): Boolean {
            if (lastIThread != threadId) {
                operationCounts.clear()
                lastIThread = threadId
            }

            val count = (operationCounts[codeLocation] ?: 0) + 1

            operationCounts[codeLocation] = count

            return count > maxRepetitions
        }

        fun reset() {
            lastIThread = Int.MIN_VALUE
        }
    }

    /**
     * Logs thread events such as thread switches and passed codeLocations
     */
    protected interface ThreadEventLogger {
        fun newSwitch(threadId: Int, codeLocation: Int, reason: SwitchReason)
        fun finishThread(threadId: Int)
        fun passCodeLocation(threadId: Int, codeLocation: Int)

        fun threadEvents(): List<InterleavingEvent>
    }

    /**
     * Just ignores every event
     */
    protected object DummyEventLogger : ThreadEventLogger {
        override fun newSwitch(threadId: Int, codeLocation: Int, reason: SwitchReason) {
            // ignore
        }

        override fun finishThread(threadId: Int) {
            // ignore
        }

        override fun passCodeLocation(threadId: Int, codeLocation: Int) {
            // ignore
        }

        override fun threadEvents(): List<InterleavingEvent> = emptyList()
    }

    /**
     * Implementation of ThreadEventLogger
     */
    protected inner class SimpleEventLogger : ThreadEventLogger {
        private val threadEvents = mutableListOf<InterleavingEvent>()

        override fun newSwitch(threadId: Int, codeLocation: Int, reason: SwitchReason) {
            val actorId = currentActorId[threadId]
            if (codeLocation != -1)
                threadEvents.add(SwitchEvent(threadId, actorId, getLocationDescription(codeLocation), reason))
            else
                threadEvents.add(SuspendSwitchEvent(threadId, actorId))
        }

        override fun finishThread(threadId: Int) {
            threadEvents.add(FinishEvent(threadId, parallelActors[threadId].size))
        }

        override fun passCodeLocation(threadId: Int, codeLocation: Int) {
            threadEvents.add(PassCodeLocationEvent(threadId, currentActorId[threadId], getLocationDescription(codeLocation)))
        }

        override fun threadEvents(): List<InterleavingEvent> = threadEvents
    }

    /**
     * Track operations with monitor (acquire/release, wait/notify) to tell whether a thread can be executed
     */
    protected class MonitorTracker(nThreads: Int) {
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

        // TODO: add tests on wait-notify using custom scenarios
    }
}
