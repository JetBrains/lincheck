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
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressStrategy

/**
 * Options for the stress strategy.
 */
open class StressOptions : Options<StressOptions>()

internal fun StressOptions.createStrategy(
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunction: Actor?,
): Strategy {
    val runner = ExecutionScenarioRunner(
        scenario = scenario,
        testClass = testClass,
        validationFunction = validationFunction,
        timeoutMs = timeoutMs,
        useClocks = UseClocks.RANDOM
    )
    return StressStrategy(runner).also {
        runner.initializeStrategy(it)
    }
}
