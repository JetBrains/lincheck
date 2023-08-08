/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.jetbrains.kotlinx.lincheck.*
import java.lang.reflect.*
import kotlin.reflect.KClass

class EventStructureStrategy(
        testCfg: EventStructureCTestConfiguration,
        testClass: Class<*>,
        scenario: ExecutionScenario,
        validationFunctions: List<Method>,
        stateRepresentation: Method?,
        verifier: Verifier
) : ManagedStrategy(testClass, scenario, verifier, validationFunctions, stateRepresentation, testCfg,
                    memoryTrackingEnabled = true) {
    // The number of invocations that the strategy is eligible to use to search for an incorrect execution.
    private val maxInvocations = testCfg.invocationsPerIteration

    private val sequentialConsistencyChecker: SequentialConsistencyChecker =
        SequentialConsistencyChecker(
            checkReleaseAcquireConsistency = true,
            approximateSequentialConsistency = false
        )

    private val atomicityChecker: AtomicityChecker =
        AtomicityChecker()

    private val eventStructure: EventStructure =
        EventStructure(
            nThreads,
            sequentialConsistencyChecker,
            atomicityChecker,
            memoryInitializer,
        )

    // Tracker of shared memory accesses.
    override val memoryTracker: MemoryTracker = EventStructureMemoryTracker(eventStructure)
    // Tracker of monitors operations.
    override val monitorTracker: MonitorTracker =
        EventStructureMonitorTracker(eventStructure, MapMonitorTracker(nThreads))
    // Tracker of thread parking
    override val parkingTracker: ParkingTracker = EventStructureParkingTracker(eventStructure)

    val stats = Stats()

    init {
        atomicityChecker.initialize(eventStructure)
    }

    override fun runImpl(): LincheckFailure? {
        // TODO: move invocation counting logic to ManagedStrategy class
        // TODO: should we count failed inconsistent executions as used invocations?
        outer@while (stats.totalInvocations < maxInvocations) {
            val (result, inconsistency) = runNextExploration()
                ?: break
            if (inconsistency == null) {
                check(result != null)
                runUntracking(nThreads) {
                    memoryTracker.dumpMemory()
                }
                checkResult(result, shouldCollectTrace = false)?.let { return it }
            }
        }
        println(stats)
        return null
    }

    // TODO: rename & refactor!
    fun runNextExploration(): Pair<InvocationResult?, Inconsistency?>? {
        // check that we have next invocation to explore
        if (!eventStructure.startNextExploration())
            return null
        eventStructure.checkConsistency()?.let { inconsistency ->
            return (null to inconsistency)
        }
        val result = runInvocation()
        // if invocation was aborted we also abort the current execution inside event structure
        if (result.isAbortedInvocation()) {
            // println("============ Before abort =================")
            // println(eventStructure.currentExecution)
            eventStructure.abortExploration()
            // println("============ After abort =================")
            // println(eventStructure.currentExecution)
        }
        // patch clocks
        if (result is CompletedInvocationResult) {
            patchResultsClock(result.results)
        }
        val inconsistency = eventStructure.checkConsistency()
        stats.update(result, inconsistency)
        return (result to inconsistency)
    }

    class Stats {

        var consistentInvocations: Int = 0
            private set

        private var relAcqInconsistenciesCount: Int = 0

        private var seqCstViolationsCount: IntArray =
            IntArray(SequentialConsistencyCheckPhase.values().size) { 0 }

        val inconsistentInvocations: Int
            get() =
                releaseAcquireViolationsCount() +
                sequentialConsistencyViolationsCount()

        val totalInvocations: Int
            get() = consistentInvocations + inconsistentInvocations

        fun releaseAcquireViolationsCount(): Int =
            relAcqInconsistenciesCount

        fun sequentialConsistencyViolationsCount(phase: SequentialConsistencyCheckPhase? = null): Int =
            phase?.let { seqCstViolationsCount[it.ordinal] } ?: seqCstViolationsCount.sum()

        fun update(result: InvocationResult?, inconsistency: Inconsistency?) {
            if (inconsistency == null) {
                consistentInvocations++
                return
            }
            when(inconsistency) {
                is ReleaseAcquireConsistencyViolation ->
                    relAcqInconsistenciesCount++
                is SequentialConsistencyViolation ->
                    seqCstViolationsCount[inconsistency.phase.ordinal]++
            }
        }

        override fun toString(): String = """
            #Total invocations   = $totalInvocations         
                #consistent      = $consistentInvocations    
                #inconsistent    = $inconsistentInvocations  
            #RelAcq violations   = $relAcqInconsistenciesCount
            #SeqCst violations   = ${sequentialConsistencyViolationsCount()}
                #prelim. phase   = ${sequentialConsistencyViolationsCount(SequentialConsistencyCheckPhase.PRELIMINARY)}
                #approx. phase   = ${sequentialConsistencyViolationsCount(SequentialConsistencyCheckPhase.APPROXIMATION)} 
                #replay  phase   = ${sequentialConsistencyViolationsCount(SequentialConsistencyCheckPhase.REPLAYING)}
        """.trimIndent()

    }

    // a hack to reset happens-before clocks computed by scheduler,
    // because these clocks can be not in sync with with
    // happens-before relation constructed by the event structure
    // TODO: refactor this --- we need a more robust solution;
    //   for example, we can compute happens before relation induced by
    //   the event structure and pass it on
    private fun patchResultsClock(executionResult: ExecutionResult) {
        for (results in executionResult.parallelResultsWithClock) {
            for (result in results) {
                result.clockOnStart.reset()
            }
        }
    }

    // Number of steps given to each thread before context-switch
    private val SCHEDULER_THREAD_STEPS_NUM: Int = 3
    private var thread_steps = 0

    override fun shouldSwitch(iThread: Int): ThreadSwitchDecision {
        // If strategy is in replay phase we first need to execute replaying threads
        if (eventStructure.inReplayPhase() && !eventStructure.inReplayPhase(iThread)) {
            return ThreadSwitchDecision.MAY
        }
        // If strategy is in replay mode for given thread
        // we should wait until replaying the next event become possible
        // (i.e. when all the dependencies will be replayed too)
        if (eventStructure.inReplayPhase(iThread)) {
            return if (eventStructure.canReplayNextEvent(iThread))
                ThreadSwitchDecision.NOT
            else
                ThreadSwitchDecision.MUST
        }

        /* For event structure strategy enforcing context switches is not necessary,
         * because it is guaranteed that the strategy will explore all
         * executions anyway, no matter of the order of context switches.
         * Thus, in principle it is possible to explore threads in fixed static order,
         * (e.g. always return false here).
         * In practice, however, the order of context switches may influence performance
         * of the model checking, and time-to-first-bug-discovered metric.
         * Thus we might want to customize scheduling strategy.
         * TODO: make scheduling strategy configurable

         * Another important consideration for scheduling strategy is fairness.
         * In case of live-locks (e.g. spin-loops) unfair scheduler
         * is likely to prioritize wasteful exploration of spinning executions.
         * For example, when checking typical spin-lock implementation,
         * unfair scheduler might give bias to a thread waiting in spin-loop
         *
         * Thus we currently employ simple fair strategy that gives equal number of steps
         * to every threads before switch.
         */
        // if (++thread_steps <= SCHEDULER_THREAD_STEPS_NUM)
        //     return false
        // thread_steps = 0
        return ThreadSwitchDecision.NOT
    }

    override fun chooseThread(iThread: Int): Int {
        // see comment in `shouldSwitch` method
        // TODO: make scheduling strategy configurable
        return switchableThreads(iThread).first()
    }

    override fun isActive(iThread: Int): Boolean {
        return super.isActive(iThread) && (eventStructure.inReplayPhase() implies {
            eventStructure.inReplayPhase(iThread) && eventStructure.canReplayNextEvent(iThread)
        })
    }

    override fun initializeInvocation() {
        super.initializeInvocation()
        thread_steps = 0
        eventStructure.initializeExploration()
        eventStructure.addThreadStartEvent(eventStructure.mainThreadId)
    }

    override fun beforeParallelPart() {
        super.beforeParallelPart()
        eventStructure.addThreadForkEvent(eventStructure.mainThreadId, (0 until nThreads).toSet())
    }

    override fun afterParallelPart() {
        super.afterParallelPart()
        eventStructure.addThreadJoinEvent(eventStructure.mainThreadId, (0 until nThreads).toSet())
    }

    override fun onStart(iThread: Int) {
        super.onStart(iThread)
        eventStructure.addThreadStartEvent(iThread)
    }

    override fun onFinish(iThread: Int) {
        // TODO: refactor, make `switchCurrentThread` private again in ManagedStrategy,
        //   call overridden `onStart` and `onFinish` methods only when thread is active
        //   and the `currentThread` lock is held
        awaitTurn(iThread)
        // while (!isActive(iThread)) {
        //     switchCurrentThread(iThread, mustSwitch = true)
        // }
        eventStructure.addThreadFinishEvent(iThread)
        super.onFinish(iThread)
    }
}

