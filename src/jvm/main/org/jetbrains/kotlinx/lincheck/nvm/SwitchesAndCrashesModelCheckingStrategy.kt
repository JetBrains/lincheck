/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.nvm

import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.managed.CrashReason
import org.jetbrains.kotlinx.lincheck.strategy.managed.TracePoint
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.AbstractModelCheckingStrategy
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTestConfiguration
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import org.objectweb.asm.ClassVisitor
import java.lang.reflect.Method
import kotlin.math.max

internal class SwitchesAndCrashesModelCheckingStrategy(
    testCfg: ModelCheckingCTestConfiguration,
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunctions: List<Method>,
    stateRepresentation: Method?,
    verifier: Verifier,
    recoverModel: RecoverabilityModel
) : AbstractModelCheckingStrategy<SwitchesAndCrashesModelCheckingStrategy.SwitchesAndCrashesInterleaving, SwitchesAndCrashesModelCheckingStrategy.SwitchesAndCrashesInterleavingBuilder>(
    testCfg, testClass, scenario,
    validationFunctions, stateRepresentation, verifier, recoverModel
) {
    @Volatile
    private var systemCrashInitiator: Int = NO_CRASH_INITIATOR

    override fun createBuilder() = SwitchesAndCrashesInterleavingBuilder()
    override fun createRoot(): InterleavingTreeNode = ThreadChoosingNodeWithCrashes((0 until nThreads).toList())

    override fun createTransformer(cv: ClassVisitor): ClassVisitor {
        val visitor = CrashEnabledVisitor(cv, testClass, recoverModel.crashes)
        val recoverTransformer = recoverModel.createTransformer(visitor, testClass)
        val managedTransformer = CrashesManagedStrategyTransformer(
            recoverTransformer, tracePointConstructors, testCfg.guarantees, testCfg.eliminateLocalObjects,
            collectStateRepresentation, collectTrace, codeLocationIdProvider, visitor
        )
        return recoverModel.createTransformerWrapper(managedTransformer, testClass)
    }

    override fun initializeInvocation() {
        systemCrashInitiator = NO_CRASH_INITIATOR
        Probability.resetRandom(this)
        Crash.barrierCallback = { forceSwitchToAwaitSystemCrash() }
        super.initializeInvocation()
    }

    override fun newCrashPoint(iThread: Int) {
        if (!isTestThread(iThread)) return // can crash only test threads
        if (inIgnoredSection(iThread)) return // cannot suspend in ignored sections
        check(iThread == currentThread)
        val isSystemCrash = waitingSystemCrash()
        val shouldCrash = shouldCrash(iThread) || isSystemCrash
        if (shouldCrash) {
            val initializeSystemCrash = !isSystemCrash && isSystemCrash(iThread)
            if (initializeSystemCrash) {
                systemCrashInitiator = iThread
            }
            crashCurrentThread(iThread, isSystemCrash, initializeSystemCrash)
        }
        // continue the operation
    }

    override fun newSwitchPoint(iThread: Int, codeLocation: Int, tracePoint: TracePoint?) {
        if (waitingSystemCrash()) return
        super.newSwitchPoint(iThread, codeLocation, tracePoint)
    }

    override fun newRandomChoice(): Boolean {
        currentInterleaving.newRandomChoicePosition()
        return currentInterleaving.randomChoice()
    }

    private fun onNewCrash(iThread: Int, mustCrash: Boolean) {
        if (mustCrash) {
            currentInterleaving.newExecutionCrashPosition(iThread)
        }
    }

    fun invocations() = usedInvocations

    private fun shouldCrash(iThread: Int): Boolean {
        check(iThread == currentThread)
        currentInterleaving.newExecutionCrashPosition(iThread)
        return currentInterleaving.isCrashPosition()
    }

    private fun isSystemCrash(iThread: Int): Boolean {
        check(iThread == currentThread)
        return currentInterleaving.isSystemCrash()
    }

    private fun waitingSystemCrash() = systemCrashInitiator != NO_CRASH_INITIATOR

    private fun forceSwitchToAwaitSystemCrash() {
        check(waitingSystemCrash())
        val iThread = currentThread
        if (iThread != systemCrashInitiator) {
            currentThread = systemCrashInitiator
            awaitTurn(iThread)
        } else {
            for (t in switchableThreads(iThread)) {
                currentThread = t
                awaitTurn(iThread)
            }
            Crash.onSystemCrash()
            systemCrashInitiator = NO_CRASH_INITIATOR
        }
    }

    private fun crashCurrentThread(iThread: Int, mustCrash: Boolean, initializeSystemCrash: Boolean) {
        val systemCrash = mustCrash || initializeSystemCrash
        val reason = if (systemCrash) CrashReason.SYSTEM_CRASH else CrashReason.CRASH
        traceCollector?.newCrash(iThread, reason)
        onNewCrash(iThread, mustCrash)
        Crash.crash(iThread + 1, null, systemCrash)
    }

    /**
     * Represents a choice of a thread that should be next in the execution.
     */
    private inner class ThreadChoosingNodeWithCrashes(switchableThreads: List<Int>, moreCrashes: Boolean = true) :
        ThreadChoosingNode(switchableThreads) {
        init {
            choices = switchableThreads.map {
                val child =
                    if (recoverModel.crashes && moreCrashes) SwitchOrCrashChoosingNode() else SwitchChoosingNode()
                Choice(child, it)
            }
        }
    }

    private inner class CrashChoosingNode(private val isSystemCrash: Boolean) : InterleavingTreeNode() {
        override fun nextInterleaving(interleavingBuilder: SwitchesAndCrashesInterleavingBuilder): SwitchesAndCrashesInterleaving {
            val isLeaf = maxNumberOfEvents == interleavingBuilder.numberOfEvents
            if (isLeaf) {
                finishExploration()
                if (!isInitialized)
                    interleavingBuilder.addLastNoninitializedNode(this)
                return interleavingBuilder.build()
            }
            val choice = chooseUnexploredNode()
            interleavingBuilder.addCrashPosition(choice.value, isSystemCrash)
            val interleaving = choice.node.nextInterleaving(interleavingBuilder)
            updateExplorationStatistics()
            return interleaving
        }
    }

    private inner class SwitchOrCrashChoosingNode : InterleavingTreeNode() {
        init {
            choices = if (recoverModel.nonSystemCrashSupported())
                listOf(
                    Choice(SwitchChoosingNode(), 0),
                    Choice(CrashChoosingNode(true), 1),
                    Choice(CrashChoosingNode(false), 2)
                )
            else
                listOf(Choice(SwitchChoosingNode(), 0), Choice(CrashChoosingNode(true), 1))
        }

        override fun nextInterleaving(interleavingBuilder: SwitchesAndCrashesInterleavingBuilder): SwitchesAndCrashesInterleaving {
            val child = chooseUnexploredNode()
            val interleaving = child.node.nextInterleaving(interleavingBuilder)
            updateExplorationStatistics()
            return interleaving
        }
    }


    private inner class RandomChoiceNode(createChild: () -> InterleavingTreeNode) : InterleavingTreeNode() {
        init {
            choices = List(2) { Choice(createChild(), it) }
        }

        override fun nextInterleaving(interleavingBuilder: SwitchesAndCrashesInterleavingBuilder): SwitchesAndCrashesInterleaving {
            val child = chooseUnexploredNode()
            check(child.value in 0..1)
            interleavingBuilder.addRandomChoice(child.value == 1)
            val interleaving = child.node.nextInterleaving(interleavingBuilder)
            updateExplorationStatistics()
            return interleaving
        }
    }

    internal inner class SwitchesAndCrashesInterleaving(
        switchPositions: List<Int>,
        private val crashPositions: List<Int>,
        threadSwitchChoices: List<Int>,
        private val nonSystemCrashes: List<Int>,
        private val randomChoices: List<Boolean>,
        lastNotInitializedNode: InterleavingTreeNode?
    ) : Interleaving(
        switchPositions,
        threadSwitchChoices,
        lastNotInitializedNode
    ) {
        private lateinit var explorationType: ExplorationNodeType
        private lateinit var nextRandomChoice: Iterator<Boolean>
        private var randomChoiceCount = 0

        override fun initialize() {
            explorationType = ExplorationNodeType.fromNode(lastNotInitializedNode)
            nextRandomChoice = randomChoices.iterator()
            randomChoiceCount = 0
            super.initialize()
        }

        fun isCrashPosition() = executionPosition in crashPositions
        fun isSystemCrash() = executionPosition !in nonSystemCrashes
        fun randomChoice() = if (nextRandomChoice.hasNext()) nextRandomChoice.next() else true

        /**
         * Creates a new execution position that corresponds to the current switch/crash point.
         * Unlike switch points, the execution position is just a gradually increasing counter
         * which helps to distinguish different switch points.
         */
        private fun newExecutionPosition(iThread: Int, type: ExplorationNodeType) {
            executionPosition++
            if (type != explorationType) return
            if (executionPosition > lastChosenExecutionPosition()) {
                // Add a new thread choosing node corresponding to the switch at the current execution position.
                val node = wrapRandomChoice(randomChoiceCount) { createChildNode(iThread) }
                lastNotInitializedNodeChoices?.add(Choice(node, executionPosition))
            }
        }

        private fun wrapRandomChoice(level: Int, createNode: () -> InterleavingTreeNode): InterleavingTreeNode {
            if (level == 0) return createNode()
            return RandomChoiceNode { wrapRandomChoice(level - 1, createNode) }
        }

        private fun lastChosenExecutionPosition() =
            max(switchPositions.lastOrNull() ?: -1, crashPositions.lastOrNull() ?: -1)

        private fun createChildNode(iThread: Int): InterleavingTreeNode {
            val moreCrashesPermitted = crashPositions.size < recoverModel.defaultExpectedCrashes()
            return when (explorationType) {
                ExplorationNodeType.SWITCH -> ThreadChoosingNodeWithCrashes(switchableThreads(iThread))
                ExplorationNodeType.CRASH -> if (moreCrashesPermitted) SwitchOrCrashChoosingNode() else SwitchChoosingNode()
                ExplorationNodeType.NONE -> error("Cannot create child for no exploration node")
            }
        }

        override fun newExecutionPosition(iThread: Int) = newExecutionPosition(iThread, ExplorationNodeType.SWITCH)
        fun newExecutionCrashPosition(iThread: Int) = newExecutionPosition(iThread, ExplorationNodeType.CRASH)
        fun newRandomChoicePosition() {
            if (nextRandomChoice.hasNext()) return
            randomChoiceCount++
        }
    }


    internal inner class SwitchesAndCrashesInterleavingBuilder : InterleavingBuilder<SwitchesAndCrashesInterleaving>() {
        private val crashPositions = mutableListOf<Int>()
        private val nonSystemCrashes = mutableListOf<Int>()
        private val randomChoices = mutableListOf<Boolean>()
        override val numberOfEvents get() = switchPositions.size + crashPositions.size

        fun addCrashPosition(crashPosition: Int, isSystemCrash: Boolean) {
            crashPositions.add(crashPosition)
            if (!isSystemCrash) nonSystemCrashes.add(crashPosition)
        }

        fun addRandomChoice(choice: Boolean) {
            randomChoices.add(choice)
        }

        override fun build() = SwitchesAndCrashesInterleaving(
            switchPositions,
            crashPositions,
            threadSwitchChoices,
            nonSystemCrashes,
            randomChoices,
            lastNoninitializedNode
        )
    }

    private enum class ExplorationNodeType {
        SWITCH, CRASH, NONE;

        companion object {
            fun fromNode(node: AbstractModelCheckingStrategy<*, *>.InterleavingTreeNode?) = when (node) {
                is SwitchChoosingNode -> SWITCH
                is CrashChoosingNode -> CRASH
                else -> NONE
            }
        }
    }
}

private const val NO_CRASH_INITIATOR = -1
