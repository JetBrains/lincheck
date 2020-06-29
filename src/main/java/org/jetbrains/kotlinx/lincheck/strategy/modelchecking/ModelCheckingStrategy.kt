/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.strategy.modelchecking

import org.jetbrains.kotlinx.lincheck.appendExecutionScenario
import org.jetbrains.kotlinx.lincheck.collectThreadDump
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.strategy.ManagedStrategyBase
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * ModelCheckingStrategy at first studies codeLocations at the choosen thread
 * until a blocking event or a thread finish and
 * then chooses one uniform randomly.
 */
internal class ModelCheckingStrategy(
        testCfg: ModelCheckingCTestConfiguration,
        testClass: Class<*>,
        scenario: ExecutionScenario,
        validationFunctions: List<Method>,
        verifier: Verifier
) : ManagedStrategyBase(testClass, scenario, verifier, validationFunctions, testCfg.hangingDetectionThreshold, testCfg.checkObstructionFreedom) {
    // an increasing id of operation performed within this execution
    private val executionPosition = AtomicInteger(0)
    // ids of operations where a thread should be switched
    private val switchPositions = mutableListOf<Int>()
    // ids of threads to which a thread should switch at the corresponding choices
    private val threadSwitchChoices = mutableListOf<Int>()
    // maximum number of thread switches that managed strategy may use to search for incorrect execution
    private val maxInvocations = testCfg.maxInvocationsPerIteration
    // number of used invocations
    private var usedInvocations = 0
    // maximum number of switches that strategy tries to use
    private var maxNumberOfSwitches = 1
    // the root for the interleaving tree
    private var root: InterleavingNode = ThreadChoosingNode(nThreads)
    // queue of nodes that should be initialized on the next run
    private var notInitializedThreadChoices: LinkedList<ThreadChoosingNode> = LinkedList()
    // queue of nodes that should be initialized on the next run
    private var notInitializedSwitchChoices: LinkedList<SwitchChoosingNode> = LinkedList()
    // iterator over threadSwitchChoices with information about what thread should be next
    private lateinit var nextThreadToSwitch: MutableListIterator<Int>

    @Throws(Exception::class)
    override fun runImpl(): LincheckFailure? {
        while (usedInvocations < maxInvocations) {
            if (root.isFullyExplored) {
                // explored everything with current limit on number of switches
                maxNumberOfSwitches++
                root.resetExploration()
                if (root.isFullyExplored) {
                    // everything is fully explored and number of switches don't change it
                    return null
                }
            }
            root.exploreChild()?.let { return it }
        }
        return null
    }

    override fun onFinish(threadId: Int) {
        // the reason to increment execution position here is to
        // add possibility not to add a switch between last thread switch and
        // the moment when we must switch
        check(threadId == currentThread)
        executionPosition.incrementAndGet()
        super.onFinish(threadId)
    }

    override fun onNewSwitch() {
        val position = executionPosition.get()
        if (lastSwitchPosition() < position) {
            // strictly after the last switch.
            // initialize node with choice of next switch location.
            val node = notInitializedSwitchChoices.poll() ?: return
            node.initialize(position)
        }
    }

    override fun shouldSwitch(threadId: Int): Boolean {
        // the increment of current position is made in the same place as where the check is,
        // because the position check and the position increment are dual operations
        check(threadId == currentThread)
        executionPosition.incrementAndGet()
        return executionPosition.get() in switchPositions
    }

    override fun initializeInvocation() {
        nextThreadToSwitch = threadSwitchChoices.listIterator()
        currentThread = nextThreadToSwitch.next()
        executionPosition.set(-1) // one step before zero
        usedInvocations++
        super.initializeInvocation()
    }

    override fun doSwitchCurrentThread(threadId: Int, mustSwitch: Boolean) {
        if (notInitializedThreadChoices.isNotEmpty() && executionPosition.get() >= lastSwitchPosition()) {
            val node = notInitializedThreadChoices.poll()
            // initialize node with the choice of next thread
            val switchableThreads = switchableThreads(threadId)
            node.initialize(switchableThreads.size)
        }
        super.doSwitchCurrentThread(threadId, mustSwitch)
    }

    override fun chooseThread(switchableThreads: Int): Int {
        // if have a predefined choice, than pick it, otherwise just return a random thread to avoid deadlocks
        return if (nextThreadToSwitch.hasNext())
            nextThreadToSwitch.next()
        else
            random.nextInt(switchableThreads)
    }

    private fun lastSwitchPosition() = switchPositions.lastOrNull() ?: -1

    private abstract inner class InterleavingNode {
        protected var percentageUnexplored = 1.0
        protected lateinit var children: Array<InterleavingNode>
        var isFullyExplored: Boolean = false
            protected set

        abstract fun exploreChild(): LincheckFailure?

        fun resetExploration() {
            percentageUnexplored = 1.0
            isFullyExplored = when {
                isNotInitialized() -> false
                else -> {
                    children.forEach { it.resetExploration() }
                    children.all { it.isFullyExplored }
                }
            }
            percentageUnexplored = if (isFullyExplored) 0.0 else 1.0
        }

        fun finishExploration() {
            isFullyExplored = true
            percentageUnexplored = 0.0
        }

        protected fun updatePercentageExplored() {
            if (children.isEmpty()) return

            val total = children.fold(0.0) { acc, node ->
                acc + node.percentageUnexplored
            }
            percentageUnexplored = total / children.size
            isFullyExplored = children.all { it.isFullyExplored }
        }

        protected fun chooseChild(): Int {
            if (children.size == 1) return 0
            // choose child with a weighted random.
            val total = children.sumByDouble { it.percentageUnexplored }
            val random = generationRandom.nextDouble() * total
            var sumWeight = 0.0
            children.forEachIndexed { i, child ->
                sumWeight += child.percentageUnexplored
                if (sumWeight >= random)
                    return i
            }
            return children.lastIndex
        }

        fun isNotInitialized() = !::children.isInitialized
    }

    /**
     * Represents a choice of a thread that should be next in the execution.
     */
    private inner class ThreadChoosingNode(nThreads: Int? = null) : InterleavingNode() {
        private val nThreads: Int?
            get() = if (isNotInitialized()) null else children.size

        init {
            if (nThreads != null)
                children = Array(nThreads) { SwitchChoosingNode() }
        }

        override fun exploreChild(): LincheckFailure? {
            val child: InterleavingNode
            val wasNotInitialized = isNotInitialized()
            if (wasNotInitialized) {
                // will be initialized during next run
                notInitializedThreadChoices.add(this)
                // suppose that we will have a child, but we don't know yet which one it will be
                child = SwitchChoosingNode()
            } else {
                val nextThread = chooseChild()
                threadSwitchChoices.add(nextThread)
                child = children[nextThread]
            }
            child.exploreChild()?.let { return it }
            updatePercentageExplored()
            if (nThreads == 0) {
                // there are no variants of threads to switch, so this node should be a leaf node
                finishExploration()
                return null
            }
            // if was not initialized before, we didn't knew which of our children will be the child
            if (wasNotInitialized)
                children[threadSwitchChoices.last()] = child
            threadSwitchChoices.removeAt(threadSwitchChoices.lastIndex)
            return null
        }

        fun initialize(switchableThreads: Int) {
            children = Array(switchableThreads) { SwitchChoosingNode() }
            if (switchableThreads == 0) return
            // add the choice of the initialized node.
            val nextThread = chooseChild()
            nextThreadToSwitch.add(nextThread)
        }
    }

    /**
     * Represents a choice of a location to switch thread context.
     */
    private inner class SwitchChoosingNode : InterleavingNode() {
        var startPosition = 0

        override fun exploreChild(): LincheckFailure? {
            if (maxNumberOfSwitches == switchPositions.size) {
                checkResults(runInvocation())?.let { return it }
                finishExploration()
                return null
            }
            if (isNotInitialized()) {
                notInitializedSwitchChoices.add(this)
                // initialize during the run
                checkResults(runInvocation())?.let { return it }
            }
            if (children.isEmpty()) {
                // no children => should be a leaf node.
                finishExploration()
                return null
            }
            val child = chooseChild()
            val position = startPosition + child
            switchPositions.add(position)
            children[child].exploreChild()?.let { return it }
            updatePercentageExplored()
            switchPositions.removeAt(switchPositions.lastIndex)
            return null
        }

        fun initialize(finishPosition: Int) {
            startPosition = lastSwitchPosition() + 1
            children = Array(finishPosition - startPosition + 1) { ThreadChoosingNode() }
        }
    }
}
