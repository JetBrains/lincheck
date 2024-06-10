/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking

import sun.nio.ch.lincheck.TestThread
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.ObjectLabelFactory.cleanObjectNumeration
import java.lang.reflect.*
import java.util.*
import kotlin.random.Random

/**
 * The model checking strategy studies all possible interleavings by increasing the
 * interleaving tree depth -- the number of context switches performed by the strategy.
 *
 * To restrict the number of interleaving to be studied, it is specified in [testCfg].
 * The strategy constructs an interleaving tree, where nodes choose where the next
 * context switch should be performed and to which thread.
 *
 * The strategy does not study the same interleaving twice.
 * The depth of the interleaving tree increases gradually when all possible
 * interleavings of the previous depth are studied. On the current level,
 * the interleavings are studied uniformly, to study as many different ones
 * as possible when the maximal number of interleavings to be studied is lower
 * than the number of all possible interleavings on the current depth level.
 */
internal class ModelCheckingStrategy(
    testCfg: ModelCheckingCTestConfiguration,
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunction: Actor?,
    stateRepresentation: Method?,
    val replay: Boolean,
) : ManagedStrategy(testClass, scenario, validationFunction, stateRepresentation, testCfg, memoryTrackingEnabled = false) {
    // The maximum number of thread switch choices that strategy should perform
    // (increases when all the interleavings with the current depth are studied).
    private var maxNumberOfSwitches = 0
    // The root of the interleaving tree that chooses the starting thread.
    private var root: InterleavingTreeNode = ThreadChoosingNode((0 until nThreads).toList())
    // This random is used for choosing the next unexplored interleaving node in the tree.
    private val generationRandom = Random(0)
    // The interleaving that will be studied on the next invocation.
    private lateinit var currentInterleaving: Interleaving

    // Tracker of objects' allocations and object graph topology.
    override val objectTracker: ObjectTracker = LocalObjectManager()
    // Model checking strategy does not intercept shared memory accesses.
    override val memoryTracker: MemoryTracker? = null
    // Tracker of the monitors' operations.
    override val monitorTracker: MonitorTracker = ModelCheckingMonitorTracker(nThreads)
    // Tracker of the thread parking.
    override val parkingTracker: ParkingTracker = ModelCheckingParkingTracker(nThreads, allowSpuriousWakeUps = true)

    override fun nextInvocation(): Boolean {
        currentInterleaving = root.nextInterleaving()
            ?: return false
        return true
    }

    override fun initializeInvocation() {
        super.initializeInvocation()
        currentInterleaving.initialize()
    }

    override fun enableSpinCycleReplay() {
        super.enableSpinCycleReplay()
        currentInterleaving.rollbackAfterSpinCycleFound()
    }

    /**
     * If the plugin enabled and the failure has a trace, passes information about
     * the trace and the failure to the Plugin and run re-run execution to debug it.
     */
    internal fun runReplayIfPluginEnabled(failure: LincheckFailure) {
        if (replay && failure.trace != null) {
            // Extract trace representation in the appropriate view.
            val trace = constructTraceForPlugin(failure, failure.trace)
            // Collect and analyze the exceptions thrown.
            val (exceptionsRepresentation, internalBugOccurred) = collectExceptionsForPlugin(failure)
            // If an internal bug occurred - print it on the console, no need to debug it.
            if (internalBugOccurred) return
            // Provide all information about the failed test to the debugger.
            testFailed(
                failureType = failure.type,
                trace = trace,
                version = lincheckVersion,
                minimalPluginVersion = MINIMAL_PLUGIN_VERSION,
                exceptions = exceptionsRepresentation
            )
            // Replay execution while it's needed.
            doReplay()
            while (shouldReplayInterleaving()) {
                doReplay()
            }
        }
    }

    override fun shouldInvokeBeforeEvent(): Boolean {
        // We do not check `inIgnoredSection` here because this method is called from instrumented code
        // that should be invoked only outside the ignored section.
        // However, we cannot add `!inIgnoredSection` check here
        // as the instrumented code might call `enterIgnoredSection` just before this call.
        return replay && collectTrace &&
                Thread.currentThread() is TestThread &&
                suddenInvocationResult == null &&
                !shouldSkipNextBeforeEvent()

    }


    /**
     * We provide information about the failure type to the Plugin, but
     * due to difficulties with passing objects like LincheckFailure (as class versions may vary),
     * we use its string representation.
     * The Plugin uses this information to show the failure type to a user.
     */
    private val LincheckFailure.type: String
        get() = when (this) {
            is IncorrectResultsFailure -> "INCORRECT_RESULTS"
            is ObstructionFreedomViolationFailure -> "OBSTRUCTION_FREEDOM_VIOLATION"
            is UnexpectedExceptionFailure -> "UNEXPECTED_EXCEPTION"
            is ValidationFailure -> "VALIDATION_FAILURE"
            is ManagedDeadlockFailure, is TimeoutFailure -> "DEADLOCK"
        }


    private fun doReplay(): InvocationResult {
        cleanObjectNumeration()
        currentInterleaving = currentInterleaving.copy()
        resetEventIdProvider()
        return runInvocation()
    }

    /**
     * Processes the exceptions was thrown during the execution.
     * @return exceptions string representation to pass
     * to the plugin with a flag, indicating if an internal bug was the cause of the failure, or not.
     */
    private fun collectExceptionsForPlugin(failure: LincheckFailure): ExceptionProcessingResult {
        val results: ExecutionResult = when (failure) {
            is IncorrectResultsFailure -> (failure as? IncorrectResultsFailure)?.results ?: return ExceptionProcessingResult(emptyArray(), isInternalBugOccurred = false)
            is ValidationFailure -> return ExceptionProcessingResult(arrayOf(failure.exception.text), isInternalBugOccurred = false)
            else -> return ExceptionProcessingResult(emptyArray(), isInternalBugOccurred = false)
        }
        return when (val exceptionsProcessingResult = collectExceptionStackTraces(results)) {
            // If some exception was thrown from the Lincheck itself, we'll ask for bug reporting
            is InternalLincheckBugResult ->
                ExceptionProcessingResult(arrayOf(exceptionsProcessingResult.exception.text), isInternalBugOccurred = true)
            // Otherwise collect all the exceptions
            is ExceptionStackTracesResult -> {
                exceptionsProcessingResult.exceptionStackTraces.entries
                    .sortedBy { (_, numberAndStackTrace) -> numberAndStackTrace.number }
                    .map { (exception, numberAndStackTrace) ->
                        val header = exception::class.java.canonicalName + ": " + exception.message
                        header + numberAndStackTrace.stackTrace.joinToString("") { "\n\tat $it" }
                    }
                    .let { ExceptionProcessingResult(it.toTypedArray(), isInternalBugOccurred = false) }
            }
        }
    }

    /**
     * Result of creating string representations of exceptions
     * thrown during the execution before passing them to the plugin.
     *
     * @param exceptionsRepresentation string representation of all the exceptions
     * @param isInternalBugOccurred a flag indicating that the exception is caused by a bug in the Lincheck.
     */
    @Suppress("ArrayInDataClass")
    private data class ExceptionProcessingResult(
        val exceptionsRepresentation: Array<String>,
        val isInternalBugOccurred: Boolean
    )

    /**
     * Transforms failure trace to the array of string to pass it to the debugger.
     * (due to difficulties with passing objects like List and TracePoint, as class versions may vary)
     *
     * Each trace point is transformed into the line of type:
     * "type,iThread,callDepth,shouldBeExpanded,eventId,representation".
     *
     * Later, when [testFailed] breakpoint is triggered debugger parses these lines back to trace points.
     *
     * To help the plugin to create execution view, we provide a type for each trace point.
     * Below are the codes of trace point types.
     *
     * | Value                          | Code |
     * |--------------------------------|------|
     * | REGULAR                        | 0    |
     * | ACTOR                          | 1    |
     * | RESULT                         | 2    |
     * | SWITCH                         | 3    |
     * | SPIN_CYCLE_START               | 4    |
     * | SPIN_CYCLE_SWITCH              | 5    |
     * | OBSTRUCTION_FREEDOM_VIOLATION  | 6    |
     */
    private fun constructTraceForPlugin(failure: LincheckFailure, trace: Trace): Array<String> {
        val results = failure.results
        val nodesList = constructTraceGraph(failure, results, trace, collectExceptionsOrEmpty(failure))
        var sectionIndex = 0
        var node: TraceNode? = nodesList.firstOrNull()
        val representations = mutableListOf<String>()
        while (node != null) {
            when (node) {
                is TraceLeafEvent -> {
                    val event = node.event
                    val eventId = event.eventId
                    val representation = event.toStringImpl(withLocation = false)
                    val type = when (event) {
                        is SwitchEventTracePoint -> {
                            when (event.reason) {
                                SwitchReason.ACTIVE_LOCK -> {
                                    5
                                }
                                else -> 3
                            }
                        }
                        is SpinCycleStartTracePoint -> 4
                        is ObstructionFreedomViolationExecutionAbortTracePoint -> 6
                        else -> 0
                    }

                    if (representation.isNotEmpty()) {
                        representations.add("$type;${node.iThread};${node.callDepth};${node.shouldBeExpanded(false)};${eventId};${representation}")
                    }
                }

                is CallNode -> {
                    val beforeEventId = node.call.eventId
                    val representation = node.call.toStringImpl(withLocation = false)
                    if (representation.isNotEmpty()) {
                        representations.add("0;${node.iThread};${node.callDepth};${node.shouldBeExpanded(false)};${beforeEventId};${representation}")
                    }
                }

                is ActorNode -> {
                    val beforeEventId = -1
                    val representation = node.actorRepresentation
                    if (representation.isNotEmpty()) {
                        representations.add("1;${node.iThread};${node.callDepth};${node.shouldBeExpanded(false)};${beforeEventId};${representation}")
                    }
                }

                is ActorResultNode -> {
                    val beforeEventId = -1
                    val representation = node.resultRepresentation.toString()
                    representations.add("2;${node.iThread};${node.callDepth};${node.shouldBeExpanded(false)};${beforeEventId};${representation};${node.exceptionNumberIfExceptionResult ?: -1}")
                }

                else -> {}
            }

            node = node.next
            if (node == null && sectionIndex != nodesList.lastIndex) {
                node = nodesList[++sectionIndex]
            }
        }
        return representations.toTypedArray()
    }

    private fun collectExceptionsOrEmpty(failure: LincheckFailure): Map<Throwable, ExceptionNumberAndStacktrace> {
        if (failure is ValidationFailure) {
            return mapOf(failure.exception to ExceptionNumberAndStacktrace(1, failure.exception.stackTrace.toList()))
        }
        val results = (failure as? IncorrectResultsFailure)?.results ?: return emptyMap()
        return when (val result = collectExceptionStackTraces(results)) {
            is ExceptionStackTracesResult -> result.exceptionStackTraces
            is InternalLincheckBugResult -> emptyMap()
        }
    }

    override fun onNewSwitch(iThread: Int, mustSwitch: Boolean) {
        if (replay && collectTrace) {
            onThreadSwitchesOrActorFinishes()
        }
        if (mustSwitch) {
            // Create new execution position if this is a forced switch.
            // All other execution positions are covered by `shouldSwitch` method,
            // but forced switches do not ask `shouldSwitch`, because they are forced.
            // a choice of this execution position will mean that the next switch is the forced one.
            currentInterleaving.newExecutionPosition(iThread)
        }
    }

    override fun shouldSwitch(iThread: Int): ThreadSwitchDecision {
        // Crete a new current position in the same place as where the check is,
        // because the position check and the position increment are dual operations.
        check(iThread == currentThread)
        currentInterleaving.newExecutionPosition(iThread)
        return if (currentInterleaving.isSwitchPosition())
            ThreadSwitchDecision.MAY
        else
            ThreadSwitchDecision.NOT
    }



    override fun chooseThread(iThread: Int): Int =
        currentInterleaving.chooseThread(iThread)

    /**
     * An abstract node with an execution choice in the interleaving tree.
     */
    private abstract inner class InterleavingTreeNode {
        private var fractionUnexplored = 1.0
        lateinit var choices: List<Choice>
        var isFullyExplored: Boolean = false
            protected set
        val isInitialized get() = ::choices.isInitialized

        fun nextInterleaving(): Interleaving? {
            if (isFullyExplored) {
                // Increase the maximum number of switches that can be used,
                // because there are no more not covered interleavings
                // with the previous maximum number of switches.
                maxNumberOfSwitches++
                resetExploration()
            }
            // Check if everything is fully explored and there are no possible interleavings with more switches.
            if (isFullyExplored) return null
            return nextInterleaving(InterleavingBuilder())
        }

        abstract fun nextInterleaving(interleavingBuilder: InterleavingBuilder): Interleaving

        protected fun resetExploration() {
            if (!isInitialized) {
                // This is a leaf node.
                isFullyExplored = false
                fractionUnexplored = 1.0
                return
            }
            choices.forEach { it.node.resetExploration() }
            updateExplorationStatistics()
        }

        fun finishExploration() {
            isFullyExplored = true
            fractionUnexplored = 0.0
        }

        protected fun updateExplorationStatistics() {
            check(isInitialized) { "An interleaving tree node was not initialized properly. " +
                    "Probably caused by non-deterministic behaviour (WeakHashMap, Object.hashCode, etc)" }
            if (choices.isEmpty()) {
                finishExploration()
                return
            }
            val total = choices.fold(0.0) { acc, choice ->
                acc + choice.node.fractionUnexplored
            }
            fractionUnexplored = total / choices.size
            isFullyExplored = choices.all { it.node.isFullyExplored }
        }

        protected fun chooseUnexploredNode(): Choice {
            if (choices.size == 1) return choices.first()
            // Choose a weighted random child.
            val total = choices.sumOf { it.node.fractionUnexplored }
            val random = generationRandom.nextDouble() * total
            var sumWeight = 0.0
            choices.forEach { choice ->
                sumWeight += choice.node.fractionUnexplored
                if (sumWeight >= random)
                    return choice
            }
            // In case of errors because of floating point numbers choose the last unexplored choice.
            return choices.last { !it.node.isFullyExplored }
        }
    }

    /**
     * Represents a choice of a thread that should be next in the execution.
     */
    private inner class ThreadChoosingNode(switchableThreads: List<Int>) : InterleavingTreeNode() {
        init {
            choices = switchableThreads.map { Choice(SwitchChoosingNode(), it) }
        }

        override fun nextInterleaving(interleavingBuilder: InterleavingBuilder): Interleaving {
            val child = chooseUnexploredNode()
            interleavingBuilder.addThreadSwitchChoice(child.value)
            val interleaving = child.node.nextInterleaving(interleavingBuilder)
            updateExplorationStatistics()
            return interleaving
        }
    }

    /**
     * Represents a choice of a position of a thread context switch.
     */
    private inner class SwitchChoosingNode : InterleavingTreeNode() {
        override fun nextInterleaving(interleavingBuilder: InterleavingBuilder): Interleaving {
            val isLeaf = maxNumberOfSwitches == interleavingBuilder.numberOfSwitches
            if (isLeaf) {
                finishExploration()
                if (!isInitialized)
                    interleavingBuilder.addLastNoninitializedNode(this)
                return interleavingBuilder.build()
            }
            val choice = chooseUnexploredNode()
            interleavingBuilder.addSwitchPosition(choice.value)
            val interleaving = choice.node.nextInterleaving(interleavingBuilder)
            updateExplorationStatistics()
            return interleaving
        }
    }

    private inner class Choice(val node: InterleavingTreeNode, val value: Int) {
        override fun toString(): String {
            return "Choice(node=$node, value=$value)"
        }
    }

    /**
     * This class specifies an interleaving that is re-producible.
     */
    private inner class Interleaving(
        /**
         * Numbers of execution positions [executionPosition] where thread switch must be performed.
         */
        private val switchPositions: List<Int>,
        /**
         * Numbers of the threads where to switch if the [switchPositions].
         */
        private val threadSwitchChoices: List<Int>,
        /**
         * The next not initialized switch node. It's stored as a field because sometimes execution may be replayed
         * due to spin cycles, and we have to drop information about odd executions, that was performed during
         * unnecessary spin cycle iterations.
         */
        private val initialLastNotInitializedNode: SwitchChoosingNode?
    ) {
        private var lastNotInitializedNode: SwitchChoosingNode? = initialLastNotInitializedNode
        private lateinit var interleavingFinishingRandom: Random
        private lateinit var nextThreadToSwitch: Iterator<Int>
        private var lastNotInitializedNodeChoices: MutableList<Choice>? = null
        private var executionPosition: Int = 0

        fun initialize() {
            executionPosition = -1 // the first execution position will be zero
            interleavingFinishingRandom = Random(2) // random with a constant seed
            nextThreadToSwitch = threadSwitchChoices.iterator()
            lastNotInitializedNodeChoices = null
            lastNotInitializedNode?.let {
                // Create a mutable list for the initialization of the not initialized node choices.
                lastNotInitializedNodeChoices = mutableListOf<Choice>().also { choices ->
                    it.choices = choices
                }
                lastNotInitializedNode = null
            }
        }

        fun rollbackAfterSpinCycleFound() {
            lastNotInitializedNode = initialLastNotInitializedNode
            lastNotInitializedNodeChoices?.clear()
        }

        fun chooseThread(iThread: Int): Int =
            if (nextThreadToSwitch.hasNext()) {
                // Use the predefined choice.
                nextThreadToSwitch.next()
            } else {
                // There is no predefined choice.
                // This can happen if there were forced thread switches after the last predefined one
                // (e.g., thread end, coroutine suspension, acquiring an already acquired lock or monitor.wait).
                // We use a deterministic random here to choose the next thread.
                lastNotInitializedNodeChoices =
                    null // end of execution position choosing initialization because of new switch
                switchableThreads(iThread).random(interleavingFinishingRandom)
            }

        fun isSwitchPosition() = executionPosition in switchPositions

        /**
         * Creates a new execution position that corresponds to the current switch point.
         * Unlike switch points, the execution position is just a gradually increasing counter
         * which helps to distinguish different switch points.
         */
        fun newExecutionPosition(iThread: Int) {
            executionPosition++
            if (executionPosition > switchPositions.lastOrNull() ?: -1) {
                // Add a new thread choosing node corresponding to the switch at the current execution position.
                lastNotInitializedNodeChoices?.add(Choice(ThreadChoosingNode(switchableThreads(iThread)), executionPosition))
            }
        }

        fun copy() = Interleaving(switchPositions, threadSwitchChoices, lastNotInitializedNode)

    }

    private inner class InterleavingBuilder {
        private val switchPositions = mutableListOf<Int>()
        private val threadSwitchChoices = mutableListOf<Int>()
        private var lastNoninitializedNode: SwitchChoosingNode? = null

        val numberOfSwitches get() = switchPositions.size

        fun addSwitchPosition(switchPosition: Int) {
            switchPositions.add(switchPosition)
        }

        fun addThreadSwitchChoice(iThread: Int) {
            threadSwitchChoices.add(iThread)
        }

        fun addLastNoninitializedNode(lastNoninitializedNode: SwitchChoosingNode) {
            this.lastNoninitializedNode = lastNoninitializedNode
        }

        fun build() = Interleaving(switchPositions, threadSwitchChoices, lastNoninitializedNode)
    }
}

