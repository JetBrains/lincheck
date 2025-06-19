/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.modelchecking.snapshot

import org.jetbrains.kotlinx.lincheck.ExceptionResult
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedOptions
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
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

    protected open fun <O : ManagedOptions<O, *>> O.customize() {}

    @Test
    open fun testModelChecking() = ModelCheckingOptions()
        .iterations(1)
        .actorsBefore(0)
        .actorsAfter(0)
        .actorsPerThread(2)
        .apply { customize() }
        .check(this::class)
}