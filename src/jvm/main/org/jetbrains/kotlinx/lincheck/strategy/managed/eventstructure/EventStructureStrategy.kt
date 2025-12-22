/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTestConfiguration
import org.jetbrains.kotlinx.lincheck.trace.Trace
//import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent
import sun.nio.ch.lincheck.TestThread
import java.lang.reflect.*
import org.objectweb.asm.Type

internal class EventStructureStrategy(
    runner: Runner,
    settings: ManagedStrategySettings,
    // TODO: Should we turn this on or not?
    // The flag to enable IntelliJ IDEA plugin mode
    //  val inIdeaPluginReplayMode: Boolean = false,
) : ManagedStrategy(runner, settings) {

    private val memoryInitializer: MemoryInitializer = { location ->
        runInIgnoredSection {
            location.read(eventStructure.objectRegistry::getValue)?.opaque()
        }
    }

    private val eventStructure: EventStructure =
        EventStructure(nThreads, memoryInitializer, ::onInconsistency) { iThread, reason ->
            switchCurrentThread(iThread, reason, mustSwitch = true)
        }

    private var isTestInstanceRegistered = false

    // Tracker of objects.
    override val objectTracker: ObjectTracker =
        EventStructureObjectTracker(eventStructure)
    // Tracker of shared memory accesses.
    override val memoryTracker: MemoryTracker =
        EventStructureMemoryTracker(eventStructure, objectTracker)
    // Tracker of monitors operations.
    override val monitorTracker: MonitorTracker =
        EventStructureMonitorTracker(eventStructure, eventStructure.objectRegistry)
    // Tracker of thread parking
    override val parkingTracker: ParkingTracker =
        EventStructureParkingTracker(eventStructure)

    override val trackFinalFields: Boolean = true

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
                is SpinLoopBoundInvocationResult -> null
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

    // TODO: temporarily disable trace collection for event structure strategy
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
            if (result is SpinLoopBoundInvocationResult) {
                check(inconsistency == null)
                blockedInvocations++
                return
            }
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

    // TODO: This is a very hacky way to reproduce the old behaviour of shouldSwitch
    var threadToSwitch : ThreadId? = null
    override fun onSwitchPoint(iThread: ThreadId) {
        threadToSwitch = iThread;
    }

    override fun shouldSwitch(): Boolean {

        // TODO: this has changed see what the new semantics are
        // If strategy is in replay phase we first need to execute replaying threads
//        if (eventStructure.inReplayPhase() && !eventStructure.inReplayPhase(iThread)) {
//            return ThreadSwitchDecision.MAY
//        }

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
        return if (runner.currentExecutionPart == ExecutionPart.PARALLEL)
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
        val forkedThreads = (0 until eventStructure.nThreads)
            .filter { it != eventStructure.mainThreadId && it != eventStructure.initThreadId }
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

    private fun registerTestInstance() {
        check(!isTestInstanceRegistered)
        eventStructure.addObjectAllocationEvent(eventStructure.mainThreadId, runner.testInstance.opaque())
        isTestInstanceRegistered = true
    }

    override fun onStart(iThread: Int) {
        super.onStart(iThread)
        if (iThread != eventStructure.mainThreadId && iThread != eventStructure.initThreadId) {
            eventStructure.addThreadStartEvent(iThread)
        }
    }

    override fun onFinish(iThread: Int) {
        // TODO: refactor, make `switchCurrentThread` private again in ManagedStrategy,
        //   call overridden `onStart` and `onFinish` methods only when thread is active
        //   and the `currentThread` lock is held
        awaitTurn(iThread)
        // TODO: extract this check into a method ?
        while (eventStructure.inReplayPhase() && !eventStructure.canReplayNextEvent(iThread)) {
            switchCurrentThread(iThread, mustSwitch = true)
        }
        eventStructure.addThreadFinishEvent(iThread)
        super.onFinish(iThread)
    }

    override fun onActorStart(iThread: Int) {
        super.onActorStart(iThread)
        // TODO: move ignored section to ManagedStrategyRunner
        runInIgnoredSection {
            if (runner.currentExecutionPart == ExecutionPart.VALIDATION)
                return@runInIgnoredSection
            val actor = scenario.threads[iThread][currentActorId[iThread]]
            eventStructure.addActorStartEvent(iThread, actor)
        }
    }

    override fun onActorFinish(iThread: Int) {
        // TODO: move ignored section to ManagedStrategyRunner
        runInIgnoredSection {
            if (runner.currentExecutionPart == ExecutionPart.VALIDATION)
                return@runInIgnoredSection
            val actor = scenario.threads[iThread][currentActorId[iThread]]
            eventStructure.addActorEndEvent(iThread, actor)
        }
        super.onActorFinish(iThread)
    }

    private fun onInconsistency(inconsistency: Inconsistency) {
        suddenInvocationResult = InconsistentInvocationResult(inconsistency)
        throw ForcibleExecutionFinishError
    }

    override fun afterCoroutineSuspended(iThread: Int) {
        eventStructure.addCoroutineSuspendRequestEvent(iThread, currentActorId[iThread])
        super.afterCoroutineSuspended(iThread)
    }

    override fun afterCoroutineResumed(iThread: Int) {
        super.afterCoroutineResumed(iThread)
        eventStructure.addCoroutineSuspendResponseEvent(iThread, currentActorId[iThread])
    }

    override fun afterCoroutineCancelled(iThread: Int, promptCancellation: Boolean, cancellationResult: CancellationResult) {
        super.afterCoroutineCancelled(iThread, promptCancellation, cancellationResult)
        if (cancellationResult == CancellationResult.CANCELLATION_FAILED)
            return
        eventStructure.addCoroutineSuspendRequestEvent(iThread, currentActorId[iThread], promptCancellation)
        eventStructure.addCoroutineCancelResponseEvent(iThread, currentActorId[iThread])
    }

    override fun onResumeCoroutine(iThread: Int, iResumedThread: Int, iResumedActor: Int) {
        super.onResumeCoroutine(iThread, iResumedThread, iResumedActor)
        eventStructure.addCoroutineResumeEvent(iThread, iResumedThread, iResumedActor)
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

typealias ReportInconsistencyCallback = (Inconsistency) -> Unit
typealias InternalThreadSwitchCallback = (ThreadID, SwitchReason) -> Unit

private class EventStructureObjectTracker(
    private val eventStructure: EventStructure,
) : ObjectTracker {

    override fun registerNewObject(obj: Any) {
        val iThread = (Thread.currentThread() as TestThread).threadId
        eventStructure.addObjectAllocationEvent(iThread, obj.opaque())
    }

    override fun registerObjectLink(fromObject: Any, toObject: Any?) {}

    override fun initializeObject(obj: Any) {
        val isRegistered = (eventStructure.objectRegistry[obj.opaque()] != null)
        if (!isRegistered && !obj.isPrimitive()) {
            registerNewObject(obj)
        }
    }

    override fun shouldTrackObjectAccess(obj: Any): Boolean = true

    override fun getObjectId(obj: Any): ObjectID {
        return eventStructure.objectRegistry.getOrRegisterObjectID(obj.opaque())
    }

    override fun reset() {}

}

private class EventStructureMemoryTracker(
    private val eventStructure: EventStructure,
    private val objectTracker: ObjectTracker,
) : MemoryTracker {

    private val objectRegistry: ObjectRegistry
        get() = eventStructure.objectRegistry

    private fun getValueID(location: MemoryLocation, value: OpaqueValue?): ValueID =
        objectRegistry.getOrRegisterValueID(location.type, value)

    private fun getValue(location: MemoryLocation, valueID: ValueID) =
        objectRegistry.getValue(location.type, valueID)

    private fun getValue(type: Type, valueID: ValueID) =
        objectRegistry.getValue(type, valueID)

    private fun addWriteEvent(iThread: Int, codeLocation: Int, location: MemoryLocation, value: OpaqueValue?,
                              rmwWriteDescriptor: ReadModifyWriteDescriptor? = null) {
        // force evaluation of initial value (before possibly overwriting it)
        // TODO: refactor this!
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
                label.location.write(newValue?.unwrap(), objectRegistry::getValue)
                return getValue(label.location, label.value)
            }

            is ReadModifyWriteDescriptor.CompareAndSetDescriptor -> {
                if (label.value == rmwDescriptor.expectedValue) {
                    val newValueID = rmwDescriptor.newValue
                    val newValue = getValue(label.location, newValueID)
                    eventStructure.addWriteEvent(iThread, label.codeLocation, label.location, newValueID, rmwDescriptor)
                    label.location.write(newValue?.unwrap(), objectRegistry::getValue)
                    return getValue(Type.BOOLEAN_TYPE, true.toInt().toLong())
                }
                return getValue(Type.BOOLEAN_TYPE, false.toInt().toLong())
            }

            is ReadModifyWriteDescriptor.CompareAndExchangeDescriptor -> {
                if (label.value == rmwDescriptor.expectedValue) {
                    val newValueID = rmwDescriptor.newValue
                    val newValue = getValue(label.location, newValueID)
                    eventStructure.addWriteEvent(iThread, label.codeLocation, label.location, newValueID, rmwDescriptor)
                    label.location.write(newValue?.unwrap(), objectRegistry::getValue)
                }
                return getValue(label.location, label.value)
            }

            is ReadModifyWriteDescriptor.FetchAndAddDescriptor -> {
                val newValueID = label.value + rmwDescriptor.delta
                val newValue = getValue(label.location, newValueID)
                eventStructure.addWriteEvent(iThread, label.codeLocation, label.location, newValueID, rmwDescriptor)
                label.location.write(newValue?.unwrap(), objectRegistry::getValue)
                return when (rmwDescriptor.kind) {
                    ReadModifyWriteDescriptor.IncrementKind.Pre  -> getValue(label.location, label.value)
                    ReadModifyWriteDescriptor.IncrementKind.Post -> getValue(label.location, newValueID)
                }
            }
        }
    }

    override fun beforeWrite(iThread: Int, codeLocation: Int, location: MemoryLocation, value: Any?) {
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
            LincheckJavaAgent.ensureObjectIsTransformed(it)
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
            writeLocation.write(value, objectRegistry::getValue)
        }
    }

    override fun reset() {}

}

