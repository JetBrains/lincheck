/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.strategy.stress

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import java.lang.reflect.*

internal class StressStrategy(
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunction: Actor?,
    stateRepresentationFunction: Method?,
    timeoutMs: Long,
) : Strategy(scenario) {

    override val runner : Runner = ParallelThreadsRunner(
        strategy = this,
        testClass = testClass,
        validationFunction = validationFunction,
        stateRepresentationFunction = stateRepresentationFunction,
        timeoutMs = timeoutMs,
        useClocks = UseClocks.RANDOM,
    )

    override fun runInvocation(): InvocationResult = runner.run()
}