private class EventStructureMemoryTracker(private val eventStructure: EventStructure): MemoryTracker() {

    override fun objectAllocation(iThread: Int, value: OpaqueValue) {
        eventStructure.addObjectAllocationEvent(iThread, value)
    }

    override fun writeValue(iThread: Int, location: MemoryLocation, kClass: KClass<*>, value: OpaqueValue?) {
        eventStructure.addWriteEvent(iThread, location, kClass, value)
    }

    override fun readValue(iThread: Int, location: MemoryLocation, kClass: KClass<*>): OpaqueValue? {
        val readEvent = eventStructure.addReadEvent(iThread, location, kClass)
        return (readEvent.label as ReadAccessLabel).value
    }

    override fun compareAndSet(iThread: Int, location: MemoryLocation, kClass: KClass<*>, expected: OpaqueValue?, desired: OpaqueValue?): Boolean {
        val readEvent = eventStructure.addReadEvent(iThread, location, kClass, isExclusive = true)
        val value = (readEvent.label as ReadAccessLabel).value
        if (value != expected)
            return false
        eventStructure.addWriteEvent(iThread, location, kClass, desired, isExclusive = true)
        return true
    }

    private enum class IncrementKind { Pre, Post }

    private fun fetchAndAdd(iThread: Int, memoryLocationId: MemoryLocation, kClass: KClass<*>, delta: Number, incKind: IncrementKind): OpaqueValue? {
        val readEvent = eventStructure.addReadEvent(iThread, memoryLocationId, kClass, isExclusive = true)
        val readLabel = readEvent.label as ReadAccessLabel
        // TODO: should we use some sub-type check instead of equality check?
        check(readLabel.kClass == kClass)
        val oldValue = readLabel.value!!
        val newValue = oldValue + delta
        eventStructure.addWriteEvent(iThread, memoryLocationId, kClass, newValue, isExclusive = true)
        return when (incKind) {
            IncrementKind.Pre -> oldValue
            IncrementKind.Post -> newValue
        }
    }

