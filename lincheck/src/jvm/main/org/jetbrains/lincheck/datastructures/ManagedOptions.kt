/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

@file:Suppress("DEPRECATION")

package org.jetbrains.lincheck.datastructures

import org.jetbrains.lincheck.LincheckSettings

/**
 * Options for the managed strategy.
 */
@Deprecated("Use LincheckSettings and Lincheck.runConcurrentTest instead.", level = DeprecationLevel.WARNING)
sealed class ManagedOptions<OPT : Options<OPT, CTEST>, CTEST : CTestConfiguration> : Options<OPT, CTEST>() {

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
     * When `false`, all library collections are hidden if no thread switch happened in it.
     * In concurrent collections the scheduler tries to avoid switching in it unless forced by a live or deadlock.
     *
     * When `true` (default) all standard library collections are treated as user code.
     *
     * @param analyzeStdLib true to analyze standard library methods, false to treat them as silent
     */
    internal fun analyzeStdLib(analyzeStdLib: Boolean): OPT = applyAndCast {
        this.stdLibAnalysisEnabled = analyzeStdLib
    }

    internal fun createSettings() = toSettings().strategy as LincheckSettings.TestingStrategy.ModelChecking

    internal fun managedSettings() = LincheckSettings.TestingStrategy.ModelChecking().also {
        it.analyzeStdLib = stdLibAnalysisEnabled
        it.checkObstructionFreedom = checkObstructionFreedom
        it.loopDetection.loopIterationsBeforeThreadSwitch = loopIterationsBeforeThreadSwitch
        it.loopDetection.loopBound = loopBound
        it.loopDetection.recursionBound = recursionBound
        it.loopDetection.awaitLoopsAnalysisEnabled = awaitLoopsAnalysisEnabled
        guarantees.forEach(it::addGuarantee)
    }

    companion object {
        const val DEFAULT_CHECK_OBSTRUCTION_FREEDOM =
            LincheckSettings.TestingStrategy.ModelChecking.DEFAULT_CHECK_OBSTRUCTION_FREEDOM

        const val DEFAULT_LOOP_ITERATIONS_BEFORE_THREAD_SWITCH = LincheckSettings.LoopDetection.DEFAULT_LOOP_ITERATIONS_BEFORE_THREAD_SWITCH
        const val DEFAULT_LOOP_BOUND = LincheckSettings.LoopDetection.DEFAULT_LOOP_BOUND
        const val DEFAULT_RECURSION_BOUND = LincheckSettings.LoopDetection.DEFAULT_RECURSION_BOUND

        const val DEFAULT_LIVELOCK_EVENTS_THRESHOLD = LincheckSettings.LoopDetection.DEFAULT_LIVELOCK_EVENTS_THRESHOLD

        val DEFAULT_STDLIB_ANALYSIS_ENABLED = LincheckSettings.TestingStrategy.ModelChecking.DEFAULT_STDLIB_ANALYSIS_ENABLED

        const val DEFAULT_AWAIT_LOOPS_ANALYSIS_ENABLED = LincheckSettings.LoopDetection.DEFAULT_AWAIT_LOOPS_ANALYSIS_ENABLED

        val DEFAULT_GUARANTEES = LincheckSettings.TestingStrategy.ModelChecking.DEFAULT_GUARANTEES

    }
}

@Suppress("UNCHECKED_CAST")
private inline fun <OPT : Options<OPT, CTEST>, CTEST : CTestConfiguration> ManagedOptions<OPT, CTEST>.applyAndCast(
        block: ManagedOptions<OPT, CTEST>.() -> Unit
) = this.apply {
    block()
} as OPT