/**
 * Manages objects created within the local scope.
 * The purpose of this manager is to keep track of locally created objects that aren't yet shared
 * and automatically delete their dependencies when they become shared.
 * This tracking helps to avoid exploring unnecessary interleavings, which can occur if access to such local
 * objects triggers switch points in the model checking strategy.
 */
internal class LocalObjectManager : ObjectTracker {
    /**
     * An identity hash map holding each local object and its dependent objects.
     * Each local object is a key, and its value is a list of objects accessible from it.
     * Note that non-local objects are excluded from this map.
     */
    private val localObjects = IdentityHashMap<Any, MutableList<Any>>()

    /**
     * Registers a new object as a locally accessible one.
     */
    override fun registerNewObject(obj: Any) {
        check(obj !== StaticObject)
        localObjects[obj] = mutableListOf()
    }

    override fun registerObjectLink(fromObject: Any, toObject: Any?) {
        if (toObject == null) return
        if (fromObject === StaticObject) {
            markObjectNonLocal(toObject)
        }
        val reachableObjects = localObjects[fromObject]
        if (reachableObjects != null) {
            check(toObject !== StaticObject)
            reachableObjects.add(toObject)
        } else {
            markObjectNonLocal(toObject)
        }
    }

    /**
     * Removes the specified local object and its dependencies from the set of local objects.
     * If the removing object references other local objects, they are also removed recursively.
     */
    private fun markObjectNonLocal(obj: Any?) {
        if (obj == null) return
        val objects = localObjects.remove(obj) ?: return
        objects.forEach { markObjectNonLocal(it) }
    }

