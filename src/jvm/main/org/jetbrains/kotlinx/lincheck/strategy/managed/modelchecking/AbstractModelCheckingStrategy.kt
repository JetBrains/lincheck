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

import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.nvm.RecoverabilityModel
import org.jetbrains.kotlinx.lincheck.runner.InvocationResult
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.lang.reflect.Method
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
internal abstract class AbstractModelCheckingStrategy<
    INTERLEAVING : AbstractModelCheckingStrategy<INTERLEAVING, BUILDER>.Interleaving,
    BUILDER : AbstractModelCheckingStrategy<INTERLEAVING, BUILDER>.InterleavingBuilder<INTERLEAVING>>(
    testCfg: ModelCheckingCTestConfiguration,
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunctions: List<Method>,
    stateRepresentation: Method?,
    verifier: Verifier,
    recoverModel: RecoverabilityModel
) : ManagedStrategy(testClass, scenario, verifier, validationFunctions, stateRepresentation, testCfg, recoverModel) {
    // The number of invocations that the strategy is eligible to use to search for an incorrect execution.
    private val maxInvocations = testCfg.invocationsPerIteration

    // This random is used for choosing the next unexplored interleaving node in the tree.
    private val generationRandom = Random(0)

    // The interleaving that will be studied on the next invocation.
    protected lateinit var currentInterleaving: INTERLEAVING

    // The tactic for exploring interleaving tree
    @Suppress("LeakingThis")
    protected var interleavingExplorer = testCfg.explorationTactic.toInterleavingTreeExplorer(createRoot(), this)

    internal abstract fun createBuilder(): BUILDER
    protected open fun createRoot(): InterleavingTreeNode = ThreadChoosingNode((0 until nThreads).toList())

    override fun runImpl(): LincheckFailure? {
        var usedInvocations = 0
        while (usedInvocations < maxInvocations && interleavingExplorer.hasNextInterleaving()) {
            usedInvocations++
            // run invocation and check its results
            checkResult(interleavingExplorer.runNextInterleaving())?.let { return it }
        }
        return null
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
    internal abstract inner class InterleavingTreeNode(choices: List<Choice>? = null) {
        var fractionUnexplored = 1.0
        lateinit var choices: List<Choice>
        var isFullyExplored: Boolean = false
        val isInitialized get() = ::choices.isInitialized
        val needsExploration: Boolean

        init {
            if (choices != null) {
                this.choices = choices
                if (choices.isEmpty()) {
                    // Dead end. Set fully explored to prevent descending in this node.
                    fractionUnexplored = 0.0
                    isFullyExplored = true
                }
            }
            needsExploration = !isInitialized
        }

        fun runNextInterleaving(): InvocationResult = runNextInterleaving(createBuilder())

        abstract fun BUILDER.applyChoice(choice: Int)

        fun runNextInterleaving(interleavingBuilder: BUILDER): InvocationResult = runAndUpdate {
            if (!isInitialized) {
                interleavingBuilder.addLastNoninitializedNode(this)
                // Run the new interleaving
                currentInterleaving = interleavingBuilder.build()
                return@runAndUpdate runInvocation()
            }
            val choice = chooseUnexploredNode()
            interleavingBuilder.applyChoice(choice.value)
            return@runAndUpdate choice.node.runNextInterleaving(interleavingBuilder)
        }

        private inline fun <T> runAndUpdate(block: () -> T): T {
            interleavingExplorer.run { this@InterleavingTreeNode.onNodeEntering() }
            val result = block()
            interleavingExplorer.run { this@InterleavingTreeNode.onNodeLeaving() }
            return result
        }

        fun chooseUnexploredNode(): Choice {
            if (choices.size == 1) return choices.first()
            // Choose a weighted random child.
            val total = choices.sumByDouble { it.node.fractionUnexplored }
            val random = generationRandom.nextDouble() * total
            var sumWeight = 0.0
            choices.forEach { choice ->
                sumWeight += choice.node.fractionUnexplored
                if (sumWeight >= random && !choice.node.isFullyExplored)
                    return choice
            }
            // In case of errors because of floating point numbers choose the last unexplored choice.
            return choices.last { !it.node.isFullyExplored }
        }
    }

    /**
     * Represents a choice of a thread that should be next in the execution.
     */
    protected open inner class ThreadChoosingNode(switchableThreads: List<Int>) : InterleavingTreeNode(
        choices = switchableThreads.map { Choice(SwitchChoosingNode(), it) }
    ) {
        override fun BUILDER.applyChoice(choice: Int) {
            addThreadSwitchChoice(choice)
        }
    }

    /**
     * Represents a choice of a position of a thread context switch.
     */
    internal inner class SwitchChoosingNode : InterleavingTreeNode() {
        override fun BUILDER.applyChoice(choice: Int) {
            addSwitchPosition(choice)
        }
    }

    inner class Choice(val node: InterleavingTreeNode, val value: Int)

    /**
     * This class specifies an interleaving that is re-producible.
     */
    internal open inner class Interleaving(
        protected val switchPositions: List<Int>,
        private val threadSwitchChoices: List<Int>,
        protected var lastNotInitializedNode: InterleavingTreeNode?
    ) {
        private lateinit var interleavingFinishingRandom: Random
        private lateinit var nextThreadToSwitch: Iterator<Int>
        protected var lastNotInitializedNodeChoices: MutableList<Choice>? = null
        protected var executionPosition: Int = 0

        open fun initialize() {
            executionPosition = -1 // the first execution position will be zero
            interleavingFinishingRandom = Random(2) // random with a constant seed
            nextThreadToSwitch = threadSwitchChoices.iterator()
            currentThread = nextThreadToSwitch.next() // choose initial executing thread
            lastNotInitializedNodeChoices = null
            lastNotInitializedNode?.let {
                // Create a mutable list for the initialization of the not initialized node choices.
                lastNotInitializedNodeChoices = mutableListOf<Choice>().also { choices ->
                    it.choices = choices
                }
                lastNotInitializedNode = null
            }
        }

        fun chooseThread(iThread: Int): Int =
            if (nextThreadToSwitch.hasNext()) {
                // Use the predefined choice.
                val result = nextThreadToSwitch.next()
                check(result in switchableThreads(iThread))
                result
            } else {
                // There is no predefined choice.
                // This can happen if there were forced thread switches after the last predefined one
                // (e.g., thread end, coroutine suspension, acquiring an already acquired lock or monitor.wait).
                // We use a deterministic random here to choose the next thread.

                // end of execution position choosing initialization because of new switch
                lastNotInitializedNodeChoices = null
                switchableThreads(iThread).random(interleavingFinishingRandom)
            }

        fun isSwitchPosition() = executionPosition in switchPositions

        /**
         * Creates a new execution position that corresponds to the current switch point.
         * Unlike switch points, the execution position is just a gradually increasing counter
         * which helps to distinguish different switch points.
         */
        open fun newExecutionPosition(iThread: Int) {
            executionPosition++
            if (executionPosition > lastChosenExecutionPosition()) {
                // Add a new thread choosing node corresponding to the switch at the current execution position.
                val choices = lastNotInitializedNodeChoices ?: return
                val choice = Choice(ThreadChoosingNode(switchableThreads(iThread)), executionPosition)
                choices.add(choice)
            }
        }

        private fun lastChosenExecutionPosition() = switchPositions.lastOrNull() ?: -1
    }

    abstract inner class InterleavingBuilder<out T: Interleaving> {
        protected val switchPositions = mutableListOf<Int>()
        protected val threadSwitchChoices = mutableListOf<Int>()
        protected var lastNoninitializedNode: InterleavingTreeNode? = null

        abstract fun build(): T

        fun addSwitchPosition(switchPosition: Int) {
            switchPositions.add(switchPosition)
        }

        fun addThreadSwitchChoice(iThread: Int) {
            check(threadSwitchChoices.lastOrNull() != iThread) { "Cannot switch to the same thread!" }
            threadSwitchChoices.add(iThread)
        }

        fun addLastNoninitializedNode(lastNoninitializedNode: InterleavingTreeNode) {
            this.lastNoninitializedNode = lastNoninitializedNode
        }
    }
}
