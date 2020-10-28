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
) : ManagedStrategy(testClass, scenario, verifier, validationFunctions, stateRepresentation, testCfg) {
    // the number of invocations that the managed strategy may use to search for an incorrect execution
    private val maxInvocations = testCfg.invocationsPerIteration
    // the number of used invocations
    private var usedInvocations = 0
    // the maximum number of switches that strategy tries to use
    private var maxNumberOfSwitches = 0
    // the root for the interleaving tree. Is a `ThreadChoosingNode`, for which every thread can be chosen as first to execute
    private var root: InterleavingTreeNode = ThreadChoosingNode((0 until nThreads).toList())
    // random used for the generation of seeds and the execution tree
    private val generationRandom = Random(0)
    // the interleaving that will be executed on the next invocations
    private lateinit var currentInterleaving: Interleaving

    override fun runImpl(): LincheckFailure? {
        while (usedInvocations < maxInvocations) {
            // get new unexplored interleaving
            currentInterleaving = root.nextInterleaving() ?: break
            usedInvocations++
            // run invocation and check its results
            checkResult(runInvocation())?.let { return it }
        }
        return null
    }

    override fun onNewSwitch(iThread: Int, mustSwitch: Boolean) {
        if (mustSwitch) {
            // create new execution position if is a forced switch.
            // all other execution positions are covered by `shouldSwitch` method,
            // but forced switches do not ask `shouldSwitch`, because they are forced.
            // a choice of this execution position will mean that the next switch is the forced switch
            currentInterleaving.newExecutionPosition(iThread)
        }
    }

    override fun shouldSwitch(iThread: Int): Boolean {
        // crete a new current position in the same place as where the check is,
        // because the position check and the position increment are dual operations
        check(iThread == currentThread)
        currentInterleaving.newExecutionPosition(iThread)
        return currentInterleaving.isSwitchPosition()
    }

    override fun initializeInvocation() {
        currentInterleaving.initialize()
        super.initializeInvocation()
    }

    override fun chooseThread(iThread: Int): Int = currentInterleaving.chooseThread(iThread)

    /**
     * An abstract node with an execution choice in the interleaving tree.
     */
    private abstract inner class InterleavingTreeNode {
        private var fractionUnexplored = 1.0
        lateinit var choices: List<Choice>
        var isFullyExplored: Boolean = false
            protected set
        val isInitialized
            get() = ::choices.isInitialized

        fun nextInterleaving(): Interleaving? {
            if (isFullyExplored) {
                // increase the maximum number of switches that can be used,
                // because there are no more not covered interleavings
                // with the previous maximum number of switches
                maxNumberOfSwitches++
                resetExploration()
            }
            // check if everything is fully explored and there are no possible interleavings with more switches
            if (isFullyExplored) return null
            return nextInterleaving(InterleavingBuilder())
        }

        abstract fun nextInterleaving(interleavingBuilder: InterleavingBuilder): Interleaving

        protected fun resetExploration() {
            if (!isInitialized) {
                // is a leaf node
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
            // choose a weighted random child.
            val total = choices.sumByDouble { it.node.fractionUnexplored }
            val random = generationRandom.nextDouble() * total
            var sumWeight = 0.0
            choices.forEach { choice ->
                sumWeight += choice.node.fractionUnexplored
                if (sumWeight >= random)
                    return choice
            }
            // in case of errors because of floating point numbers choose the last unexplored choice
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
            val isLeaf = maxNumberOfSwitches == interleavingBuilder.numberOfSwitches()
            if (isLeaf) {
                finishExploration()
                if (!isInitialized)
                    interleavingBuilder.addLastNotInitializedNode(this)
                return interleavingBuilder.build()
            }
            val choice = chooseUnexploredNode()
            interleavingBuilder.addSwitchPosition(choice.value)
            val interleaving = choice.node.nextInterleaving(interleavingBuilder)
            updateExplorationStatistics()
            return interleaving
        }
    }

    private inner class Choice(val node: InterleavingTreeNode, val value: Int)

    /**
     * This class helps to run re-producible invocations.
     * Its parameters determine what interleaving the invocation will get.
     * To re-run the same invocation, just use the same Interleaving instance.
     */
    private inner class Interleaving(switchPositions: List<Int>, private val threadSwitchChoices: List<Int>, private var lastNotInitializedNode: SwitchChoosingNode?) {
        private val switchPositions = switchPositions.toIntArray()
        private lateinit var interleavingFinishingRandom: Random
        private lateinit var nextThreadToSwitch: Iterator<Int>
        private var lastNotInitializedNodeChoices: MutableList<Choice>? = null
        private var executionPosition: Int = 0

        fun initialize() {
            executionPosition = -1 // the first execution position will be zero
            interleavingFinishingRandom = Random(2) // random with any constant seed
            nextThreadToSwitch = threadSwitchChoices.iterator()
            currentThread = nextThreadToSwitch.next() // choose initial executing thread
            lastNotInitializedNodeChoices = null
            lastNotInitializedNode?.let {
                // create mutable list for the initialization of the not initialized node choices
                lastNotInitializedNodeChoices = mutableListOf<Choice>().also { choices ->
                    it.choices = choices
                }
                lastNotInitializedNode = null
            }
        }

        fun chooseThread(iThread: Int): Int =
            if (nextThreadToSwitch.hasNext()) {
                // use the predefined choice
                val result = nextThreadToSwitch.next()
                check(result in switchableThreads(iThread))
                result
            } else {
                // there is no predefined choice.
                // this can happen if there are forced thread switches after the last predefined one
                // (e.g., thread end, coroutine suspension, acquiring an already acquired lock or monitor.wait).
                // we use random here, because deterministic thread switch choices,
                // such as minimal available thread id, can result in a livelock.
                lastNotInitializedNodeChoices = null // end of execution position choosing initialization because of new switch
                switchableThreads(iThread).random(interleavingFinishingRandom)
            }

        fun isSwitchPosition() = executionPosition in switchPositions

        /**
         * Create a new execution position that corresponds to a switch point.
         * Unlike switch point the execution position is just a gradually increasing counter
         * that helps to distinguish different switch positions.
         */
        fun newExecutionPosition(iThread: Int) {
            executionPosition++
            if (executionPosition > switchPositions.lastOrNull() ?: -1) {
                // add new thread choosing node corresponding to a switch at the current execution position
                lastNotInitializedNodeChoices?.add(Choice(ThreadChoosingNode(switchableThreads(iThread)), executionPosition))
            }
        }
    }

    private inner class InterleavingBuilder {
        private val switchPositions = mutableListOf<Int>()
        private val threadSwitchChoices = mutableListOf<Int>()
        private var lastNotInitializedNode: SwitchChoosingNode? = null

        fun addSwitchPosition(switchPosition: Int) {
            switchPositions.add(switchPosition)
        }

        fun addThreadSwitchChoice(iThread: Int) {
            threadSwitchChoices.add(iThread)
        }

        fun addLastNotInitializedNode(lastNotInitializedNode: SwitchChoosingNode) {
            this.lastNotInitializedNode = lastNotInitializedNode
        }

        fun numberOfSwitches() = switchPositions.size
        fun build(): Interleaving = Interleaving(switchPositions, threadSwitchChoices, lastNotInitializedNode)
    }
}