    override fun initializeObject(obj: Any) {
        throw UnsupportedOperationException("Model checking strategy does not track object initialization")
    }

    override fun shouldTrackObjectAccess(obj: Any): Boolean =
        !isLocalObject(obj)

    /**
     * Checks if an object is only locally accessible.
     */
    private fun isLocalObject(obj: Any?) = localObjects.containsKey(obj)

    override fun getObjectId(obj: Any): ObjectID {
        // model checking strategy does not currently use object IDs
        throw UnsupportedOperationException("Model checking strategy does not track unique object IDs")
        // return System.identityHashCode(obj).toLong()
    }

    override fun reset() {
        localObjects.clear()
    }
}

/**
 * Tracks synchronization operations on the monitors (intrinsic locks)
 */
internal class ModelCheckingMonitorTracker(val nThreads: Int) : MonitorTracker {
    // Maintains a set of acquired monitors with an information on which thread
    // performed the acquisition and the reentrancy depth.
    private val acquiredMonitors = IdentityHashMap<Any, MonitorAcquiringInfo>()

    // Maintains a set of monitors on which each thread is waiting.
    // Note, that a thread can wait on a free monitor if it is waiting for a `notify` call.
    // Stores `null` if thread is not waiting on any monitor.
    private val waitingMonitor = Array<MonitorAcquiringInfo?>(nThreads) { null }

