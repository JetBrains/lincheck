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

import org.jetbrains.lincheck.LincheckSettings.MemoryModel

/**
 * Options for the model checking strategy.
 */
@Deprecated("Use LincheckSettings and Lincheck.runConcurrentTest instead.", level = DeprecationLevel.WARNING)
class ModelCheckingOptions : ManagedOptions<ModelCheckingOptions>() {

    private var experimentalModelChecking = false
    internal var memoryModel: MemoryModel = MemoryModel.SequentialConsistency
        private set
    internal var printExecutionGraph = false

    internal fun useExperimentalModelChecking(): ModelCheckingOptions = enablePartialOrderReduction()

    /**
     * Enables partial order reduction, reducing the exploration space at the cost of more heavyweight analysis.
     * Skips interleavings equivalent up to reordering non-conflicting (commutative) events.
     * Enabling this option selects the event-structure algorithm; disabled by default.
     */
    @JvmOverloads
    fun enablePartialOrderReduction(enabled: Boolean = true): ModelCheckingOptions = apply {
        experimentalModelChecking = enabled
    }

    internal val isExperimentalModelCheckingEnabled: Boolean
        get() = experimentalModelChecking


    /**
     * Selects the memory model used to explore thread interleavings and shared-memory behavior.
     * Defaults to [MemoryModel.SequentialConsistency]; [MemoryModel.RelaxedJava] enables relaxed Java semantics.
     * Any non-sequentially-consistent model automatically selects the event-structure algorithm.
     */
    fun memoryModel(model: MemoryModel): ModelCheckingOptions {
        memoryModel = model
        return this
    }

    /**
     * Enables or disables appending the execution graph of the failing invocation to the failure report.
     * Disabled by default.
     */
    internal fun printExecutionGraph(print: Boolean): ModelCheckingOptions {
        check(experimentalModelChecking) { "You need to set experimentalModelChecking to use this feature" }
        printExecutionGraph = print
        return this
    }

    internal fun modelCheckingSettings() = managedSettings().also {
        if (useExperimentalModelChecking) {
            it.enablePartialOrderReduction()
        }
        it.memoryModel = memoryModel
        it.printExecutionGraph = printExecutionGraph
    }

    internal val useExperimentalModelChecking get() =
        experimentalModelChecking || System.getProperty("lincheck.useExperimentalModelChecking")?.toBoolean() ?: false
}
