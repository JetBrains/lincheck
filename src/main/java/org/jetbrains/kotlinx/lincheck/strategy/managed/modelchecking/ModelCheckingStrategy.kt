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
package org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.lang.reflect.*
import java.util.concurrent.atomic.*
import kotlin.random.*

/**
 * ModelCheckingStrategy studies interesting code locations in the scenario
 * and then tries to add thread context switches at random code locations.
 * This process can be described as building an interleaving tree which nodes are the choices of
 * where to add a thread context switch or what thread to switch to at the chosen code location.
 * The strategy do not try the same interleaving twice.
 * The depth of the interleaving tree is increased gradually as all possible
 * interleavings of the previous depth are researched.
 */
internal class ModelCheckingStrategy(
        testCfg: ModelCheckingCTestConfiguration,
        testClass: Class<*>,
        scenario: ExecutionScenario,
        validationFunctions: List<Method>,
        stateRepresentation: Method?,
        verifier: Verifier
) : ManagedStrategyBase(testClass, scenario, verifier, validationFunctions, stateRepresentation, testCfg) {
    // an increasing id of code locations in the execution
    private val executionPosition = AtomicInteger(0)
    // ids of code locations where a thread should be switched
    private val switchPositions = mutableListOf<Int>()
    // ids of threads to which the executing thread should switch at the corresponding choices
    private val threadSwitchChoices = mutableListOf<Int>()
    // the number of invocations that the managed strategy may use to search for an incorrect execution
    private val maxInvocations = testCfg.invocationsPerIteration
    // the number of used invocations
    private var usedInvocations = 0
    // the maximum number of switches that strategy tries to use currently
    private var maxNumberOfSwitches = 1
    // the root for the interleaving tree
    private var root: InterleavingTreeNode = ThreadChoosingNode(nThreads)
    // a thread choosing node of the interleaving tree that should be initialized on the next run
    private var notInitializedThreadChoice: ThreadChoosingNode? = null
    // a switch position choosing node of the interleaving tree that should be initialized on the next
    private var notInitializedSwitchChoice: SwitchChoosingNode? = null
    // an iterator over threadSwitchChoices with information about what thread should be next
    private lateinit var nextThreadToSwitch: MutableListIterator<Int>
    private lateinit var executionFinishingRandom: Random

    override fun runImpl(): LincheckFailure? {
        while (usedInvocations < maxInvocations) {
            if (root.isFullyExplored) {
                // explored everything with the current limit on the number of switches
                // increase the maximum number of switches
                maxNumberOfSwitches++
                root.resetExploration()
                if (root.isFullyExplored) {
                    // everything is fully explored and there are no possible interleavings with more switches
                    return null
                }
            }
            root.exploreChild()?.let { return it }
        }
        return null
    }

    override fun onNewSwitch(iThread: Int, mustSwitch: Boolean) {
        // increment position if is a forced switch, not a one decided by shouldSwitch method
        if (mustSwitch)
            executionPosition.incrementAndGet()
        val position = executionPosition.get()
        // check whether a switch choice node should be initialized here
        if (lastSwitchPosition() < position) {
            // strictly after the last switch.
            // initialize node with the choice of the next switch location.
            val node = notInitializedSwitchChoice ?: return
            notInitializedSwitchChoice = null
            node.initialize(position)
        }

        // check whether a thread choice node should be initialized here
        if (notInitializedThreadChoice != null && executionPosition.get() == lastSwitchPosition()) {
            val node = notInitializedThreadChoice!!
            notInitializedThreadChoice = null
            // initialize node with the choice of the next thread
            val switchableThreads = switchableThreads(iThread)
            node.initialize(switchableThreads.size)
        }
    }

    override fun shouldSwitch(iThread: Int): Boolean {
        // the increment of the current position is made in the same place as where the check is,
        // because the position check and the position increment are dual operations
        check(iThread == currentThread)
        executionPosition.incrementAndGet()
        return executionPosition.get() in switchPositions
    }

    override fun initializeInvocation(repeatExecution: Boolean) {
        nextThreadToSwitch = threadSwitchChoices.listIterator()
        currentThread = nextThreadToSwitch.next() // the root chooses the first thread to execute
        executionPosition.set(-1) // one step before zero
        usedInvocations++
        executionFinishingRandom = Random(1) // random with any constant seed
        super.initializeInvocation(repeatExecution)
    }

    override fun chooseThread(switchableThreads: Int): Int {
        // if there is a predefined choice, than pick it, otherwise just return a random thread to avoid deadlocks
        return if (nextThreadToSwitch.hasNext())
            nextThreadToSwitch.next()
        else
            executionFinishingRandom.nextInt(switchableThreads)
    }

    private fun lastSwitchPosition() = switchPositions.lastOrNull() ?: -1

    /**
     * An abstract node with an execution choice in the interleaving tree.
     */
    private abstract inner class InterleavingTreeNode {
        private var fractionUnexplored = 1.0
        protected lateinit var choices: Array<InterleavingTreeNode>
        var isFullyExplored: Boolean = false
            protected set
        val isInitialized: Boolean get() = ::choices.isInitialized

        abstract fun exploreChild(): LincheckFailure?

        fun resetExploration() {
            isFullyExplored = when {
                isInitialized -> {
                    choices.forEach { it.resetExploration() }
                    choices.all { it.isFullyExplored }
                }
                else -> false
            }
            fractionUnexplored = if (isFullyExplored) 0.0 else 1.0
        }

        fun finishExploration() {
            isFullyExplored = true
            fractionUnexplored = 0.0
        }

        protected fun updateExplorationStatistics() {
            check(isInitialized) { "An interleaving tree node was not initialized properly. " +
                    "Probably caused by non-deterministic behaviour (WeakHashMap, Object.hashCode, etc)" }
            if (choices.isEmpty()) return
            val total = choices.fold(0.0) { acc, node ->
                acc + node.fractionUnexplored
            }
            fractionUnexplored = total / choices.size
            isFullyExplored = choices.all { it.isFullyExplored }
        }

        protected fun chooseUnexploredChild(): Int {
            if (choices.size == 1) return 0
            // choose a weighted random child.
            val total = choices.sumByDouble { it.fractionUnexplored }
            val random = generationRandom.nextDouble() * total
            var sumWeight = 0.0
            choices.forEachIndexed { i, child ->
                sumWeight += child.fractionUnexplored
                if (sumWeight >= random)
                    return i
            }
            return choices.lastIndex
        }
    }

    /**
     * Represents a choice of a thread that should be next in the execution.
     */
    private inner class ThreadChoosingNode(threadsToSwitch: Int? = null) : InterleavingTreeNode() {
        init {
            if (threadsToSwitch != null)
                choices = Array(threadsToSwitch) { SwitchChoosingNode() }
        }

        override fun exploreChild(): LincheckFailure? {
            val child: InterleavingTreeNode
            val wasNotInitialized = !isInitialized
            if (wasNotInitialized) {
                // will be initialized during next run
                check(notInitializedThreadChoice == null)
                notInitializedThreadChoice = this
                // suppose that the node will have a child, but we do not know yet which one it will be
                child = SwitchChoosingNode()
            } else {
                val nextThread = chooseUnexploredChild()
                threadSwitchChoices.add(nextThread)
                child = choices[nextThread]
            }
            child.exploreChild()?.let { return it }
            updateExplorationStatistics()
            if (choices.isEmpty()) {
                // there are no variants of threads to switch, so this node should be a leaf node
                finishExploration()
                return null
            }
            // if the node was not initialized before, we did not know which of our children will be the child
            // so write the child now
            if (wasNotInitialized)
                choices[threadSwitchChoices.last()] = child
            threadSwitchChoices.removeAt(threadSwitchChoices.lastIndex)
            return null
        }

        fun initialize(switchableThreads: Int) {
            choices = Array(switchableThreads) { SwitchChoosingNode() }
            if (switchableThreads == 0) return
            // add the choice of the initialized node.
            val nextThread = chooseUnexploredChild()
            nextThreadToSwitch.add(nextThread)
        }
    }

    /**
     * Represents a choice of a location of a thread context switch.
     */
    private inner class SwitchChoosingNode : InterleavingTreeNode() {
        // the start of a position interval for a possible thread context switch.
        // will be initialized later
        var startPosition = 0

        override fun exploreChild(): LincheckFailure? {
            val shouldBeLeaf = maxNumberOfSwitches == switchPositions.size
            if (shouldBeLeaf) {
                checkResults(runInvocation())?.let { return it }
                finishExploration()
                return null
            }
            if (!isInitialized) {
                check(notInitializedSwitchChoice == null)
                notInitializedSwitchChoice = this
                // initialize during the next run
                checkResults(runInvocation())?.let { return it }
            }
            if (choices.isEmpty()) {
                // no children => should be a leaf node.
                finishExploration()
                return null
            }
            val child = chooseUnexploredChild()
            val position = startPosition + child
            switchPositions.add(position)
            choices[child].exploreChild()?.let { return it }
            updateExplorationStatistics()
            switchPositions.removeAt(switchPositions.lastIndex)
            return null
        }

        fun initialize(finishPosition: Int) {
            startPosition = lastSwitchPosition() + 1
            choices = Array(finishPosition - startPosition + 1) { ThreadChoosingNode() }
        }
    }
}
