/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.jetbrains.lincheck.datastructures.Options
import org.junit.*
import kotlin.reflect.*

abstract class AbstractLincheckTest(
    private vararg val expectedFailures: KClass<out LincheckFailure>
) {
    open fun <O: Options<O, *>> O.customize() {}

    private fun <O : Options<O, *>> O.runInternalTest() {
        val failure: LincheckFailure? = checkImpl(this@AbstractLincheckTest::class.java)
        if (failure === null) {
            assert(expectedFailures.isEmpty()) {
                "This test should fail, but no error has been occurred (see the logs for details)"
            }
        } else {
            checkFailureIsNotLincheckInternalBug(failure)
            failure.trace?.let { checkTraceHasNoLincheckEvents(it.toString()) }
            assert(expectedFailures.contains(failure::class)) {
                "This test has failed with an unexpected error: \n $failure"
            }
        }
    }

    @Test(timeout = TIMEOUT)
    fun testWithStressStrategy(): Unit = StressOptions().run {
        invocationsPerIteration(5_000)
        commonConfiguration()
        runInternalTest()
    }

    @Test(timeout = TIMEOUT)
    // NOTE: please do not rename - the name is used to filter tests on CI in the "single-CPU" configuration:
    // https://teamcity.jetbrains.com/buildConfiguration/KotlinTools_KotlinxLincheck_BuildLinuxOnJava17OnlyOneCpu
    // We use this configuration to check the Lincheck's behavior on machines with a single CPU available.
    // We want to run only model checking tests in this configuration, because stress tests when run on
    // a single CPU might not detect any bugs.
    fun testWithModelCheckingStrategy(): Unit = ModelCheckingOptions().run {
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

private const val TIMEOUT = 20 * 60_000L // 20 min