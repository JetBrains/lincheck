/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.strategy.modelchecking.snapshot

import org.jetbrains.lincheck.Lincheck.runConcurrentTest
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.verifier.Verifier
import org.jetbrains.lincheck.scenario.ExecutionResult
import org.jetbrains.lincheck.scenario.parallelResults
import org.jetbrains.lincheck.util.ExceptionResult
import org.junit.Test

abstract class AbstractSnapshotTest {
    abstract class SnapshotVerifier() : Verifier {
        protected fun checkForExceptions(results: ExecutionResult?) {
            results?.parallelResults?.forEach { threadsResults ->
                threadsResults.forEach { result ->
                    if (result is ExceptionResult) {
                        throw result.throwable
                    }
                }
            }
        }
    }

    protected open fun LincheckSettings.customize() {}

    @Test
    open fun testModelChecking() = runConcurrentTest(LincheckSettings {
        modelChecking()
        scenarioGeneration {
            randomScenarios = 1
            operationsBefore = 0
            operationsAfter = 0
            operationsPerThread = 2
        }
        customize()
    }, this::class)
}