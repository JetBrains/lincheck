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


// TODO do we need to split ManagedStrategyBase and ManagedStrategy?
abstract class ManagedStrategyBase(
        testClass: Class<*>,
        scenario: ExecutionScenario,
        verifier: Verifier,
        reporter: Reporter,
        maxRepetitions: Int,
        protected val canHaveLocks: Boolean
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
    // number of clinit blocks enter and not leaved for each thread
    protected val classInitializationLevel = IntArray(nThreads) { 0 }

    @Throws(Exception::class)
    abstract override fun run(): Unit

    override fun onStart(iThread: Int) {
        awaitTurn(iThread)
    }

    override fun onFinish(iThread: Int) {
        awaitTurn(iThread)
        finishThread(iThread)
    }

    override fun onException(iThread: Int, e: Throwable) {
        exception = e
    }

    /**
     * Is executed before any thread switch
     */
    protected open fun onNewSwitch() {}

    override fun beforeSharedVariableRead(iThread: Int, codeLocation: Int) {
        newSuspensionPoint(iThread, codeLocation)
    }

    override fun beforeSharedVariableWrite(iThread: Int, codeLocation: Int) {
        newSuspensionPoint(iThread, codeLocation)
    }

    override fun beforeLockAcquire(iThread: Int, codeLocation: Int, monitor: Any): Boolean {
        if (iThread == nThreads) return true

        assert(canHaveLocks) { "At least obstruction freedom required but a lock found" }

        newSuspensionPoint(iThread, codeLocation)

        awaitTurn(iThread)
        if (!monitorTracker.canAcquireMonitor(monitor)) {
            monitorTracker.awaitAcquiringMonitor(iThread, monitor)
            switchCurrentThread(iThread, codeLocation, "lock is already acquired")
        }

        monitorTracker.acquireMonitor(iThread, monitor)

        return false
    }

    override fun beforeLockRelease(iThread: Int, codeLocation: Int, monitor: Any): Boolean {
        if (iThread == nThreads) return true

        awaitTurn(iThread)
        monitorTracker.releaseMonitor(monitor)
        return false
    }

    override fun beforePark(iThread: Int, codeLocation: Int, withTimeout: Boolean): Boolean {
        return iThread == nThreads
    }

    override fun afterUnpark(iThread: Int, codeLocation: Int, thread: Any) {}

    override fun beforeWait(iThread: Int, codeLocation: Int, monitor: Any, withTimeout: Boolean): Boolean {
        if (iThread == nThreads) return true

        assert(canHaveLocks) { "At least obstruction freedom required but a waiting on monitor found" }

        newSuspensionPoint(iThread, codeLocation)

        awaitTurn(iThread)
        monitorTracker.waitMonitor(iThread, monitor)
        switchCurrentThread(iThread, codeLocation, "wait on monitor")

        return false
    }

    override fun afterNotify(iThread: Int, codeLocation: Int, monitor: Any, notifyAll: Boolean) {
        if (notifyAll)
            monitorTracker.notifyAll(monitor)
        else
            monitorTracker.notify(monitor)
    }

    override fun afterThreadInterrupt(iThread: Int, codeLocation: Int, iInterruptedThread: Int) {}

    override fun afterCoroutineSuspended(iThread: Int) {
        check(currentThread == iThread)
        isSuspended[iThread].set(true)
        if (runner.canResumeCoroutine(iThread, getCurrentActorId(iThread))) {
            newSuspensionPoint(iThread, -1)
        } else {
            // Currently a suspension point does not supposed to violate obstruction-freedom
            // assert(canHaveLocks) { "At least obstruction freedom required but a loop found" }
            switchCurrentThread(iThread, -1)
        }
    }

    override fun beforeCoroutineResumed(iThread: Int) {
        check(currentThread == iThread)
        isSuspended[iThread].set(false)
    }

    override fun beforeClassInitialization(iThread: Int) {
        classInitializationLevel[iThread]++
    }

    override fun afterClassInitialization(iThread: Int) {
        classInitializationLevel[iThread]--
    }

    protected fun newSuspensionPoint(iThread: Int, codeLocation: Int) {
        if (iThread == nThreads) return // can suspend only test threads
        if (classInitializationLevel[iThread] == 0) return // can not suspend in static initialization blocks

        awaitTurn(iThread)

        var isLoop = false

        if (loopDetector.newOperation(iThread, codeLocation)) {
            assert(canHaveLocks) { "At least obstruction freedom required but an active lock found" }
            isLoop = true
        }

        val shouldSwitch = isLoop or shouldSwitch(iThread)

        if (shouldSwitch) {
            val reason = if (isLoop) "active lock detected" else ""
            switchCurrentThread(iThread, codeLocation, reason)
        }

        eventLogger.passCodeLocation(iThread, codeLocation)

        // continue operation
    }

    /**
     * Returns whether thread should switch at the suspension point
     */
    protected abstract fun shouldSwitch(iThread: Int): Boolean

    /**
     * Switch due to thread finish
     */
    protected fun finishThread(iThread: Int) {
        finished[iThread].set(true)

        eventLogger.finishThread(iThread)
        onNewSwitch()

        doSwitchCurrentThread(iThread, true)
    }

    /**
     * Regular switch on another thread
     */
    protected fun switchCurrentThread(iThread: Int, codeLocation: Int, reason: String = "") {
        eventLogger.newSwitch(iThread, codeLocation, reason)
        onNewSwitch()
        doSwitchCurrentThread(iThread)

        awaitTurn(iThread)
    }

    private fun doSwitchCurrentThread(iThread: Int, mustSwitch: Boolean = false) {
        var switchableThreads = 0

        for (i in 0 until nThreads)
            if (i != iThread && canResume(i))
                switchableThreads++

        if (switchableThreads == 0) {
            if (mustSwitch && !finished.all { it.get() }) {
                // all threads are suspended
                // then switch on any suspended thread to finish it and get SuspendedResult
                val nextThread = (0 until nThreads).filter { !finished[it].get() && isSuspended[it].get() }.firstOrNull()

                if (nextThread == null) {
                    val exception = AssertionError("Must switch not to get into deadlock, but there are no threads to switch")
                    onException(iThread, exception)
                    throw exception
                }

                currentThread = nextThread
            }
            return // ignore switch, because there is no one to switch to
        }

        var nextThreadNumber = executionRandom.nextInt(switchableThreads)

        for (i in 0 until nThreads)
            if (i != iThread && canResume(i)) {
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
    protected fun canResume(iThread: Int): Boolean {
        var canResume = !finished[iThread].get() && monitorTracker.canResume(iThread)

        if (isSuspended[iThread].get())
            canResume = canResume && runner.canResumeCoroutine(iThread, getCurrentActorId(iThread))

        return canResume
    }

    /**
     * Wait untill this thread is allowed to be executed
     */
    protected fun awaitTurn(iThread: Int) {
        while (currentThread != iThread) {
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
        loopDetector.reset()
        exception = null
    }

    protected fun restoreRandom() {
        executionRandom = executionRandomCopy;
    }

    /**
     * Returns number of current executing actor
     */
    protected fun getCurrentActorId(iThread: Int): Int {
        // can use binary search here, but for small number of actors (usual case) this code would perform better

        for ((i, actor) in parallelActors[iThread].withIndex().reversed())
            if (actor.wasInvoked)
                return i

        return -1
    }

    /**
     * Detects loop if we visit a codeLocation too often
     */
    protected class LoopDetector(val maxRepetitions: Int) {
        private var lastIThread = Int.MIN_VALUE
        private val operationCounts = mutableMapOf<Int, Int>()

        fun newOperation(iThread: Int, codeLocation: Int): Boolean {
            if (lastIThread != iThread) {
                operationCounts.clear()
                lastIThread = iThread
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
        fun newSwitch(iThread: Int, codeLocation: Int, reason: String)
        fun finishThread(iThread: Int)
        fun passCodeLocation(iThread: Int, codeLocation: Int)

        fun threadEvents(): List<ThreadEvent>
    }

    /**
     * Just ignores every event
     */
    protected object DummyEventLogger : ThreadEventLogger {
        override fun newSwitch(iThread: Int, codeLocation: Int, reason: String) {
            // ignore
        }

        override fun finishThread(iThread: Int) {
            // ignore
        }

        override fun passCodeLocation(iThread: Int, codeLocation: Int) {
            // ignore
        }

        override fun threadEvents(): List<ThreadEvent> = emptyList()
    }

    /**
     * Implementation of ThreadEventLogger
     */
    protected inner class SimpleEventLogger : ThreadEventLogger {
        private val threadEvents = mutableListOf<ThreadEvent>()

        override fun newSwitch(iThread: Int, codeLocation: Int, reason: String) {
            val actorId = getCurrentActorId(iThread)
            if (codeLocation != -1)
                threadEvents.add(SwitchEvent(iThread, actorId, getLocationDescription(codeLocation)))
            else
                threadEvents.add(SuspendSwitchEvent(iThread, actorId))
        }

        override fun finishThread(iThread: Int) {
            threadEvents.add(FinishEvent(iThread, Int.MAX_VALUE))
        }

        override fun passCodeLocation(iThread: Int, codeLocation: Int) {
            threadEvents.add(PassCodeLocationEvent(iThread, getCurrentActorId(iThread), getLocationDescription(codeLocation)))
        }

        override fun threadEvents(): List<ThreadEvent> = threadEvents
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

        fun acquireMonitor(iThread: Int, monitor: Any) {
            acquiredMonitors.add(monitor)
            acquiringMonitor[iThread] = null
        }

        fun releaseMonitor(monitor: Any) {
            acquiredMonitors.remove(monitor)
        }

        fun canResume(iThread: Int): Boolean {
            val monitor = acquiringMonitor[iThread] ?: return true
            return !needsNotification[iThread] && canAcquireMonitor(monitor)
        }

        fun awaitAcquiringMonitor(iThread: Int, monitor: Any) {
            acquiringMonitor[iThread] = monitor
        }

        fun waitMonitor(iThread: Int, monitor: Any) {
            // TODO: support timeouts for wait
            // they can break reproducability of an execution and thus were not added now.
            // TODO: can add spurious wakeups
            check(monitor in acquiredMonitors) { "Monitor should have been acquired by this thread" }
            releaseMonitor(monitor)
            needsNotification[iThread] = true
            awaitAcquiringMonitor(iThread, monitor)
        }

        fun notify(monitor: Any) {
            // just notify all. odd threads will have a spurious wakeup
            notifyAll(monitor)
        }

        fun notifyAll(monitor: Any) {
            for (iThread in needsNotification.indices)
                if (acquiringMonitor[iThread] === monitor)
                    needsNotification[iThread] = false
        }

        // TODO: add tests on wait-notify using custom scenarios
    }
}
