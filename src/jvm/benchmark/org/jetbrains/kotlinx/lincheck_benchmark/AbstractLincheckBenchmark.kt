/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package org.jetbrains.kotlinx.lincheck_benchmark

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.junit.Test
import kotlin.reflect.KClass

abstract class AbstractLincheckBenchmark(
    private vararg val expectedFailures: KClass<out LincheckFailure>
) {

    open fun <O: Options<O, *>> O.customize() {}

    private fun <O : Options<O, *>> O.runInternalTest() {
        val failure: LincheckFailure? = checkImpl(this@AbstractLincheckBenchmark::class.java)
        if (failure === null) {
            assert(expectedFailures.isEmpty()) {
                "This test should fail, but no error has been occurred (see the logs for details)"
            }
        } else {
            assert(expectedFailures.contains(failure::class)) {
                "This test has failed with an unexpected error: \n $failure"
            }
        }
    }

    @Test(timeout = TIMEOUT)
    fun benchmarkWithStressStrategy(): Unit = StressOptions().run {
        invocationsPerIteration(5_000)
        commonConfiguration()
        runInternalTest()
    }

    @Test(timeout = TIMEOUT)
    fun benchmarkWithModelCheckingStrategy(): Unit = ModelCheckingOptions().run {
        invocationsPerIteration(1_000)
        commonConfiguration()
        runInternalTest()
    }

    private fun <O : Options<O, *>> O.commonConfiguration(): Unit = run {
        iterations(30)
        actorsBefore(2)
        threads(3)
        actorsPerThread(2)
        actorsAfter(2)
        minimizeFailedScenario(false)
        customize()
    }
}

private const val TIMEOUT = 5 * 60 * 1000L // 5 minutes