private class EventStructureMonitorTracker(
    private val eventStructure: EventStructure,
    private val objectRegistry: ObjectRegistry,
) : MonitorTracker {

    // for each mutex object acquired by some thread,
    // this map stores a mapping from the mutex object to the lock-response event;
    // to handle lock re-entrance, we actually store a stack of lock-response events
    private val lockStacks = mutableMapOf<ValueID, MutableList<AtomicThreadEvent>>()

    // for threads waiting on the mutex,
    // stores the lock stack of the current thread for the awaited mutex
    private val waitLockStack = ArrayIntMap<MutableList<AtomicThreadEvent>>(eventStructure.nThreads)

    private fun canAcquireMonitor(iThread: Int, mutexID: ValueID): Boolean {
        val lockStack = lockStacks[mutexID]
        return (lockStack == null) || (lockStack.last().threadId == iThread)
    }

    private fun canAcquireMonitor(iThread: Int, monitor: OpaqueValue): Boolean {
        val mutexID = objectRegistry[monitor]!!.id
        return canAcquireMonitor(iThread, mutexID)
    }

    override fun acquireMonitor(iThread: Int, monitor: Any): Boolean {
        // issue lock-request event
        val lockRequest = issueLockRequest(iThread, monitor.opaque())
        // if lock is acquired by another thread then postpone addition of lock-response event
        if (!canAcquireMonitor(iThread, monitor.opaque()))
            return false
        // try to add lock-response event
        val lockResponse = tryCompleteLockResponse(lockRequest)
        // return true if the lock-response event was created successfully
        return (lockResponse != null)
    }

    override fun releaseMonitor(iThread: Int, monitor: Any) {
        issueUnlock(iThread, monitor.opaque())
    }

    private fun issueLockRequest(iThread: Int, monitor: OpaqueValue): AtomicThreadEvent {
        val mutexID = objectRegistry[monitor]!!.id
        // check if the thread is already blocked on the lock-request
        val blockingRequest = eventStructure.getPendingBlockingRequest(iThread)
            ?.ensure { it.label.satisfies<LockLabel> { this.mutexID == mutexID } }
        if (blockingRequest != null)
            return blockingRequest
        // check if it is a re-entrance lock and obtain lock re-entrance depth
        val lockStack = lockStacks[mutexID]
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
            lockStacks.updateInplace(mutexID, default = mutableListOf()) {
                check(isNotEmpty() implies { last().threadId == lockResponse.threadId })
                add(lockResponse)
            }
        }
    }

    private fun issueUnlock(iThread: Int, monitor: OpaqueValue): AtomicThreadEvent {
        val mutexID = objectRegistry[monitor]!!.id
        // obtain current lock-responses stack, and ensure that
        // the lock is indeed acquired by the releasing thread
        val lockStack = lockStacks[mutexID]!!
            .ensure { it.isNotEmpty() && (it.last().threadId == iThread) }
        val depth = lockStack.size
        // add unlock event to the event structure
        return eventStructure.addUnlockEvent(iThread, monitor,
            isReentry = (depth > 1),
            reentrancyDepth = depth,
        ).also {
            // remove last lock-response event from the stack,
            // since we just released the lock one time
            lockStack.removeLast()
            if (lockStack.isEmpty()) {
                lockStacks.remove(mutexID)
            }
        }
    }

    override fun isWaiting(iThread: Int): Boolean {
        val blockingRequest = eventStructure.getPendingBlockingRequest(iThread)
            ?.takeIf { (it.label is LockLabel || it.label is WaitLabel) }
            ?: return false
        val mutexID = (blockingRequest.label as MutexLabel).mutexID
        return !(eventStructure.isPendingUnblockedRequest(blockingRequest) &&
                canAcquireMonitor(iThread, mutexID))
    }

    override fun waitOnMonitor(iThread: Int, monitor: Any): Boolean {
        val mutexID = objectRegistry[monitor.opaque()]!!.id
        // check if the thread is already blocked on wait-request or (synthetic) lock-request
        val blockingRequest = eventStructure.getPendingBlockingRequest(iThread)
            ?.ensure { it.label.satisfies<MutexLabel> { this.mutexID == mutexID } }
            ?.ensure { it.label is LockLabel || it.label is WaitLabel }
        var waitRequest = blockingRequest?.takeIf { it.label is WaitLabel }
        var lockRequest = blockingRequest?.takeIf { it.label is LockLabel }
        // if the thread is not blocked yet, issue wait-request event;
        // this procedure will also add synthetic unlock event
        if (blockingRequest == null) {
            check(waitLockStack[iThread] == null)
            waitRequest = issueWaitRequest(iThread, monitor.opaque())
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
        if (!canAcquireMonitor(iThread, mutexID))
            return true
        val lockResponse = tryCompleteWaitLockResponse(lockRequest)
        // exit waiting if the lock response was added successfully
        return (lockResponse == null)
    }

    override fun notify(iThread: Int, monitor: Any, notifyAll: Boolean) {
        issueNotify(iThread, monitor.opaque(), notifyAll)
    }

    private fun issueWaitRequest(iThread: Int, monitor: OpaqueValue): AtomicThreadEvent {
        val mutexID = objectRegistry[monitor]!!.id
        // obtain the current lock-responses stack, and ensure that
        // the lock is indeed acquired by the waiting thread
        val lockStack = lockStacks[mutexID]!!
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
        lockStacks.remove(mutexID)
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
            lockStacks.put(mutexID, lockStack).ensureNull()
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

private class EventStructureParkingTracker(
    private val eventStructure: EventStructure,
) : ParkingTracker {

    override fun park(iThread: Int) {
        eventStructure.addParkRequestEvent(iThread)
    }

    override fun waitUnpark(iThread: Int): Boolean {
        val parkRequest = eventStructure.getPendingBlockingRequest(iThread)
            ?.takeIf { it.label is ParkLabel }
            ?: return false
        val parkResponse = eventStructure.addParkResponseEvent(parkRequest)
        return (parkResponse == null)
    }

    override fun unpark(iThread: Int, unparkedThreadId: Int) {
        eventStructure.addUnparkEvent(iThread, unparkedThreadId)
    }

    override fun isParked(iThread: Int): Boolean {
        val blockingRequest = eventStructure.getPendingBlockingRequest(iThread)
            ?.takeIf { it.label is ParkLabel }
            ?: return false
        return !eventStructure.isPendingUnblockedRequest(blockingRequest)
    }

    override fun reset() {}

}

internal const val SPIN_BOUND = 5