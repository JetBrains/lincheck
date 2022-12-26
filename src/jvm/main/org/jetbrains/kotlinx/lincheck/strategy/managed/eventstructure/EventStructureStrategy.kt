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
import org.jetbrains.kotlinx.lincheck.implies
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
        )

    override val alwaysMustSwitch: Boolean = true

    // Tracker of shared memory accesses.
    override val memoryTracker: MemoryTracker = EventStructureMemoryTracker(eventStructure)
    // Tracker of monitors operations.
    override var monitorTracker: MonitorTracker = EventStructureMonitorTracker(eventStructure)
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
            inner@while (eventStructure.startNextExploration()) {
                val result = try {
                    runInvocation()
                } catch (e: Throwable) {
                    UnexpectedExceptionInvocationResult(e)
                }
                // if invocation was aborted we also abort the current execution inside event structure
                if (result.isAbortedInvocation()) {
                    eventStructure.abortExploration()
                }
                // patch clocks
                if (result is CompletedInvocationResult) {
                    patchResultsClock(result.results)
                }
                // println(eventStructure.currentExecution)

                val inconsistency = eventStructure.checkConsistency()
                stats.update(result, inconsistency)
                // println(stats)
                // println()

                if (inconsistency == null) {
                    checkResult(result, shouldCollectTrace = false)?.let { return it }
                }
                continue@outer
            }
            break
        }
        return null
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

    override fun shouldSwitch(iThread: Int): Boolean {
        // If strategy is in replay phase we first need to execute replaying threads
        if (eventStructure.inReplayPhase() && !eventStructure.inReplayPhase(iThread)) {
            return true
        }
        // If strategy is in replay mode for given thread
        // we should wait until replaying the next event become possible
        // (i.e. when all the dependencies will be replayed too)
        if (eventStructure.inReplayPhase(iThread)) {
            return !eventStructure.canReplayNextEvent(iThread)
        }
        // For event structure strategy enforcing context switches is not necessary,
        // because it is guaranteed that the strategy will explore all
        // executions anyway, no matter of the order of context switches.
        // Therefore we explore threads in fixed static order,
        // and thus this method always returns false.
        // In practice, however, the order of context switches may influence performance
        // of the model checking, and time-to-first-bug-discovered metric.
        // Thus we might want to customize scheduling strategy.
        // TODO: make scheduling strategy configurable
        return false
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
        eventStructure.initializeExploration()
        eventStructure.addThreadStartEvent(eventStructure.initialThreadId)
    }

    override fun beforeParallelPart() {
        super.beforeParallelPart()
        eventStructure.addThreadForkEvent(eventStructure.initialThreadId, (0 until nThreads).toSet())
    }

    override fun afterParallelPart() {
        super.afterParallelPart()
        eventStructure.addThreadJoinEvent(eventStructure.initialThreadId, (0 until nThreads).toSet())
    }

    override fun onStart(iThread: Int) {
        super.onStart(iThread)
        eventStructure.addThreadStartEvent(iThread)
    }

    override fun onFinish(iThread: Int) {
        // TODO: refactor, make `switchCurrentThread` private again in ManagedStrategy,
        //   call overridden `onStart` and `onFinish` methods only when thread is active
        //   and the `currentThread` lock is held
        while (!isActive(iThread)) {
            switchCurrentThread(iThread, mustSwitch = true)
        }
        eventStructure.addThreadFinishEvent(iThread)
        super.onFinish(iThread)
    }
}

private class EventStructureMemoryTracker(private val eventStructure: EventStructure): MemoryTracker() {

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
}

private class EventStructureMonitorTracker(private val eventStructure: EventStructure) : MonitorTracker {

    override fun acquire(iThread: Int, monitor: Any): Boolean {
        val event = eventStructure.addLockEvent(iThread, monitor)
        return event.label.isResponse
    }

    override fun release(iThread: Int, monitor: Any) {
        eventStructure.addUnlockEvent(iThread, monitor)
    }

    override fun wait(iThread: Int, monitor: Any): Boolean {
        val event = eventStructure.addWaitEvent(iThread, monitor)
        return event.label.isRequest
    }

    override fun notify(iThread: Int, monitor: Any, notifyAll: Boolean) {
        eventStructure.addNotifyEvent(iThread, monitor, notifyAll)
    }

    override fun isWaiting(iThread: Int): Boolean {
        return eventStructure.isWaiting(iThread)
    }

    override fun reentranceDepth(iThread: Int, monitor: Any): Int {
        return eventStructure.lockReentranceDepth(iThread, monitor)
    }

}

private class EventStructureParkingTracker(private val eventStructure: EventStructure) : ParkingTracker {

    override fun park(iThread: Int) {
        eventStructure.addParkEvent(iThread)
    }

    override fun unpark(iThread: Int, unparkingThreadId: Int) {
        eventStructure.addUnparkEvent(iThread, unparkingThreadId)
    }

    override fun isParked(iThread: Int): Boolean =
        eventStructure.isParked(iThread)

}