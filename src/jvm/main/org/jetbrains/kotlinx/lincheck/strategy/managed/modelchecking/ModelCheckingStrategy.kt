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
    testCfg: ModelCheckingCTestConfiguration,
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunction: Actor?,
    stateRepresentation: Method?,
) : ManagedStrategy(testClass, scenario, validationFunction, stateRepresentation, testCfg) {
    // The maximum number of thread switch choices that strategy should perform
    // (increases when all the interleavings with the current depth are studied).
    private var maxNumberOfSwitches = 0
    // The root of the interleaving tree that chooses the starting thread.
    private var root: InterleavingTreeNode = ThreadChoosingNode((0 until nThreads).toList())
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

    override fun enableSpinCycleReplay() {
        super.enableSpinCycleReplay()
        currentInterleaving.rollbackAfterSpinCycleFound()
        isReplayingSpinCycle = true
    }

    override fun initializeReplay() {
        super.initializeReplay()
        currentInterleaving = currentInterleaving.copy()
    }

    override fun shouldInvokeBeforeEvent(): Boolean {
        // We do not check `inIgnoredSection` here because this method is called from instrumented code
        // that should be invoked only outside the ignored section.
        // However, we cannot add `!inIgnoredSection` check here
        // as the instrumented code might call `enterIgnoredSection` just before this call.
        return inIdeaPluginReplayMode && collectTrace &&
                suddenInvocationResult == null &&
                isRegisteredThread() &&
                !shouldSkipNextBeforeEvent()
    }

    override fun onNewSwitch(iThread: Int, mustSwitch: Boolean) {
        if (mustSwitch) {
            // Create new execution position if this is a forced switch.
            // All other execution positions are covered by `shouldSwitch` method,
            // but forced switches do not ask `shouldSwitch`, because they are forced.
            // a choice of this execution position will mean that the next switch is the forced one.
            currentInterleaving.newExecutionPosition(iThread)
        }
    }

    override fun shouldSwitch(iThread: Int): Boolean {
        // Crete a new current position in the same place as where the check is,
        // because the position check and the position increment are dual operations.
        check(iThread == threadScheduler.scheduledThreadId)
        if (runner.currentExecutionPart != PARALLEL) return false
        currentInterleaving.newExecutionPosition(iThread)
        return currentInterleaving.isSwitchPosition()
    }

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

        override fun getStringRepresentation(indent: String): String = StringBuilder()
            .append("${indent}ThreadChoosingNode: threads=")
            .appendChoices(indent)
            .toString()
    }

    /**
     * Represents a choice of a position of a thread context switch.
     */
    private inner class SwitchChoosingNode : InterleavingTreeNode() {

        private var switchPositionChoices: MutableList<Choice>? = null

        override val choices: List<Choice>
            get() = switchPositionChoices ?: listOf()

        override val isInitialized: Boolean
            get() = (switchPositionChoices != null)

        fun initialize() {
            switchPositionChoices = mutableListOf<Choice>()
        }

        fun addChoice(choice: Choice) {
            check(isInitialized)
            switchPositionChoices!!.add(choice)
        }

        fun reset() {
            switchPositionChoices?.clear()
        }

        override fun nextInterleaving(interleavingBuilder: InterleavingBuilder): Interleaving {
            val isLeaf = maxNumberOfSwitches == interleavingBuilder.numberOfSwitches
            if (isLeaf) {
                finishExploration()
                if (!isInitialized)
                    interleavingBuilder.setLeafNode(this)
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
         * Initial leaf switch position node in the tree.
         *
         * It's stored as a field because sometimes execution may be replayed
         * due to spin cycles, and we have to drop information about odd executions that were performed during
         * unnecessary spin cycle iterations.
         */
        private val initialLeafNode: SwitchChoosingNode?
    ) {
        private var leafNode: SwitchChoosingNode? = initialLeafNode
        private var currentInterleavingPosition = 0 // specifies index of currently executing thread in 'threadSwitchChoices'
        private var executionPosition: Int = 0

        private lateinit var interleavingFinishingRandom: Random

        fun initialize() {
            executionPosition = -1 // the first execution position will be zero
            interleavingFinishingRandom = Random(2) // random with a constant seed
            currentInterleavingPosition = 0
            if (leafNode != null) {
                leafNode!!.initialize()
            }
        }

        fun rollbackAfterSpinCycleFound() {
            leafNode = initialLeafNode
            leafNode?.reset()
        }

        fun chooseThread(iThread: Int): Int =
            if (currentInterleavingPosition < threadSwitchChoices.size) {
                // TODO: causes TL on CI
                // check(
                //     // no thread switch happened yet, initial thread id will be returned
                //     executionPosition == -1 ||
                //     // loop detector fully controls 'switchPositions' by itself, thus, 'executionPosition' is always 0, but 'threadSwitchChoices' are still valid
                //     (executionPosition == 0 && loopDetector.replayModeEnabled) ||
                //     // 'threadSwitchChoices.size == switchPositions.size + 1', thus, we subtract 1 from 'currentInterleavingPosition'
                //     // (indexing is correct, because if 'currentInterleavingPosition' is 0, then 'executionPosition == -1' would hold, and we would exit disjunction earlier)
                //     executionPosition == switchPositions[currentInterleavingPosition - 1]
                // ) {
                //     """
                //         Attempt to switch thread on execution position which does not correspond to any saved switch position.
                //         Execution position: $executionPosition, switch positions: $switchPositions.
                //     """.trimIndent()
                // }
                // Use the predefined choice.
                threadSwitchChoices[currentInterleavingPosition++]
            } else {
                // There is no predefined choice.
                // This can happen if there were forced thread switches after the last predefined one
                // (e.g., thread end, coroutine suspension, acquiring an already acquired lock or monitor.wait).
                // We use a deterministic random here to choose the next thread.
                leafNode = null // end of execution position choosing initialization because of new switch
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
            if (executionPosition > (switchPositions.lastOrNull() ?: -1)) {
                // Add a new thread choosing node corresponding to the switch at the current execution position.
                if (leafNode == null) return
                val availableThreads = switchableThreads(iThread)
                // TODO: causes TL on CI
                // val lastThreadSwitchChoice = threadSwitchChoices.lastOrNull()
                // check(lastThreadSwitchChoice !in availableThreads) {
                //     """
                //         Attempt to add a thread choice node with the option for switching to the same thread.
                //         Threads switches: $threadSwitchChoices, added new thread options: $availableThreads, current thread: $iThread.
                //     """.trimIndent()
                // }
                val choice = Choice(ThreadChoosingNode(availableThreads), executionPosition)
                leafNode!!.addChoice(choice)
            }
        }

        fun copy() = Interleaving(switchPositions, threadSwitchChoices, leafNode)

    }

    private inner class InterleavingBuilder {
        private val switchPositions = mutableListOf<Int>()
        private val threadSwitchChoices = mutableListOf<Int>()
        private var leafNode: SwitchChoosingNode? = null

        val numberOfSwitches get() = switchPositions.size

        fun addSwitchPosition(switchPosition: Int) {
            switchPositions.add(switchPosition)
        }

        fun addThreadSwitchChoice(iThread: Int) {
            threadSwitchChoices.add(iThread)
        }

        fun setLeafNode(leafNode: SwitchChoosingNode) {
            this.leafNode = leafNode
        }

        fun build() = Interleaving(switchPositions, threadSwitchChoices, leafNode)
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
            if (wasLocal) obj else null
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
