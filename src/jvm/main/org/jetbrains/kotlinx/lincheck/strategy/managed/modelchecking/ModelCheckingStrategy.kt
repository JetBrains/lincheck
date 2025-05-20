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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.runner.ExecutionPart.*
import org.jetbrains.kotlinx.lincheck.traceagent.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck.transformation.isJavaLambdaClass
import org.jetbrains.kotlinx.lincheck.util.*
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
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunction: Actor?,
    stateRepresentation: Method?,
    settings: ManagedStrategySettings,
) : ManagedStrategy(testClass, scenario, validationFunction, stateRepresentation, settings) {
    // The maximum number of thread switch choices that strategy should perform
    // (increases when all the interleavings with the current depth are studied).
    private var maxNumberOfSwitches = 0

    // The root of the interleaving tree.
    // Artificial switch point is inserted before execution starts.
    private var root = SwitchChoosingNode()
        // root is always initialized
        .apply { initialize() }

    // This random is used for choosing the next unexplored interleaving node in the tree.
    private val generationRandom = Random(0)

    // The interleaving that will be studied on the next invocation.
    private lateinit var currentInterleaving: Interleaving

    private var isReplayingSpinCycle = false

    // Tracker of objects' allocations and object graph topology.
    override val objectTracker: ObjectTracker? = if (isInTraceDebuggerMode) null else LocalObjectManager()
    // Tracker of the monitors' operations.
    override val monitorTracker: MonitorTracker = ModelCheckingMonitorTracker()
    // Tracker of the thread parking.
    override val parkingTracker: ParkingTracker = ModelCheckingParkingTracker(allowSpuriousWakeUps = true)

    override fun nextInvocation(): Boolean {
        // if we are in spin-cycle replay mode, then next invocation always exist,
        // since we just repeat the previous one.
        if (isReplayingSpinCycle) {
            return true
        }
        replayNumber = 0
        currentInterleaving = root.nextInterleaving()
            ?: return false
        resetTraceDebuggerTrackerIds()
        return true
    }

    override fun initializeInvocation() {
        super.initializeInvocation()
        currentInterleaving.initialize()
        isReplayingSpinCycle = false
        replayNumber++
    }

    override fun initializeReplay() {
        super.initializeReplay()
        currentInterleaving = currentInterleaving.copy()
    }

    override fun enableSpinCycleReplay() {
        super.enableSpinCycleReplay()
        currentInterleaving.rollbackAfterSpinCycleFound()
        isReplayingSpinCycle = true
    }

    override fun onSwitchPoint(iThread: Int) {
        check(iThread == -1 /* initial thread choice */ || iThread == threadScheduler.scheduledThreadId)
        if (runner.currentExecutionPart != PARALLEL) return
        // in case if `tryAbortingUserThreads` succeeded in aborting execution,
        // we should not insert switch points after it
        if (threadScheduler.areAllThreadsFinishedOrAborted()) return
        // unblock interrupted threads
        unblockInterruptedThreads()
        if (loopDetector.replayModeEnabled) return
        currentInterleaving.onSwitchPoint(iThread)
    }

    override fun shouldSwitch(): Boolean =
        currentInterleaving.isSwitchPosition()

    override fun chooseThread(iThread: Int): Int =
        currentInterleaving.chooseThread(iThread)

    /**
     * An abstract node with an execution choice in the interleaving tree.
     */
    private abstract inner class InterleavingTreeNode {
        abstract val choices: List<Choice>

        abstract val isInitialized: Boolean

        private var fractionUnexplored = 1.0

        var isFullyExplored: Boolean = false
            protected set

        override fun toString(): String = getStringRepresentation()

        protected abstract fun getStringRepresentation(indent: String = ""): String

        protected fun StringBuilder.appendChoices(indent: String = ""): StringBuilder {
            if (isInitialized) {
                append("[" + choices.map { it.value }.joinToString(", ") + "]\n")
                choices.forEach { choice ->
                    append("${indent}\t${choice.value}: ${choice.node.getStringRepresentation("${indent}\t").trim()}\n")
                }
            } else {
                append(" not initialized\n")
            }
            return this
        }

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
            check(isInitialized) {
                """
                    An interleaving tree node was not initialized properly.
                    Probably caused by non-deterministic behaviour (WeakHashMap, Object.hashCode, etc)
                """.trimIndent()
            }
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

        abstract fun getChildNode(choiceValue: Int): InterleavingTreeNode?
    }

    /**
     * Represents a choice of a thread that should be next in the execution.
     */
    private inner class ThreadChoosingNode(switchableThreads: List<Int>) : InterleavingTreeNode() {

        override val choices: List<Choice> =
            switchableThreads.map { Choice(SwitchChoosingNode(), it) }

        override val isInitialized: Boolean = true

        override fun nextInterleaving(interleavingBuilder: InterleavingBuilder): Interleaving {
            val child = chooseUnexploredNode()
            interleavingBuilder.addThreadSwitchChoice(child.value)
            val interleaving = child.node.nextInterleaving(interleavingBuilder)
            updateExplorationStatistics()
            return interleaving
        }

        override fun getChildNode(choiceValue: Int): InterleavingTreeNode? {
            return choices.find { it.value == choiceValue }?.node
        }

        override fun getStringRepresentation(indent: String): String = StringBuilder()
            .append("${indent}ThreadChoosingNode: threads=")
            .appendChoices(indent)
            .toString()
    }

    /**
     * Represents a choice of a position of a thread context switch.
     */
    private inner class SwitchChoosingNode : InterleavingTreeNode() {

        private var _choices: MutableList<Choice> = mutableListOf()

        override val choices: List<Choice>
            get() = _choices

        override var isInitialized: Boolean = false
            private set

        fun initialize() {
            isInitialized = true
        }

        fun reset() {
            _choices.clear()
        }

        fun addChoice(choice: Choice) {
            check(isInitialized) {
                "Node should be initialized."
            }
            _choices.add(choice)
        }

        override fun getChildNode(choiceValue: Int): InterleavingTreeNode? {
            // Here we rely on fact, then switch points are stored sequentially in switch choosing node
            // e.g. [N, N+1, N+2, ..., N+K]. So we calculate the index of switch point `N+M` as difference between
            // `N+M` and the first switch point number in current switch choosing node
            if (choices.isEmpty()) return null
            val index = choiceValue - choices.first().value
            if (index < 0 || index >= choices.size) return null
            return choices[index].node
        }

        override fun nextInterleaving(interleavingBuilder: InterleavingBuilder): Interleaving {
            val isLeaf = maxNumberOfSwitches == interleavingBuilder.numberOfSwitches
            if (isLeaf) {
                finishExploration()
                return interleavingBuilder.build()
            }
            val choice = chooseUnexploredNode()
            interleavingBuilder.addSwitchPosition(choice.value)
            val interleaving = choice.node.nextInterleaving(interleavingBuilder)
            updateExplorationStatistics()
            return interleaving
        }

        override fun getStringRepresentation(indent: String): String = StringBuilder()
            .append("${indent}SwitchChoosingNode: switch points=")
            .appendChoices(indent)
            .toString()
    }

    private inner class Choice(val node: InterleavingTreeNode, val value: Int) {
        override fun toString(): String {
            return "Choice(node=${node.javaClass.simpleName}, value=$value)"
        }
    }

    /**
     * This class specifies an interleaving that is reproducible.
     *
     * @property switchPositions Numbers of execution positions [executionPosition]
     *   where thread switch must be performed.
     * @param threadSwitchChoices Numbers of the threads where to switch if the [switchPositions].
     */
    private inner class Interleaving(
        private val switchPositions: List<Int>,
        private val threadSwitchChoices: List<Int>
    ) {
        // number of the current execution position
        private var executionPosition: Int = 0

        // specifies the index of currently executing thread in 'threadSwitchChoices'
        private var currentInterleavingPosition = 0

        // traverses `SwitchChoosingNode` nodes of an interleaving tree
        private var currentInterleavingNode: SwitchChoosingNode = root

        // tells the strategy is it allowed to insert new switch points to the `currentInterleavingNode`
        private var shouldAddNewSwitchPoints: Boolean = true

        // allows for optimization in which for every interleaving we only push
        // `currentInterleavingNode` deeper in the tree only once
        private var shouldMoveCurrentNode: Boolean = true

        private lateinit var interleavingFinishingRandom: Random

        fun initialize() {
            executionPosition = -1 // the first execution position will be zero
            currentInterleavingPosition = 0
            interleavingFinishingRandom = Random(2) // random with a constant seed
        }

        fun rollbackAfterSpinCycleFound() {
            // execution will be replayed due to spin cycles, so we have to drop information about
            // odd executions that were performed during unnecessary spin cycle iterations
            currentInterleavingNode.reset()
            // we will replay this interleaving,
            // and since we clear out the switch points in the `currentInterleavingNode`
            // we need to insert them once again, thus, we tell strategy to append switch points
            shouldAddNewSwitchPoints = true
            shouldMoveCurrentNode = false
        }

        fun chooseThread(iThread: Int): Int {
            val availableThreads = availableThreads(iThread)
            val nextThread = if (currentInterleavingPosition < threadSwitchChoices.size) {
                // Use the predefined choice.
                val nextThread = threadSwitchChoices[currentInterleavingPosition++]
                // Update current node.
                if (shouldMoveCurrentNode && !loopDetector.replayModeEnabled) {
                    currentInterleavingNode = currentInterleavingNode
                        .getChildNode(executionPosition)!!
                        .getChildNode(nextThread)!!
                        as SwitchChoosingNode
                    // we reached the next `SwitchChoosingNode` node, so mark it as initialized
                    currentInterleavingNode.initialize()
                }
                check(nextThread in availableThreads) {
                    """
                        Trying to switch the execution to thread $nextThread,
                        but only the following threads are eligible to switch: $availableThreads
                    """.trimIndent()
                }
                nextThread
            } else {
                // There is no predefined choice.
                // This can happen if there were forced thread switches after the last predefined one
                // (e.g., thread end, coroutine suspension, acquiring an already acquired lock or monitor.wait).
                // We use a deterministic random here to choose the next thread.
                // end of tracked execution positions, so tell strategy not to generate switch points any further
                shouldAddNewSwitchPoints = false
                // in case no switchable thread available we return -1, this way
                // the strategy will either report an error or stay on the calling
                // thread if the switch was not mandatory
                if (availableThreads.isEmpty()) -1
                else availableThreads.random(interleavingFinishingRandom)
            }
            if (currentInterleavingPosition == threadSwitchChoices.size) {
                shouldMoveCurrentNode = false
            }
            return nextThread
        }

        fun isSwitchPosition() = executionPosition in switchPositions

        /**
         * Creates a new execution position that corresponds to the current switch point.
         * Unlike switch points, the execution position is just a gradually increasing counter
         * which helps to distinguish different switch points.
         */
        fun onSwitchPoint(iThread: Int) {
            executionPosition++
            if (shouldAddNewSwitchPoints && executionPosition > (switchPositions.lastOrNull() ?: -1)) {
                // Add a new thread choosing node corresponding to the switch at the current execution position.
                val choice = Choice(ThreadChoosingNode(availableThreads(iThread)), executionPosition)
                currentInterleavingNode.addChoice(choice)
            }
        }

        fun copy() = Interleaving(switchPositions, threadSwitchChoices)

    }

    private inner class InterleavingBuilder {
        private val switchPositions = mutableListOf<Int>()
        private val threadSwitchChoices = mutableListOf<Int>()

        val numberOfSwitches get() = switchPositions.size

        fun addSwitchPosition(switchPosition: Int) {
            switchPositions.add(switchPosition)
        }

        fun addThreadSwitchChoice(iThread: Int) {
            threadSwitchChoices.add(iThread)
        }

        fun build() = Interleaving(switchPositions, threadSwitchChoices)
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
    private val localObjects : MutableSet<Any> =
        Collections.newSetFromMap(IdentityHashMap())

    override fun registerThread(threadId: Int, thread: Thread) {
        markObjectNonLocal(thread)
    }

    /**
     * Registers a new object as a locally accessible one.
     */
    override fun registerNewObject(obj: Any) {
        check(obj !== StaticObject)
        localObjects.add(obj)
    }

    override fun registerObjectLink(fromObject: Any, toObject: Any?) {
        if (toObject == null) return
        if (!isLocalObject(fromObject)) {
            markObjectNonLocal(toObject)
        }
    }

    /**
     * Removes the specified local object and all reachable objects from the set of local objects.
     */
    private fun markObjectNonLocal(root: Any) {
        traverseObjectGraph(root) { obj ->
            val wasLocal = localObjects.remove(obj)
            if (
                wasLocal ||
                // lambdas do not appear in localObjects because its class is generated at runtime,
                // so we do not instrument its constructor (<init> blocks) invocations
                isJavaLambdaClass(obj.javaClass.name)
            ) obj
            else null
        }
    }

    override fun shouldTrackObjectAccess(obj: Any): Boolean =
        !isLocalObject(obj)

    /**
     * Checks if an object is only locally accessible.
     */
    private fun isLocalObject(obj: Any?) =
        localObjects.contains(obj)

    override fun reset() {
        localObjects.clear()
    }
}

/**
 * Tracks synchronization operations on the monitors (intrinsic locks)
 */
internal class ModelCheckingMonitorTracker : MonitorTracker {
    // Maintains a set of acquired monitors with an information on which thread
    // performed the acquisition and the reentrancy depth.
    private val acquiredMonitors = IdentityHashMap<Any, MonitorAcquiringInfo>()

    // Maintains a set of monitors on which each thread is waiting.
    // Note that a thread can wait on a free monitor if it is waiting for a `notify` call.
    // Stores `null` if the thread is not waiting on any monitor.
    private val waitingMonitor = mutableMapOf<ThreadId, MonitorAcquiringInfo?>()

    override fun registerThread(threadId: Int) {
        waitingMonitor[threadId] = null
    }

    /**
     * Performs a logical acquisition.
     */
    override fun acquireMonitor(threadId: Int, monitor: Any): Boolean {
        // Increment the reentrant depth and store the
        // acquisition info if needed.
        val info = acquiredMonitors.computeIfAbsent(monitor) {
            MonitorAcquiringInfo(monitor, threadId)
        }
        if (info.threadId != threadId) {
            waitingMonitor[threadId] = MonitorAcquiringInfo(monitor, threadId)
            return false
        }
        info.timesAcquired++
        waitingMonitor[threadId] = null
        return true
    }

    /**
     * Performs a logical release.
     */
    override fun releaseMonitor(threadId: Int, monitor: Any): Boolean {
        // Decrement the reentrancy depth and remove the acquisition info
        // if the monitor becomes free to acquire by another thread.
        val info = acquiredMonitors[monitor]!!
        info.timesAcquired--
        if (info.timesAcquired == 0) {
            acquiredMonitors.remove(monitor)
            return true
        }
        return false
    }

    override fun acquiringThreads(monitor: Any): List<ThreadId> {
        return waitingMonitor.values.mapNotNull { info ->
            if (info?.monitor === monitor) info.threadId else null
        }
    }

    /**
     * Returns `true` if the corresponding thread is waiting on some monitor.
     */
    override fun isWaiting(threadId: Int): Boolean {
        val info = waitingMonitor[threadId] ?: return false
        return info.waitForNotify || !canAcquireMonitor(threadId, info.monitor)
    }

    /**
     * Returns `true` if the monitor is already acquired by
     * the thread [threadId], or if this monitor is free to acquire.
     */
    private fun canAcquireMonitor(threadId: Int, monitor: Any) =
        acquiredMonitors[monitor]?.threadId?.equals(threadId) ?: true

    /**
     * Performs a logical wait, [isWaiting] for the specified thread
     * returns `true` until the corresponding [notify] is invoked.
     */
    override fun waitOnMonitor(threadId: Int, monitor: Any): Boolean {
        // TODO: we can add spurious wake-ups here
        var info = acquiredMonitors[monitor]
        if (info != null) {
            // in case when the lock is currently acquired by another thread, continue waiting
            if (info.threadId != threadId) return true
            // in case when current thread owns the lock we release it
            // to give other threads a chance to acquire it,
            // and we put the current thread into the waiting state
            info.waitForNotify = true
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
        if (info.waitForNotify) return true
        // otherwise acquire monitor restoring its re-entrance depth
        acquiredMonitors[monitor] = info
        waitingMonitor[threadId] = null
        return false
    }

    override fun interruptWait(threadId: Int) {
        check(isWaiting(threadId))
        val info = waitingMonitor[threadId]!!
        info.waitForNotify = false
    }

    /**
     * Performs the logical `notify`.
     * Always notifies all threads, odd threads will simply have a spurious wakeup.
     */
    override fun notify(threadId: Int, monitor: Any, notifyAll: Boolean) {
        waitingMonitor.values.forEach { info ->
            if (monitor === info?.monitor && info.waitForNotify) {
                info.waitForNotify = false
            }
        }
    }

    override fun reset() {
        acquiredMonitors.clear()
        waitingMonitor.clear()
    }

    /**
     * Stores the [monitor], id of the thread acquired the monitor [threadId],
     * and the number of reentrant acquisitions [timesAcquired].
     */
    private class MonitorAcquiringInfo(
        val monitor: Any,
        val threadId: Int,
        var timesAcquired: Int = 0,
        var waitForNotify: Boolean = false,
    )
}

class ModelCheckingParkingTracker(val allowSpuriousWakeUps: Boolean = false) : ParkingTracker {

    /**
     * Enum representing the possible states of a thread in the parking tracker:
     *
     *   - [State.UNPARKED] --- thread is not parked;
     *   - [State.PARKED] --- thread is parked and waiting;
     *   - [State.PERMITTED] --- thread has a permit to unpark;
     *   - [State.INTERRUPTED] --- thread was interrupted.
     *
     *     +----------+                     +--------+
     *     | UNPARKED | --- [ park() ] ---> | PARKED |
     *     +----------+                     +--------+
     *     /\      |                          |
     *     |       |--------------------------|
     *     |                            |
     *     |                            | [ unpark() | interrupt() ]
     *     |                            |
     *     |                            v
     *     |                      +-------------------------+
     *     |--[ waitUnpark() ]--  | PERMITTED / INTERRUPTED |
     *                            +-------------------------+
     *
     */
    private enum class State {
        UNPARKED,
        PARKED,
        PERMITTED,
        INTERRUPTED,
    }

    private val threadStates = mutableMapOf<ThreadId, State>()

    override fun registerThread(threadId: Int) {
        threadStates[threadId] = State.UNPARKED
    }

    override fun park(threadId: Int) {
        if (threadStates[threadId] == State.UNPARKED) {
            threadStates[threadId] = State.PARKED
        }
    }

    override fun waitUnpark(threadId: Int, allowSpuriousWakeUp: Boolean): Boolean {
        if (isParked(threadId, allowSpuriousWakeUp)) return true
        threadStates[threadId] = State.UNPARKED
        return false
    }

    override fun unpark(threadId: Int, unparkedThreadId: Int) {
        threadStates[unparkedThreadId] = State.PERMITTED
    }

    override fun interruptPark(threadId: Int) {
        threadStates[threadId] = State.INTERRUPTED
    }

    override fun isParked(threadId: Int): Boolean =
        isParked(threadId, allowSpuriousWakeUp = false)

    private fun isParked(threadId: Int, allowSpuriousWakeUp: Boolean): Boolean {
        if (this.allowSpuriousWakeUps && allowSpuriousWakeUp) {
            return false
        }
        return threadStates[threadId] == State.PARKED
    }

    override fun reset() {
        threadStates.clear()
    }

}