    // Stores `true` for the threads which are waiting for a
    // `notify` call on the monitor stored in `acquiringMonitor`.
    private val waitForNotify = BooleanArray(nThreads) { false }

    /**
     * Performs a logical acquisition.
     */
    override fun acquireMonitor(threadId: Int, monitor: Any): Boolean {
        // Increment the reentrant depth and store the
        // acquisition info if needed.
        val info = acquiredMonitors.computeIfAbsent(monitor) {
            MonitorAcquiringInfo(monitor, threadId, 0)
        }
        if (info.threadId != threadId) {
            waitingMonitor[threadId] = MonitorAcquiringInfo(monitor, threadId, 0)
            return false
        }
        info.timesAcquired++
        waitingMonitor[threadId] = null
        return true
    }

    /**
     * Performs a logical release.
     */
    override fun releaseMonitor(threadId: Int, monitor: Any) {
        // Decrement the reentrancy depth and remove the acquisition info
        // if the monitor becomes free to acquire by another thread.
        val info = acquiredMonitors[monitor]!!
        info.timesAcquired--
        if (info.timesAcquired == 0)
            acquiredMonitors.remove(monitor)
    }

    /**
     * Returns `true` if the corresponding thread is waiting on some monitor.
     */
    override fun isWaiting(threadId: Int): Boolean {
        val monitor = waitingMonitor[threadId]?.monitor ?: return false
        return waitForNotify[threadId] || !canAcquireMonitor(threadId, monitor)
    }

