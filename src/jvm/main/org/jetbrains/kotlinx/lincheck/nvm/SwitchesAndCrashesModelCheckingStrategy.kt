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
    private val started = BooleanArray(nThreads) { false }

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
        Crash.barrierCallback = { forceSwitchToAwaitSystemCrash() }
        started.fill(false)
        super.initializeInvocation()
        Probability.resetRandom(currentInterleaving.chooseRandomSeed())
    }

    override fun newCrashPoint(iThread: Int) {
        if (!isTestThread(iThread)) return // can crash only test threads
        if (inIgnoredSection(iThread)) return // cannot crash in ignored sections
        check(iThread == currentThread)
        check(!waitingSystemCrash()) { "This case must be handled in await." }
        if (shouldCrash(iThread)) {
            val systemCrash = isSystemCrash(iThread)
            if (systemCrash) {
                systemCrashInitiator = iThread
            }
            crashCurrentThread(iThread, systemCrash, systemCrash)
        }
        // continue the operation
    }

    override fun newSwitchPoint(iThread: Int, codeLocation: Int, tracePoint: TracePoint?) {
        if (waitingSystemCrash()) return
        super.newSwitchPoint(iThread, codeLocation, tracePoint)
    }

    private fun onNewCrash(iThread: Int, mustCrash: Boolean) {
        if (!mustCrash) {
            Probability.resetRandom(currentInterleaving.chooseRandomSeed())
        }
    }

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

    override fun onStart(iThread: Int) {
        super.onStart(iThread)
        started[iThread] = true
    }

    override fun onFinish(iThread: Int) {
        check(!waitingSystemCrash())
        super.onFinish(iThread)
    }

    override fun awaitTurn(iThread: Int) {
        super.awaitTurn(iThread)
        if (waitingSystemCrash() && systemCrashInitiator != iThread) {
            crashCurrentThread(iThread, systemCrash = true, initializeSystemCrash = false)
        }
    }


    private fun forceSwitchToAwaitSystemCrash() {
        check(waitingSystemCrash())
        val iThread = currentThread
        if (iThread != systemCrashInitiator) {
            currentThread = systemCrashInitiator
            awaitTurn(iThread)
        } else {
            for (t in switchableActiveThreads(iThread)) {
                currentThread = t
                awaitTurn(iThread)
            }
            Crash.onSystemCrash()
            systemCrashInitiator = NO_CRASH_INITIATOR
        }
    }

    private fun switchableActiveThreads(iThread: Int) = switchableThreads(iThread).filter { started[it] }

    private fun crashCurrentThread(iThread: Int, systemCrash: Boolean, initializeSystemCrash: Boolean) {
        val reason = if (systemCrash) CrashReason.SYSTEM_CRASH else CrashReason.CRASH
        traceCollector?.newCrash(iThread, reason)
        onNewCrash(iThread, systemCrash && !initializeSystemCrash)
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
            choices = mutableListOf<Choice>().apply {
                add(Choice(SwitchChoosingNode(), 0))
                add(Choice(createCrashChoosingNode(true), 1))
                if (recoverModel.nonSystemCrashSupported()) {
                    add(Choice(createCrashChoosingNode(false), 2))
                }
            }.toList()
        }

        override fun nextInterleaving(interleavingBuilder: SwitchesAndCrashesInterleavingBuilder): SwitchesAndCrashesInterleaving {
            val child = chooseUnexploredNode()
            val interleaving = child.node.nextInterleaving(interleavingBuilder)
            updateExplorationStatistics()
            return interleaving
        }
    }

    private fun createCrashChoosingNode(isSystemCrash: Boolean): InterleavingTreeNode = FlushRandomChoosingNode { CrashChoosingNode(isSystemCrash) }

    private inner class FlushRandomChoosingNode(createChild: () -> InterleavingTreeNode) : InterleavingTreeNode() {
        init {
            choices = List(RANDOM_SEEDS_BRANCHING) { Choice(createChild(), Probability.generateSeed()) }
        }

        override fun nextInterleaving(interleavingBuilder: SwitchesAndCrashesInterleavingBuilder): SwitchesAndCrashesInterleaving {
            val child = chooseUnexploredNode()
            interleavingBuilder.addRandomSeed(child.value)
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
        private val randomSeeds: List<Int>,
        lastNotInitializedNode: InterleavingTreeNode?
    ) : Interleaving(
        switchPositions,
        threadSwitchChoices,
        lastNotInitializedNode
    ) {
        private lateinit var explorationType: ExplorationNodeType
        private lateinit var nextRandomSeed: Iterator<Int>

        override fun initialize() {
            explorationType = ExplorationNodeType.fromNode(lastNotInitializedNode)
            nextRandomSeed = randomSeeds.iterator()
            super.initialize()
        }

        fun isCrashPosition() = executionPosition in crashPositions
        fun isSystemCrash() = executionPosition !in nonSystemCrashes
        fun chooseRandomSeed() = if (nextRandomSeed.hasNext()) {
            check(explorationType == ExplorationNodeType.CRASH || explorationType == ExplorationNodeType.NONE || crashPositions.any { it > executionPosition })
            nextRandomSeed.next()
        } else {
            check(explorationType != ExplorationNodeType.CRASH && crashPositions.all { it <= executionPosition })
            0
        }

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
                lastNotInitializedNodeChoices?.add(Choice(createChildNode(iThread), executionPosition))
            }
        }

        private fun lastChosenExecutionPosition() =
            max(switchPositions.lastOrNull() ?: -1, crashPositions.lastOrNull() ?: -1)

        private fun createChildNode(iThread: Int): InterleavingTreeNode {
            val crashes = crashPositions.size + (explorationType == ExplorationNodeType.CRASH).toInt()
            val moreCrashesPermitted = crashes < recoverModel.defaultExpectedCrashes()
            return when (explorationType) {
                ExplorationNodeType.SWITCH -> ThreadChoosingNodeWithCrashes(switchableThreads(iThread), moreCrashesPermitted)
                ExplorationNodeType.CRASH -> if (moreCrashesPermitted) SwitchOrCrashChoosingNode() else SwitchChoosingNode()
                ExplorationNodeType.NONE -> error("Cannot create child for no exploration node")
            }
        }

        override fun newExecutionPosition(iThread: Int) = newExecutionPosition(iThread, ExplorationNodeType.SWITCH)
        fun newExecutionCrashPosition(iThread: Int) = newExecutionPosition(iThread, ExplorationNodeType.CRASH)
    }


    internal inner class SwitchesAndCrashesInterleavingBuilder : InterleavingBuilder<SwitchesAndCrashesInterleaving>() {
        private val crashPositions = mutableListOf<Int>()
        private val nonSystemCrashes = mutableListOf<Int>()
        private val randomSeeds = mutableListOf<Int>()
        override val numberOfEvents get() = switchPositions.size + crashPositions.size

        fun addCrashPosition(crashPosition: Int, isSystemCrash: Boolean) {
            crashPositions.add(crashPosition)
            if (!isSystemCrash) nonSystemCrashes.add(crashPosition)
        }

        fun addRandomSeed(seed: Int) {
            randomSeeds.add(seed)
        }

        override fun build() = SwitchesAndCrashesInterleaving(
            switchPositions,
            crashPositions,
            threadSwitchChoices,
            nonSystemCrashes,
            randomSeeds,
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

private const val RANDOM_SEEDS_BRANCHING = 4

private fun Boolean.toInt() = if (this) 1 else 0
