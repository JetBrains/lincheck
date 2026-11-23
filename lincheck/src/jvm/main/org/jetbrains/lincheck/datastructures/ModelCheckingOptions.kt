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

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.ExecutionScenarioRunner
import org.jetbrains.kotlinx.lincheck.runner.UseClocks
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.EventStructureStrategy
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.consistency.MemoryModel
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingStrategy
import org.jetbrains.lincheck.jvm.agent.LincheckInstrumentation

/**
 * Options for the model checking strategy.
 */
class ModelCheckingOptions : ManagedOptions<ModelCheckingOptions>() {

    private var experimentalModelChecking = false
    internal var memoryModel: MemoryModel = MemoryModel.SequentialConsistency
        private set
    internal var printExecutionGraph = false

    internal fun useExperimentalModelChecking(): ModelCheckingOptions {
        experimentalModelChecking = true
        return this
    }

    internal val isExperimentalModelCheckingEnabled: Boolean
        get() = experimentalModelChecking


    internal fun memoryModel(model: MemoryModel): ModelCheckingOptions {
        check(experimentalModelChecking) { "You need to set experimentalModelChecking to use this feature" }
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

    internal val useExperimentalModelChecking get() =
        experimentalModelChecking || System.getProperty("lincheck.useExperimentalModelChecking")?.toBoolean() ?: false
}

internal fun ModelCheckingOptions.createStrategy(
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunction: Actor?,
): Strategy {
    val runner = ExecutionScenarioRunner(
        scenario = scenario,
        testClass = testClass,
        validationFunction = validationFunction,
        timeoutMs = getTimeOutMs(ideaPluginReplayModeEnabled, timeoutMs),
        useClocks = UseClocks.ALWAYS
    )
    if (useExperimentalModelChecking) {
        return EventStructureStrategy(
            runner, createSettings(), LincheckInstrumentation.context, memoryModel,
            inIdeaPluginReplayMode = ideaPluginReplayModeEnabled,
            printExecutionGraph = printExecutionGraph,
        ).also {
            runner.initializeStrategy(it)
        }
    } else {
        return ModelCheckingStrategy(runner, createSettings(), ideaPluginReplayModeEnabled, LincheckInstrumentation.context).also {
            runner.initializeStrategy(it)
        }
    }
}

internal fun getTimeOutMs(inIdeaPluginReplayMode: Boolean, defaultTimeOutMs: Long): Long =
    if (inIdeaPluginReplayMode) INFINITE_TIMEOUT else defaultTimeOutMs

/**
 * With idea plugin enabled, we should not use default Lincheck timeout
 * as debugging may take more time than default timeout.
 */
private const val INFINITE_TIMEOUT = 1000L * 60 * 60 * 24 * 365
