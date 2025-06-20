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

/**
 * Options for the managed strategy.
 */
abstract class ManagedOptions<OPT : Options<OPT, CTEST>, CTEST : CTestConfiguration> : Options<OPT, CTEST>() {

    protected var checkObstructionFreedom =
        ManagedCTestConfiguration.DEFAULT_CHECK_OBSTRUCTION_FREEDOM

    protected var hangingDetectionThreshold =
        ManagedCTestConfiguration.DEFAULT_HANGING_DETECTION_THRESHOLD

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
     * Use the specified maximum number of repetitions to detect endless loops (hangs).
     * A found loop will force managed execution to switch the executing thread or report
     * ab obstruction-freedom violation if [checkObstructionFreedom] is set.
     */
    fun hangingDetectionThreshold(hangingDetectionThreshold: Int): OPT = applyAndCast {
        this.hangingDetectionThreshold = hangingDetectionThreshold
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
    val hangingDetectionThreshold: Int,
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
            hangingDetectionThreshold = this.hangingDetectionThreshold,
            checkObstructionFreedom = this.checkObstructionFreedom,
            analyzeStdLib = this.stdLibAnalysisEnabled,
            guarantees = this.guarantees.ifEmpty { null },
        )

    companion object {
        const val DEFAULT_CHECK_OBSTRUCTION_FREEDOM = false

        const val DEFAULT_HANGING_DETECTION_THRESHOLD = 101
        const val DEFAULT_LIVELOCK_EVENTS_THRESHOLD = 10001

        const val DEFAULT_STDLIB_ANALYSIS_ENABLED = true

        val DEFAULT_GUARANTEES = listOf<ManagedStrategyGuarantee>()
    }
}