    /**
     * Returns `true` if the monitor is already acquired by
     * the thread [threadId], or if this monitor is free to acquire.
     */
    fun canAcquireMonitor(threadId: Int, monitor: Any) =
        acquiredMonitors[monitor]?.threadId?.equals(threadId) ?: true

    /**
     * Performs a logical wait, [isWaiting] for the specified thread
     * returns `true` until the corresponding [notify] is invoked.
     */
    override fun waitOnMonitor(threadId: Int, monitor: Any): Boolean {
        // TODO: we can add spurious wakeups here
        var info = acquiredMonitors[monitor]
        if (info != null) {
            // in case when lock is currently acquired by another thread continue waiting
            if (info.threadId != threadId)
                return true
            // in case when current thread owns the lock we release it
            // in order to give other thread a chance to acquire it
            // and put the current thread into waiting state
            waitForNotify[threadId] = true
            waitingMonitor[threadId] = info
            acquiredMonitors.remove(monitor)
            return true
        }
        // otherwise the lock is held by no-one and can be acquired
        info = waitingMonitor[threadId]
        check(info != null && info.monitor === monitor && info.threadId == threadId) {
            "Monitor should have been acquired by this thread"
        }
        // if there has been no `notify` yet continue waiting
        if (waitForNotify[threadId])
            return true
        // otherwise acquire monitor restoring its re-entrance depth
        acquiredMonitors[monitor] = info
        waitingMonitor[threadId] = null
        return false
    }

