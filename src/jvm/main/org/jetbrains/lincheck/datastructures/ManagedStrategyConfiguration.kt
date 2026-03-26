/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.datastructures

import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategySettings
import org.jetbrains.lincheck.datastructures.verifier.Verifier
import org.jetbrains.lincheck.util.AnalysisProfile

/**
 * Options for the managed strategy.
 */
abstract class ManagedOptions<OPT : Options<OPT, CTEST>, CTEST : CTestConfiguration> : Options<OPT, CTEST>() {

    protected var checkObstructionFreedom =
        ManagedCTestConfiguration.DEFAULT_CHECK_OBSTRUCTION_FREEDOM

    protected var loopIterationsBeforeThreadSwitch =
        ManagedCTestConfiguration.DEFAULT_LOOP_ITERATIONS_BEFORE_THREAD_SWITCH

    protected var loopBound =
        ManagedCTestConfiguration.DEFAULT_LOOP_BOUND

    protected var recursionBound =
        ManagedCTestConfiguration.DEFAULT_RECURSION_BOUND

    internal var stdLibAnalysisEnabled: Boolean =
        ManagedCTestConfiguration.DEFAULT_STDLIB_ANALYSIS_ENABLED

    protected val guarantees: MutableList<ManagedStrategyGuarantee> =
        ArrayList(ManagedCTestConfiguration.DEFAULT_GUARANTEES)

    /**
     * Set to `true` to check the testing algorithm for obstruction-freedom.
     * It also extremely useful for lock-free and wait-free algorithms.
     */
    fun checkObstructionFreedom(checkObstructionFreedom: Boolean = true): OPT = applyAndCast {
        this.checkObstructionFreedom = checkObstructionFreedom
    }

    /**
     * The number of loop iterations a thread can take before switching to another thread.
     */
    fun loopIterationsBeforeThreadSwitch(loopIterationsBeforeThreadSwitch: Int): OPT = applyAndCast {
        this.loopIterationsBeforeThreadSwitch = loopIterationsBeforeThreadSwitch
    }

    /**
     * The maximum number of iterations a loop can perform before it is considered stuck.
     */
    fun loopBound(loopBound: Int): OPT = applyAndCast {
        this.loopBound = loopBound
    }

    /**
     * The maximum number of recursive calls a method can make before it is considered stuck.
     */
    fun recursionBound(recursionBound: Int): OPT = applyAndCast {
        this.recursionBound = recursionBound
    }

    /**
     * Add a guarantee that methods in some classes are either correct in terms of concurrent execution or irrelevant.
     * These guarantees can be used for optimization. For example, we can add a guarantee that all the methods
     * in `java.util.concurrent.ConcurrentHashMap` are correct and this way the strategy will not try to switch threads
     * inside these methods. We can also mark methods irrelevant (e.g., in logging classes) so that they will be
     * completely ignored (so that they will neither be treated as atomic nor interrupted in the middle) while
     * studying possible interleavings.
     */
    fun addGuarantee(guarantee: ManagedStrategyGuarantee): OPT = applyAndCast {
        guarantees.add(guarantee)
    }


    /**
     * Controls whether the strategy should analyze standard library collections.
     * When `false` (default), all library collections are hidden if no thread switch happened in it.
     * In concurrent collections the scheduler tries to avoid switching in it unless forced by a live or deadlock.
     *
     * When `true` all standard library collections are treated as user code.
     *
     * @param analyzeStdLib true to analyze standard library methods, false to treat them as silent
     */
    internal fun analyzeStdLib(analyzeStdLib: Boolean): OPT = applyAndCast {
        this.stdLibAnalysisEnabled = analyzeStdLib
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private inline fun <OPT : Options<OPT, CTEST>, CTEST : CTestConfiguration> ManagedOptions<OPT, CTEST>.applyAndCast(
                block: ManagedOptions<OPT, CTEST>.() -> Unit
        ) = this.apply {
            block()
        } as OPT
    }
}

/**
 * Configuration for the managed strategy.
 */
abstract class ManagedCTestConfiguration(
    testClass: Class<*>,
    iterations: Int,
    threads: Int,
    actorsPerThread: Int,
    actorsBefore: Int,
    actorsAfter: Int,
    generatorClass: Class<out ExecutionGenerator>,
    verifierClass: Class<out Verifier>,
    val checkObstructionFreedom: Boolean,
    val loopIterationsBeforeThreadSwitch: Int,
    val loopBound: Int,
    val recursionBound: Int,
    invocationsPerIteration: Int,
    val guarantees: List<ManagedStrategyGuarantee>,
    minimizeFailedScenario: Boolean,
    sequentialSpecification: Class<*>,
    timeoutMs: Long,
    customScenarios: List<ExecutionScenario>,
    internal val stdLibAnalysisEnabled: Boolean,
) : CTestConfiguration(
    testClass = testClass,
    iterations = iterations,
    invocationsPerIteration = invocationsPerIteration,
    threads = threads,
    actorsPerThread = actorsPerThread,
    actorsBefore = actorsBefore,
    actorsAfter = actorsAfter,
    generatorClass = generatorClass,
    verifierClass = verifierClass,
    minimizeFailedScenario = minimizeFailedScenario,
    sequentialSpecification = sequentialSpecification,
    timeoutMs = timeoutMs,
    customScenarios = customScenarios
) {

    internal fun createSettings(): ManagedStrategySettings =
        ManagedStrategySettings(
            timeoutMs = this.timeoutMs,
            loopIterationsBeforeThreadSwitch = this.loopIterationsBeforeThreadSwitch,
            loopBound = this.loopBound,
            recursionBound = this.recursionBound,
            checkObstructionFreedom = this.checkObstructionFreedom,
            analyzeStdLib = this.stdLibAnalysisEnabled,
            guarantees = this.guarantees.ifEmpty { null },
        )

    // The flag to enable IntelliJ IDEA plugin mode
    internal var inIdeaPluginReplayMode: Boolean = false
        private set

    internal fun enableReplayModeForIdeaPlugin() {
        inIdeaPluginReplayMode = true
    }

    companion object {
        const val DEFAULT_CHECK_OBSTRUCTION_FREEDOM = false

        const val DEFAULT_LOOP_ITERATIONS_BEFORE_THREAD_SWITCH = 10
        const val DEFAULT_LOOP_BOUND = 200
        const val DEFAULT_RECURSION_BOUND = 50

        const val DEFAULT_LIVELOCK_EVENTS_THRESHOLD = 10001

        val DEFAULT_STDLIB_ANALYSIS_ENABLED = AnalysisProfile.DEFAULT.analyzeStdLib

        val DEFAULT_GUARANTEES = listOf<ManagedStrategyGuarantee>()
    }
}