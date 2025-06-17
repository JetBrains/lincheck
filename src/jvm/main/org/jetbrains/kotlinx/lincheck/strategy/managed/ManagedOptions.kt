/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration.Companion.DEFAULT_CHECK_OBSTRUCTION_FREEDOM
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration.Companion.DEFAULT_GUARANTEES
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration.Companion.DEFAULT_HANGING_DETECTION_THRESHOLD
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration.Companion.DEFAULT_INVOCATIONS
import java.util.*

/**
 * Common options for all managed strategies.
 */
abstract class ManagedOptions<OPT : Options<OPT, CTEST>, CTEST : CTestConfiguration> : Options<OPT, CTEST>() {
    protected var invocationsPerIteration = DEFAULT_INVOCATIONS
    protected var checkObstructionFreedom = DEFAULT_CHECK_OBSTRUCTION_FREEDOM
    protected var hangingDetectionThreshold = DEFAULT_HANGING_DETECTION_THRESHOLD
    protected val guarantees: MutableList<ManagedStrategyGuarantee> = ArrayList(DEFAULT_GUARANTEES)
    internal var stdLibAnalysisEnabled: Boolean = false

    /**
     * Use the specified number of scenario invocations to study interleavings in each iteration.
     * Lincheck can use less invocations if it requires less ones to study all possible interleavings.
     */
    fun invocationsPerIteration(invocations: Int): OPT = applyAndCast {
        invocationsPerIteration = invocations
    }

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