    override fun getAndAdd(iThread: Int, location: MemoryLocation, kClass: KClass<*>, delta: Number): OpaqueValue? {
        return fetchAndAdd(iThread, location, kClass, delta, IncrementKind.Pre)
    }

    override fun addAndGet(iThread: Int, location: MemoryLocation, kClass: KClass<*>, delta: Number): OpaqueValue? {
        return fetchAndAdd(iThread, location, kClass, delta, IncrementKind.Post)
    }

    override fun getAndSet(iThread: Int, location: MemoryLocation, kClass: KClass<*>, value: OpaqueValue?): OpaqueValue? {
        val readEvent = eventStructure.addReadEvent(iThread, location, kClass, isExclusive = true)
        val readValue = (readEvent.label as ReadAccessLabel).value
        eventStructure.addWriteEvent(iThread, location, kClass, value, isExclusive = true)
        return readValue
    }

    override fun dumpMemory() {
        val locations = mutableSetOf<MemoryLocation>()
        for (event in eventStructure.currentExecution) {
            if (event.label is MemoryAccessLabel) {
                locations.add(event.label.location)
            }
        }
        for (location in locations) {
            val finalWrites = eventStructure.calculateRacyWrites(location, eventStructure.currentExecution.toFrontier())
            // we choose one of the racy final writes non-deterministically and dump it to the memory
            val write = finalWrites.firstOrNull() ?: continue
            val label = write.label.asWriteAccessLabel(location).ensureNotNull()
            location.write(label.value?.unwrap())
        }
    }

    override fun reset() {}

}

