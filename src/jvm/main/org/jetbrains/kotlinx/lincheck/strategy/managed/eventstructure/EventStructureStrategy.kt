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
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.*
import org.jetbrains.kotlinx.lincheck.trace.Trace
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.descriptors.getType
import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.util.ensure
import org.jetbrains.lincheck.util.ensureNull
import org.jetbrains.lincheck.util.implies
import org.jetbrains.lincheck.util.runInsideIgnoredSection
import org.jetbrains.lincheck.util.satisfies
import org.jetbrains.lincheck.util.toInt
import org.jetbrains.lincheck.util.updateInplace
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation
import sun.nio.ch.lincheck.ThreadDescriptor

internal class EventStructureStrategy(
    runner: Runner,
    settings: ManagedStrategySettings,
    inIdeaPluginReplayMode: Boolean = false,
    context: TraceContext
) : ManagedStrategy(runner, settings, inIdeaPluginReplayMode, context) {

    private val memoryInitializer: MemoryInitializer = { location ->
        runInsideIgnoredSection {
            location.read(eventStructure.eventStructureObjectTracker::getValue)?.opaque()
        }
    }

    private val eventStructure: EventStructure =
        EventStructure( memoryInitializer, ::onInconsistency) { iThread, reason ->
            switchCurrentThread(iThread, reason)
        }

    private var isTestInstanceRegistered = false

    // Tracker of objects.
    override val objectTracker: ObjectTracker =
        eventStructure.eventStructureObjectTracker
    // Tracker of shared memory accesses.
    override val memoryTracker: MemoryTracker =
        EventStructureMemoryTracker(eventStructure, objectTracker)
    // Tracker of monitors operations.
    override val monitorTracker: MonitorTracker =
        EventStructureMonitorTracker(eventStructure, eventStructure.eventStructureObjectTracker)
    // Tracker of thread parking
    override val parkingTracker: ParkingTracker =
        EventStructureParkingTracker(eventStructure)

    override val trackFinalFields = true

    val stats = Stats()

    override fun shouldInvokeBeforeEvent(): Boolean {
        // TODO: fixme
        return false
    }

    override fun nextInvocation(): Boolean {
        // check that we have the next invocation to explore
        return eventStructure.startNextExploration()
    }

    override fun initializeInvocation() {
        super.initializeInvocation()
        isTestInstanceRegistered = false
        eventStructure.initializeExploration()
    }

    override fun runInvocationImpl(): InvocationResult {
        val (result, inconsistency) = runNextExploration()
        println($"EXECUTION: ${eventStructure.execution}")
        if (inconsistency != null) {
            return InconsistentInvocationResult(inconsistency)
        }
        check(result != null)
        // TODO: re-verify that it is safe to omit the memory dump at the end;
        //   it should be safe, because currently in the event-structure based algorithm,
        //   the intercepted writes are still performed, so the actual state of the memory
        //   reflects the state modelled by the current execution graph.
        // runInIgnoredSection {
        //     memoryTracker.dumpMemory()
        // }
        return result
    }

    // TODO: rename & refactor!
    fun runNextExploration(): Pair<InvocationResult?, Inconsistency?> {
        var result: InvocationResult? = null
        var inconsistency: Inconsistency? = eventStructure.checkConsistency()
        if (inconsistency == null) {
            eventStructure.addThreadStartEvent(eventStructure.mainThreadId)
            result = super.runInvocationImpl()
            // if invocation was aborted, we also abort the current execution inside event structure
            if (result.isAbortedInvocation()) {
                eventStructure.abortExploration()
            }
            // patch clocks
            if (result is CompletedInvocationResult) {
                val patchedResult = patchResultsClock(eventStructure.execution, result.results)
                result = CompletedInvocationResult(patchedResult)
            }
            inconsistency = when (result) {
                is InconsistentInvocationResult -> result.inconsistency
                else -> eventStructure.checkConsistency()
            }
        }

        // println(eventStructure.execution)
        // println("inconsistency: $inconsistency")
        // println()

        stats.update(result, inconsistency)
        // println(stats.totalInvocations)
        return (result to inconsistency)
    }

    // (OLD) TODO: temporarily disable trace collection for event structure strategy
    override fun tryCollectTrace(result: InvocationResult): Pair<Trace?, InvocationResult> {
        // return super.tryCollectTrace(result)
        return null to result
    }

    class Stats {

        var consistentInvocations: Int = 0
            private set

        var blockedInvocations: Int = 0
            private set

        private var lockConsistencyViolationCount: Int = 0

        private var atomicityInconsistenciesCount: Int = 0

        private var relAcqInconsistenciesCount: Int = 0

        private var seqCstApproximationInconsistencyCount: Int = 0

        private var seqCstCoherenceViolationCount: Int = 0

        private var seqCstReplayViolationCount: Int = 0

        private val sequentialConsistencyViolationsCount: Int
            get() =
                seqCstApproximationInconsistencyCount +
                seqCstCoherenceViolationCount +
                seqCstReplayViolationCount

        val inconsistentInvocations: Int
            get() =
                lockConsistencyViolationCount +
                atomicityInconsistenciesCount +
                relAcqInconsistenciesCount +
                sequentialConsistencyViolationsCount

        val totalInvocations: Int
            get() = consistentInvocations + inconsistentInvocations + blockedInvocations

        fun update(result: InvocationResult?, inconsistency: Inconsistency?) {
            if (inconsistency == null) {
                consistentInvocations++
                return
            }
            when(inconsistency) {
                is LockConsistencyViolation ->
                    lockConsistencyViolationCount++
                is ReadModifyWriteAtomicityViolation ->
                    atomicityInconsistenciesCount++
                is ReleaseAcquireInconsistency ->
                    relAcqInconsistenciesCount++
                is SequentialConsistencyApproximationInconsistency ->
                    seqCstApproximationInconsistencyCount++
                is CoherenceViolation ->
                    seqCstCoherenceViolationCount++
                is SequentialConsistencyReplayViolation ->
                    seqCstReplayViolationCount++
            }
        }

        override fun toString(): String = """
            #Total invocations   = $totalInvocations         
                #consistent      = $consistentInvocations    
                #inconsistent    = $inconsistentInvocations
                #blocked         = $blockedInvocations
            #Lock   violations   = $lockConsistencyViolationCount                
            #Atom.  violations   = $atomicityInconsistenciesCount
            #RelAcq violations   = $relAcqInconsistenciesCount
            #SeqCst violations   = $sequentialConsistencyViolationsCount
                #approx. phase   = $seqCstApproximationInconsistencyCount
                #coher.  phase   = $seqCstCoherenceViolationCount
                #replay  phase   = $seqCstReplayViolationCount
        """.trimIndent()

    }

    // a hack to reset happens-before clocks computed by scheduler,
    // because these clocks can be not in sync with with
    // happens-before relation constructed by the event structure
    // TODO: refactor this --- we need a more robust solution;
    //   for example, we can compute happens before relation induced by
    //   the event structure and pass it on
    private fun patchResultsClock(execution: Execution<AtomicThreadEvent>, executionResult: ExecutionResult): ExecutionResult {
        val initPartSize = executionResult.initResults.size
        val postPartSize = executionResult.postResults.size
        val hbClockSize = executionResult.parallelResultsWithClock.size
        val patchedParallelResults = executionResult.parallelResultsWithClock
            .map { it.map { resultWithClock -> ResultWithClock(resultWithClock.result, resultWithClock.clockOnStart) }}
        val (actorsExecution, _) = execution.aggregate(ActorAggregator(execution))
        check(actorsExecution.threadIDs.size == hbClockSize + 1)
        for (tid in patchedParallelResults.indices) {
            var actorEvents: List<HyperThreadEvent> = actorsExecution[tid]!!
            // cut init/post part
            if (tid == 0) {
                actorEvents = actorEvents.subList(
                    fromIndex = initPartSize,
                    toIndex = actorEvents.size - postPartSize,
                )
            }
            val actorResults = patchedParallelResults[tid]
            actorResults.forEachIndexed { i, result ->
                val actorEvent = actorEvents.getOrNull(i)
                val prevHBClock = actorResults.getOrNull(i - 1)?.clockOnStart?.copy()
                    ?: emptyClock(hbClockSize)
                val clockSize = result.clockOnStart.clock.size
                val hbClock = actorEvent?.causalityClock?.toHBClock(clockSize, tid, i)
                    ?: prevHBClock.apply { clock[tid] = i }
                // cut init part actors
                hbClock.clock[0] -= initPartSize
                check(hbClock[tid] == i)
                result.clockOnStart.set(hbClock)
            }
        }
        return ExecutionResult(
            initResults = executionResult.initResults,
            parallelResultsWithClock = patchedParallelResults,
            postResults = executionResult.postResults,
            afterInitStateRepresentation = executionResult.afterInitStateRepresentation,
            afterParallelStateRepresentation = executionResult.afterParallelStateRepresentation,
            afterPostStateRepresentation = executionResult.afterPostStateRepresentation,
        )
    }

    var threadToSwitch : ThreadId? = null
    override fun onSwitchPoint(iThread: ThreadId) {
        threadToSwitch = iThread
    }

    override fun shouldSwitch(): Boolean {
        // TODO: this has changed, see what the new semantics are
        // If strategy is in replay phase we first need to execute replaying threads
        // if (eventStructure.inReplayPhase() && !eventStructure.inReplayPhase(iThread)) {
        //     return ThreadSwitchDecision.MAY
        // }

        // If strategy is in replay mode for given thread
        // we should wait until replaying the next event become possible
        // (i.e. when all the dependencies will be replayed too)
        if (threadToSwitch != null && eventStructure.inReplayPhase(threadToSwitch!!)) {
            return if (eventStructure.canReplayNextEvent(threadToSwitch!!))
                false
            else
                true
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
         */
        return false
    }

    override fun chooseThread(iThread: Int): Int {
        // see comment in `shouldSwitch` method
        // TODO: make scheduling strategy configurable
        return if (currentExecutionPart == ExecutionPart.PARALLEL)
            switchableThreads(iThread).first()
        else
            eventStructure.mainThreadId
    }

    override fun isActive(iThread: Int): Boolean {
        return super.isActive(iThread) && (eventStructure.inReplayPhase() implies {
            eventStructure.inReplayPhase(iThread) && eventStructure.canReplayNextEvent(iThread)
        })
    }

    override fun beforePart(part: ExecutionPart) {
        super.beforePart(part)
        val forkedThreads = getRegisteredThreads()
            .mapNotNull {
                if (it.key == eventStructure.mainThreadId) return@mapNotNull null
                return@mapNotNull it.key
            }
            .toSet()
        when (part) {
            ExecutionPart.INIT -> {
                registerTestInstance()
            }
            ExecutionPart.PARALLEL -> {
                if (!isTestInstanceRegistered) {
                    registerTestInstance()
                }
                if (forkedThreads.isNotEmpty()) {
                    eventStructure.addThreadForkEvent(eventStructure.mainThreadId, forkedThreads)
                }
            }
            ExecutionPart.POST -> {
                if (forkedThreads.isNotEmpty()) {
                    eventStructure.addThreadJoinEvent(eventStructure.mainThreadId, forkedThreads)
                }
            }
            else -> {}
        }
    }

    override fun registerThread(thread: Thread, descriptor: ThreadDescriptor): ThreadId {
        val threadId = super.registerThread(thread, descriptor)
        eventStructure.registerThread(threadId)
        return threadId
    }

    private fun registerTestInstance() {
        check(!isTestInstanceRegistered)
        val testInstance = (runner as ExecutionScenarioRunner).testInstance
        //NOTE: The threadID may be messed up. See how this can be fixed.
        (objectTracker as EventStructureObjectTracker).registerExternalObject(testInstance)
        isTestInstanceRegistered = true
    }

    override fun onThreadStart(threadId: Int) {
        super.onThreadStart(threadId)
        if (threadId != eventStructure.mainThreadId && threadId != eventStructure.initThreadId) {
            eventStructure.addThreadStartEvent(threadId)
        }
    }

    override fun onThreadFinish(threadId: Int) {
        // TODO: refactor, make `switchCurrentThread` private again in ManagedStrategy,
        //   call overridden `onStart` and `onFinish` methods only when thread is active
        //   and the `currentThread` lock is held
        threadScheduler.awaitTurn(threadId)
        // TODO: extract this check into a method ?
        while (eventStructure.inReplayPhase() && !eventStructure.canReplayNextEvent(threadId)) {
            switchCurrentThread(threadId, null) // TODO: Should not be null, since we would not switch we need another blocking reason
        }
        eventStructure.addThreadFinishEvent(threadId)
        super.onThreadFinish(threadId)
    }

    override fun onActorStart(iThread: Int) {
        super.onActorStart(iThread)
        // TODO: move ignored section to ManagedStrategyRunner
        runInsideIgnoredSection {
            if (currentExecutionPart == ExecutionPart.VALIDATION)
                return@runInsideIgnoredSection
            val actor = scenario!!.threads[iThread][currentActorId.getOrDefault(iThread, 0)]
            eventStructure.addActorStartEvent(iThread, actor)
        }
    }

    override fun onActorFinish(iThread: Int) {
        // TODO: move ignored section to ManagedStrategyRunner
        runInsideIgnoredSection {
            if (currentExecutionPart == ExecutionPart.VALIDATION)
                return@runInsideIgnoredSection
            val actor = scenario!!.threads[iThread][currentActorId.getOrDefault(iThread, 0)]
            eventStructure.addActorEndEvent(iThread, actor)
        }
        super.onActorFinish(iThread)
    }

    private fun onInconsistency(inconsistency: Inconsistency) {
        abortWithSuddenInvocationResult(InconsistentInvocationResult(inconsistency))
    }

    // NOTE: I guess this should not be final anymore (this has been changed)
    override fun afterCoroutineSuspended(iThread: Int) {
        eventStructure.addCoroutineSuspendRequestEvent(iThread, currentActorId.getOrDefault(iThread, 0))
        super.afterCoroutineSuspended(iThread)
    }

    //NOTE: Same as above
    override fun afterCoroutineResumed(iThread: Int) {
        super.afterCoroutineResumed(iThread)
        eventStructure.addCoroutineSuspendResponseEvent(iThread, currentActorId.getOrDefault(iThread, 0))
    }

    // NOTE: In ManagedStrategy we have two flavours of afterCoroutineCancelletion none of which contain promptCancellation (how do we translate it)
    override fun afterCoroutineCancellation(iThread: Int, promptCancelatioon: Boolean, cancellationResult: CancellationResult) {
        super.afterCoroutineCancellation(iThread, promptCancelatioon, cancellationResult)
        if (cancellationResult == CancellationResult.CANCELLATION_FAILED)
            return

        eventStructure.addCoroutineSuspendRequestEvent(iThread, currentActorId[iThread]!!, promptCancelatioon)
        eventStructure.addCoroutineCancelResponseEvent(iThread, currentActorId[iThread]!!)
    }

    override fun afterCoroutineCancellation(iThread: Int, promptCancelatioon: Boolean, cancellationException: Throwable) {
        super.afterCoroutineCancellation(iThread, promptCancelatioon, cancellationException)
        eventStructure.addCoroutineSuspendRequestEvent(iThread, currentActorId[iThread]!!, promptCancelatioon)
        eventStructure.addCoroutineCancelResponseEvent(iThread, currentActorId[iThread]!!)
    }

    override fun onCoroutineResumed(iResumedThread: Int, iResumedActor: Int) {
        super.onCoroutineResumed(iResumedThread, iResumedActor)
        eventStructure.addCoroutineResumeEvent(threadScheduler.getCurrentThreadId(), iResumedThread, iResumedActor)
    }

    override fun isCoroutineResumed(iThread: Int, iActor: Int): Boolean {
        if (!super.isCoroutineResumed(iThread, iActor))
            return false
        val resumeEvent = eventStructure.execution.find {
            it.label.satisfies<CoroutineResumeLabel> { threadId == iThread && actorId == iActor }
        }
        return (resumeEvent != null)
    }
}

internal typealias ReportInconsistencyCallback = (Inconsistency) -> Unit
internal typealias InternalThreadSwitchCallback = (ThreadId, BlockingReason?) -> Unit

private class EventStructureMemoryTracker(
    private val eventStructure: EventStructure,
    private val objectTracker: ObjectTracker,
) : MemoryTracker {

    private val eventStructureObjectTracker: EventStructureObjectTracker
        get() = eventStructure.eventStructureObjectTracker

    private fun getValueID(location: MemoryLocation, value: OpaqueValue?): ValueID =
        eventStructureObjectTracker.getOrRegisterValueID(location.type, value)

    private fun getValue(location: MemoryLocation, valueID: ValueID) =
        eventStructureObjectTracker.getValue(location.type, valueID)

    private fun getValue(type: Types.Type, valueID: ValueID) =
        eventStructureObjectTracker.getValue(type, valueID)

    private fun addWriteEvent(iThread: Int, codeLocation: Int, location: MemoryLocation, value: OpaqueValue?,
                              rmwWriteDescriptor: ReadModifyWriteDescriptor? = null) {
        // force evaluation of initial value (before possibly overwriting it)
        eventStructure.allocationEvent(location.objID)?.label?.asWriteAccessLabel(location)
        eventStructure.addWriteEvent(iThread, codeLocation, location, getValueID(location, value), rmwWriteDescriptor)
    }

    private fun addReadRequest(iThread: Int, codeLocation: Int, location: MemoryLocation,
                               readModifyWriteDescriptor: ReadModifyWriteDescriptor? = null) {
        eventStructure.addReadRequest(iThread, codeLocation, location, readModifyWriteDescriptor)
    }

    private fun addReadResponse(iThread: Int): OpaqueValue? {
        val event = eventStructure.addReadResponse(iThread)
        val label = (event.label as ReadAccessLabel)
        println("RESPONSE LABEL: ${label.value} ${label.location}")
        val rmwDescriptor = label.readModifyWriteDescriptor
        // regular non-RMW read - return the read value
        if (rmwDescriptor == null) {
            return getValue(label.location, label.value)
        }
        // handle different kinds of RMWs
        // TODO: perform actual write to memory for successful CAS
        when (rmwDescriptor) {
            is ReadModifyWriteDescriptor.GetAndSetDescriptor -> {
                val newValueID = rmwDescriptor.newValue
                val newValue = getValue(label.location, newValueID)
                eventStructure.addWriteEvent(iThread, label.codeLocation, label.location, newValueID, rmwDescriptor)
                label.location.write(newValue?.unwrap(), eventStructureObjectTracker::getValue)
                return getValue(label.location, label.value)
            }

            is ReadModifyWriteDescriptor.CompareAndSetDescriptor -> {
                if (label.value == rmwDescriptor.expectedValue) {
                    val newValueID = rmwDescriptor.newValue
                    val newValue = getValue(label.location, newValueID)
                    eventStructure.addWriteEvent(iThread, label.codeLocation, label.location, newValueID, rmwDescriptor)
                    label.location.write(newValue?.unwrap(), eventStructureObjectTracker::getValue)
                    return getValue(Types.BOOLEAN_TYPE, true.toInt().toLong())
                }
                return getValue(Types.BOOLEAN_TYPE, false.toInt().toLong())
            }

            is ReadModifyWriteDescriptor.CompareAndExchangeDescriptor -> {
                if (label.value == rmwDescriptor.expectedValue) {
                    val newValueID = rmwDescriptor.newValue
                    val newValue = getValue(label.location, newValueID)
                    eventStructure.addWriteEvent(iThread, label.codeLocation, label.location, newValueID, rmwDescriptor)
                    label.location.write(newValue?.unwrap(), eventStructureObjectTracker::getValue)
                }
                return getValue(label.location, label.value)
            }

            is ReadModifyWriteDescriptor.FetchAndAddDescriptor -> {
                val newValueID = label.value + rmwDescriptor.delta
                val newValue = getValue(label.location, newValueID)
                eventStructure.addWriteEvent(iThread, label.codeLocation, label.location, newValueID, rmwDescriptor)
                label.location.write(newValue?.unwrap(), eventStructureObjectTracker::getValue)
                return when (rmwDescriptor.kind) {
                    ReadModifyWriteDescriptor.IncrementKind.Pre  -> getValue(label.location, label.value)
                    ReadModifyWriteDescriptor.IncrementKind.Post -> getValue(label.location, newValueID)
                }
            }
        }
    }

    override fun beforeWrite(iThread: Int, codeLocation: Int, location: MemoryLocation, value: Any?) {
        println("beforeWrite")
        addWriteEvent(iThread, codeLocation, location, value?.opaque())
    }

    override fun beforeRead(iThread: Int, codeLocation: Int, location: MemoryLocation) {
        addReadRequest(iThread, codeLocation, location)
    }

    override fun beforeGetAndSet(iThread: Int, codeLocation: Int, location: MemoryLocation, newValue: Any?) {
        eventStructure.addReadRequest(iThread, codeLocation, location,
            readModifyWriteDescriptor = ReadModifyWriteDescriptor.GetAndSetDescriptor(
                newValue = getValueID(location, newValue?.opaque())
            )
        )
    }

    override fun beforeCompareAndSet(iThread: Int, codeLocation: Int, location: MemoryLocation, expectedValue: Any?, newValue: Any?) {
        eventStructure.addReadRequest(iThread, codeLocation, location,
            readModifyWriteDescriptor = ReadModifyWriteDescriptor.CompareAndSetDescriptor(
                expectedValue = getValueID(location, expectedValue?.opaque()),
                newValue = getValueID(location, newValue?.opaque()),
            )
        )
    }

    override fun beforeCompareAndExchange(iThread: Int, codeLocation: Int, location: MemoryLocation, expectedValue: Any?, newValue: Any?) {
        eventStructure.addReadRequest(iThread, codeLocation, location,
            readModifyWriteDescriptor = ReadModifyWriteDescriptor.CompareAndExchangeDescriptor(
                expectedValue = getValueID(location, expectedValue?.opaque()),
                newValue = getValueID(location, newValue?.opaque()),
            )
        )
    }

    override fun beforeGetAndAdd(iThread: Int, codeLocation: Int, location: MemoryLocation, delta: Number) {
        eventStructure.addReadRequest(iThread, codeLocation, location,
            readModifyWriteDescriptor = ReadModifyWriteDescriptor.FetchAndAddDescriptor(
                delta = getValueID(location, delta.opaque()),
                kind = ReadModifyWriteDescriptor.IncrementKind.Pre,
            )
        )
    }

    override fun beforeAddAndGet(iThread: Int, codeLocation: Int, location: MemoryLocation, delta: Number) {
        eventStructure.addReadRequest(iThread, codeLocation, location,
            readModifyWriteDescriptor = ReadModifyWriteDescriptor.FetchAndAddDescriptor(
                delta = getValueID(location, delta.opaque()),
                kind = ReadModifyWriteDescriptor.IncrementKind.Post,
            )
        )
    }

    override fun interceptReadResult(iThread: Int): Any? {
        return addReadResponse(iThread)?.unwrap()?.also {
            LincheckInstrumentation.ensureObjectIsTransformed(it)
        }
    }

    override fun interceptArrayCopy(iThread: Int, codeLocation: Int, srcArray: Any?, srcPos: Int, dstArray: Any?, dstPos: Int, length: Int) {
        val srcType = srcArray!!::class.getType()
        val dstType = dstArray!!::class.getType()
        for (i in 0 until length) {
            val readLocation  = objectTracker.getArrayAccessMemoryLocation(srcArray, srcPos + i, srcType)
            val writeLocation = objectTracker.getArrayAccessMemoryLocation(dstArray, dstPos + i, dstType)
            val value = run {
                beforeRead(iThread, codeLocation, readLocation)
                interceptReadResult(iThread)
            }
            beforeWrite(iThread, codeLocation, writeLocation, value)
            writeLocation.write(value, eventStructureObjectTracker::getValue)
        }
    }

    override fun reset() {}

}

// NOTE: Some issues here as well , there are some missing members
private class EventStructureMonitorTracker(
    private val eventStructure: EventStructure,
    private val eventStructureObjectTracker: EventStructureObjectTracker,
) : MonitorTracker {

    // for each mutex object acquired by some thread,
    // this map stores a mapping from the mutex object to the lock-response event;
    // to handle lock re-entrance, we actually store a stack of lock-response events
    private val lockStacks = mutableMapOf<ValueID, MutableList<AtomicThreadEvent>>()

    // for threads waiting on the mutex,
    // stores the lock stack of the current thread for the awaited mutex
    private val waitLockStack = mutableThreadMapOf<MutableList<AtomicThreadEvent>>()

    private fun canAcquireMonitor(iThread: Int, mutexID: ValueID): Boolean {
        val lockStack = lockStacks[mutexID]
        return (lockStack == null) || (lockStack.last().threadId == iThread)
    }

    private fun canAcquireMonitor(iThread: Int, monitor: OpaqueValue): Boolean {
        val mutexID = eventStructureObjectTracker[monitor]!!.stableObjectNumber
        return canAcquireMonitor(iThread, mutexID.toLong())
    }

    // Not sure how to proceed with these (I need to look into it)
    override fun registerThread(threadId: Int) {}

    override fun acquiringThreads(monitor: Any): List<Int> {
        TODO("Not yet implemented")
    }

    override fun interruptWait(threadId: Int) {
        TODO("Not yet implemented")
    }


    override fun acquireMonitor(threadId: Int, monitor: Any): Boolean {
        // issue lock-request event
        val lockRequest = issueLockRequest(threadId, monitor.opaque())
        // if lock is acquired by another thread then postpone addition of lock-response event
        if (!canAcquireMonitor(threadId, monitor.opaque()))
            return false
        // try to add lock-response event
        val lockResponse = tryCompleteLockResponse(lockRequest)
        // return true if the lock-response event was created successfully
        return (lockResponse != null)
    }

    // NOTE: This should be a bool?
    override fun releaseMonitor(threadId: Int, monitor: Any): Boolean {
        return issueUnlock(threadId, monitor.opaque())
    }


    private fun issueLockRequest(iThread: Int, monitor: OpaqueValue): AtomicThreadEvent {
        val mutexID = eventStructureObjectTracker[monitor]!!.stableObjectNumber
        // check if the thread is already blocked on the lock-request
        val blockingRequest = eventStructure.getPendingBlockingRequest(iThread)
            ?.ensure { it.label.satisfies<LockLabel> { this.mutexID == mutexID } }
        if (blockingRequest != null)
            return blockingRequest
        // check if it is a re-entrance lock and obtain lock re-entrance depth
        val lockStack = lockStacks[mutexID.toLong()]
            ?.ensure { it.isNotEmpty() }
            ?.takeIf { it.last().threadId == iThread }
        val depth = lockStack?.size ?: 0
        // finally, add the new lock-request
        return eventStructure.addLockRequestEvent(iThread, monitor,
            isReentry = depth > 0,
            reentrancyDepth = 1 + depth,
        )
    }

    private fun tryCompleteLockResponse(lockRequest: AtomicThreadEvent): AtomicThreadEvent? {
        val mutexID = (lockRequest.label as LockLabel).mutexID
        // try to add lock-response event
        return eventStructure.addLockResponseEvent(lockRequest)?.also { lockResponse ->
            // if lock-response was added successfully, then push it to the lock stack
            lockStacks.updateInplace(mutexID.toLong(), default = mutableListOf()) {
                check(isNotEmpty() implies { last().threadId == lockResponse.threadId })
                add(lockResponse)
            }
        }
    }

    private fun issueUnlock(iThread: Int, monitor: OpaqueValue): Boolean {
        val mutexID = eventStructureObjectTracker[monitor]!!.stableObjectNumber
        // obtain current lock-responses stack, and ensure that
        // the lock is indeed acquired by the releasing thread
        val lockStack = lockStacks[mutexID.toLong()]!!
            .ensure { it.isNotEmpty() && (it.last().threadId == iThread) }
        val depth = lockStack.size
        // add unlock event to the event structure
        eventStructure.addUnlockEvent(iThread, monitor,
            isReentry = (depth > 1),
            reentrancyDepth = depth,
        )
        // remove last lock-response event from the stack,
        // since we just released the lock one time
        lockStack.removeLast()
        if (lockStack.isEmpty()) {
            lockStacks.remove(mutexID.toLong())
        }

        // Returns true the thread no longer held (TODO: Is this really the correct thing?)
        return lockStack.isEmpty()
    }

    override fun isWaiting(threadId: Int): Boolean {
        val blockingRequest = eventStructure.getPendingBlockingRequest(threadId)
            ?.takeIf { (it.label is LockLabel || it.label is WaitLabel) }
            ?: return false
        val mutexID = (blockingRequest.label as MutexLabel).mutexID
        return !(eventStructure.isPendingUnblockedRequest(blockingRequest) &&
                canAcquireMonitor(threadId, mutexID.toLong()))
    }

    override fun waitOnMonitor(threadId: Int, monitor: Any): Boolean {
        val mutexID = eventStructureObjectTracker[monitor.opaque()]!!.stableObjectNumber
        // check if the thread is already blocked on wait-request or (synthetic) lock-request
        val blockingRequest = eventStructure.getPendingBlockingRequest(threadId)
            ?.ensure { it.label.satisfies<MutexLabel> { this.mutexID == mutexID } }
            ?.ensure { it.label is LockLabel || it.label is WaitLabel }
        var waitRequest = blockingRequest?.takeIf { it.label is WaitLabel }
        var lockRequest = blockingRequest?.takeIf { it.label is LockLabel }
        // if the thread is not blocked yet, issue wait-request event;
        // this procedure will also add synthetic unlock event
        if (blockingRequest == null) {
            check(waitLockStack[threadId] == null)
            waitRequest = issueWaitRequest(threadId, monitor.opaque())
        }
        // if the wait-request was already issued, try to complete it by wait-response;
        // this procedure will also add synthetic lock-request event
        if (waitRequest != null) {
            val (_, _lockRequest) = tryCompleteWaitResponse(monitor.opaque(), waitRequest)
                ?: return true
            lockRequest = _lockRequest
        }
        // finally, check that the thread can acquire the lock back,
        // and try to complete the lock-request by lock-response
        check(lockRequest != null)
        if (!canAcquireMonitor(threadId, mutexID.toLong()))
            return true
        val lockResponse = tryCompleteWaitLockResponse(lockRequest)
        // exit waiting if the lock response was added successfully
        return (lockResponse == null)
    }

    override fun notify(threadId: Int, monitor: Any, notifyAll: Boolean) {
        issueNotify(threadId, monitor.opaque(), notifyAll)
    }

    private fun issueWaitRequest(iThread: Int, monitor: OpaqueValue): AtomicThreadEvent {
        val mutexID = eventStructureObjectTracker[monitor]!!.stableObjectNumber
        // obtain the current lock-responses stack, and ensure that
        // the lock is indeed acquired by the waiting thread
        val lockStack = lockStacks[mutexID.toLong()]!!
            .ensure { it.isNotEmpty() && (it.last().threadId == iThread) }
        val depth = lockStack.size
        // add synthetic unlock event to release the mutex
        eventStructure.addUnlockEvent(iThread, monitor,
            isSynthetic = true,
            isReentry = false,
            reentrancyDepth = depth,
        )
        // save the lock-responses stack to restore it later
        waitLockStack[iThread] = lockStack
        lockStacks.remove(mutexID.toLong())
        // add the new wait-request
        return eventStructure.addWaitRequestEvent(iThread, monitor)
    }

    private fun tryCompleteWaitResponse(monitor: OpaqueValue, waitRequest: AtomicThreadEvent): Pair<AtomicThreadEvent, AtomicThreadEvent>? {
        require(waitRequest.label.isRequest)
        require(waitRequest.label is WaitLabel)
        val mutexID = (waitRequest.label as WaitLabel).mutexID
        // try to complete wait-response
        val waitResponse = eventStructure.addWaitResponseEvent(waitRequest)
            ?: return null
        val unlockEvent = waitRequest.parent!!
        check(unlockEvent.label.satisfies<UnlockLabel> {
            this.mutexID == mutexID && isSynthetic
        })
        // issue synthetic lock-request to acquire the mutex back
        val iThread = waitRequest.threadId
        val depth = (unlockEvent.label as UnlockLabel).reentrancyDepth
        val lockRequest = eventStructure.addLockRequestEvent(iThread, monitor,
            isSynthetic = true,
            isReentry = false,
            reentrancyDepth = depth,
        )
        return (waitResponse to lockRequest)
    }

    private fun tryCompleteWaitLockResponse(lockRequest: AtomicThreadEvent): AtomicThreadEvent? {
        val iThread = lockRequest.threadId
        val mutexID = (lockRequest.label as LockLabel).mutexID
        // try to add lock-response event
        return eventStructure.addLockResponseEvent(lockRequest)?.also {
            // if lock-response was added successfully, then restore
            // the lock stack of the acquiring thread
            val lockStack = waitLockStack[iThread]!!
            lockStacks.put(mutexID.toLong(), lockStack).ensureNull()
            waitLockStack.remove(iThread)
        }
    }

    private fun issueNotify(iThread: Int, monitor: OpaqueValue, notifyAll: Boolean) {
        eventStructure.addNotifyEvent(iThread, monitor, notifyAll)
    }

    override fun reset() {
        lockStacks.clear()
        waitLockStack.clear()
    }

}

//NOTE: Same as the class above
private class EventStructureParkingTracker(
    private val eventStructure: EventStructure,
) : ParkingTracker {

    override fun registerThread(threadId: Int) {
        // We should probably not do anything. I assume that the Eventstrcture model does not really care when a new event is added for the parking tracker
    }

    // NOTE: not sure if we should handle this yet? Should we add a new parking label and event?
    override fun interruptPark(threadId: Int) {
        TODO("Not yet implemented")
    }

    override fun park(threadId: Int) {
        eventStructure.addParkRequestEvent(threadId)
    }

    //NOTE: allowSpuriusWakeup has been added and not sure how to use that
    override fun waitUnpark(threadId: Int, allowSpuriousWakeUp: Boolean): Boolean {
        val parkRequest = eventStructure.getPendingBlockingRequest(threadId)
            ?.takeIf { it.label is ParkLabel }
            ?: return false
        val parkResponse = eventStructure.addParkResponseEvent(parkRequest)
        return (parkResponse == null)
    }

    override fun unpark(threadId: Int, unparkedThreadId: Int) {
        eventStructure.addUnparkEvent(threadId, unparkedThreadId)
    }


    override fun isParked(threadId: Int): Boolean {
        val blockingRequest = eventStructure.getPendingBlockingRequest(threadId)
            ?.takeIf { it.label is ParkLabel }
            ?: return false
        return !eventStructure.isPendingUnblockedRequest(blockingRequest)
    }

    override fun reset() {}

}

