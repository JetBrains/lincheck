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

import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategySettings
import org.jetbrains.lincheck.util.AnalysisProfile

/**
 * Options for the managed strategy.
 */
sealed class ManagedOptions<OPT : Options<OPT>> : Options<OPT>() {

    protected var checkObstructionFreedom =
        DEFAULT_CHECK_OBSTRUCTION_FREEDOM

    protected var loopIterationsBeforeThreadSwitch =
        DEFAULT_LOOP_ITERATIONS_BEFORE_THREAD_SWITCH

    protected var loopBound =
        DEFAULT_LOOP_BOUND

    protected var recursionBound =
        DEFAULT_RECURSION_BOUND

    internal var stdLibAnalysisEnabled: Boolean =
        DEFAULT_STDLIB_ANALYSIS_ENABLED

    protected var awaitLoopsAnalysisEnabled: Boolean =
        DEFAULT_AWAIT_LOOPS_ANALYSIS_ENABLED

    protected val guarantees: MutableList<ManagedStrategyGuarantee> =
        ArrayList(DEFAULT_GUARANTEES)

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
     * Enables or disables await-loop analysis.
     *
     * Await loops are busy-wait (spin) loops whose body only reads shared memory until some
     * condition changes (for example, spinning on a `volatile` flag).
     * When enabled (default), Lincheck instruments such loops and suggests
     * a thread switch once an await path is taken, which helps to resolve active locks more efficiently.
     */
    fun enableAwaitLoopsAnalysis(enabled: Boolean = true): OPT = applyAndCast {
        this.awaitLoopsAnalysisEnabled = enabled
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

    internal fun createSettings(): ManagedStrategySettings =
        ManagedStrategySettings(
            timeoutMs = this.timeoutMs,
            loopIterationsBeforeThreadSwitch = this.loopIterationsBeforeThreadSwitch,
            loopBound = this.loopBound,
            recursionBound = this.recursionBound,
            checkObstructionFreedom = this.checkObstructionFreedom,
            analyzeStdLib = this.stdLibAnalysisEnabled,
            awaitLoopsAnalysisEnabled = this.awaitLoopsAnalysisEnabled,
            guarantees = this.guarantees.ifEmpty { null },
        )

    companion object {
        const val DEFAULT_CHECK_OBSTRUCTION_FREEDOM = false

        const val DEFAULT_LOOP_ITERATIONS_BEFORE_THREAD_SWITCH = 10
        const val DEFAULT_LOOP_BOUND = 50
        const val DEFAULT_RECURSION_BOUND = 20

        const val DEFAULT_LIVELOCK_EVENTS_THRESHOLD = 10001

        val DEFAULT_STDLIB_ANALYSIS_ENABLED = AnalysisProfile.DEFAULT.analyzeStdLib

        const val DEFAULT_AWAIT_LOOPS_ANALYSIS_ENABLED = true

        val DEFAULT_GUARANTEES = listOf<ManagedStrategyGuarantee>()

    }
}

@Suppress("UNCHECKED_CAST")
private inline fun <OPT : Options<OPT>> ManagedOptions<OPT>.applyAndCast(
        block: ManagedOptions<OPT>.() -> Unit
) = this.apply {
    block()
} as OPT