private class EventStructureMonitorTracker(
    private val eventStructure: EventStructure,
    private val monitorTracker: MonitorTracker,
) : MonitorTracker {

    override fun acquire(iThread: Int, monitor: OpaqueValue): Boolean {
        var lockRequest = eventStructure.getBlockedRequest(iThread)
        if (lockRequest == null) {
            val depth = monitorTracker.reentranceDepth(iThread, monitor)
            lockRequest = eventStructure.addLockRequestEvent(iThread, monitor, reentranceDepth = depth + 1)
        }
        // if lock is acquired by another thread then postpone addition of lock-response event
        if (!monitorTracker.acquire(iThread, monitor)) {
            return false
        }
        val lockResponse = eventStructure.addLockResponseEvent(lockRequest)
        // if we cannot add lock-response currently then release the lock and return
        if (lockResponse == null) {
            monitorTracker.release(iThread, monitor)
            return false
        }
        return true
    }

    override fun release(iThread: Int, monitor: OpaqueValue) {
        val depth = monitorTracker.reentranceDepth(iThread, monitor)
        monitorTracker.release(iThread, monitor)
        eventStructure.addUnlockEvent(iThread, monitor, reentranceDepth = depth)
    }

    override fun owner(monitor: OpaqueValue): Int? =
        monitorTracker.owner(monitor)

    override fun reentranceDepth(iThread: Int, monitor: OpaqueValue): Int {
        return monitorTracker.reentranceDepth(iThread, monitor)
    }

    override fun wait(iThread: Int, monitor: OpaqueValue): Boolean {
        var unlockEvent: Event? = null
        var waitRequestEvent: Event? = null
        var waitResponseEvent: Event? = null
        var lockRequestEvent: Event? = null
        var lockResponseEvent: Event? = null
        val blockedEvent = eventStructure.getBlockedRequest(iThread)
        if (blockedEvent != null) {
            check(blockedEvent.label is WaitLabel || blockedEvent.label is LockLabel)
            if (blockedEvent.label is WaitLabel) {
                waitRequestEvent = blockedEvent
            }
            if (blockedEvent.label is LockLabel) {
                lockRequestEvent = blockedEvent.ensure { it.label.isRequest && it.label is LockLabel }
                waitResponseEvent = lockRequestEvent.parent!!.ensure { it.label.isResponse && it.label is WaitLabel }
                waitRequestEvent = waitResponseEvent.parent!!.ensure { it.label.isRequest && it.label is WaitLabel }
            }
            unlockEvent = waitRequestEvent!!.parent!!.ensure { it.label is UnlockLabel }
        }
        if (unlockEvent == null) {
            val depth = monitorTracker.reentranceDepth(iThread, monitor).ensure { it > 0 }
            monitorTracker.release(iThread, monitor, times = depth)
            unlockEvent = eventStructure.addUnlockEvent(iThread, monitor,
                reentranceDepth = depth,
                reentranceCount = depth,
                isWaitUnlock = true,
            )
        }
        if (waitRequestEvent == null) {
            waitRequestEvent = eventStructure.addWaitRequestEvent(iThread, monitor)
        }
        if (waitResponseEvent == null) {
            waitResponseEvent = eventStructure.addWaitResponseEvent(waitRequestEvent)
            if (waitResponseEvent == null)
                return true
        }
        if (lockRequestEvent == null) {
            val depth = (unlockEvent.label as UnlockLabel).reentranceDepth
            val count = (unlockEvent.label as UnlockLabel).reentranceCount
            lockRequestEvent = eventStructure.addLockRequestEvent(iThread, monitor,
                reentranceDepth = depth,
                reentranceCount = count,
                isWaitLock = true,
            )
        }
        val count = (lockRequestEvent.label as LockLabel).reentranceCount
        // if lock is acquired by another thread then postpone addition of lock-response event
        if (!monitorTracker.acquire(iThread, monitor, times = count)) {
            return true
        }
        lockResponseEvent = eventStructure.addLockResponseEvent(lockRequestEvent)
        // if we cannot add lock-response currently then release the lock and return
        if (lockResponseEvent == null) {
            monitorTracker.release(iThread, monitor, times = count)
        }
        return false
    }

    override fun notify(iThread: Int, monitor: OpaqueValue, notifyAll: Boolean) {
        eventStructure.addNotifyEvent(iThread, monitor, notifyAll)
    }

    override fun isWaiting(iThread: Int): Boolean {
        if (monitorTracker.isWaiting(iThread))
            return true
        val blockedEvent = eventStructure.getBlockedAwaitingRequest(iThread)
            ?: return false
        return blockedEvent.label is LockLabel || blockedEvent.label is WaitLabel
    }

    override fun reset() {
        monitorTracker.reset()
    }

}

private class EventStructureParkingTracker(
    private val eventStructure: EventStructure,
) : ParkingTracker {

    override fun park(iThread: Int) {
        eventStructure.addParkRequestEvent(iThread)
    }

    override fun unpark(iThread: Int, unparkingThreadId: Int) {
        eventStructure.addUnparkEvent(iThread, unparkingThreadId)
    }

    override fun isParked(iThread: Int): Boolean {
        val parkRequest = eventStructure.getBlockedRequest(iThread)
            ?.takeIf { it.label is ParkLabel }
            ?: return false
        val parkResponse = eventStructure.addParkResponseEvent(parkRequest)
        return (parkResponse == null)
    }

    override fun reset() {}

}