    /**
     * Performs the logical `notify`.
     * Always notifies all threads, odd threads will simply have a spurious wakeup.
     */
    override fun notify(threadId: Int, monitor: Any, notifyAll: Boolean) {
        waitingMonitor.forEachIndexed { tid, info ->
            if (monitor === info?.monitor)
                waitForNotify[tid] = false
        }
    }

    override fun reset() {
        acquiredMonitors.clear()
        waitingMonitor.fill(null)
        waitForNotify.fill(false)
    }

    fun copy(): ModelCheckingMonitorTracker {
        val tracker = ModelCheckingMonitorTracker(nThreads)
        acquiredMonitors.forEach { (monitor, info) ->
            tracker.acquiredMonitors[monitor] = info.copy()
        }
        waitingMonitor.forEachIndexed { thread, info ->
            tracker.waitingMonitor[thread] = info?.copy()
        }
        waitForNotify.copyInto(tracker.waitForNotify)
        return tracker
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return (other is ModelCheckingMonitorTracker) &&
                (nThreads == other.nThreads) &&
                (acquiredMonitors == other.acquiredMonitors) &&
                (waitingMonitor.contentEquals(other.waitingMonitor)) &&
                (waitForNotify.contentEquals(other.waitForNotify))
    }

    override fun hashCode(): Int {
        var result = acquiredMonitors.hashCode()
        result = 31 * result + waitingMonitor.contentHashCode()
        result = 31 * result + waitForNotify.contentHashCode()
        return result
    }

    /**
     * Stores the [monitor], id of the thread acquired the monitor [threadId],
     * and the number of reentrant acquisitions [timesAcquired].
     */
    // TODO: monitor should be opaque for the correctness of the generated equals/hashCode (?)
    private data class MonitorAcquiringInfo(val monitor: Any, val threadId: Int, var timesAcquired: Int)
}

class ModelCheckingParkingTracker(val nThreads: Int, val allowSpuriousWakeUps: Boolean = false) : ParkingTracker {

    // stores `true` for the parked threads
    private val parked = BooleanArray(nThreads) { false }

    override fun park(threadId: Int) {
        parked[threadId] = true
    }

    override fun waitUnpark(threadId: Int): Boolean {
        return isParked(threadId)
    }

    override fun unpark(threadId: Int, unparkedThreadId: Int) {
        parked[unparkedThreadId] = false
    }

    override fun isParked(threadId: Int): Boolean =
        parked[threadId] && !allowSpuriousWakeUps

    override fun reset() {
        parked.fill(false)
    }

}