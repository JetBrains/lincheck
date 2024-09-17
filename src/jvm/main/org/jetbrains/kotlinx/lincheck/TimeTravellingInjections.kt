/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.verifier.*

object TimeTravellingInjections {
    @JvmStatic
    var firstRun = true

    @JvmStatic
    fun runWithLincheck(testInstance: Any, testMethodName: String) {
        println("Running with Lincheck")
        firstRun = false

        val testClass = testInstance.javaClass
        // TODO: get the name from the system property
        val testMethod = testClass.getMethod(testMethodName)

        val scenario = ExecutionScenario(
            emptyList(),
            listOf(
                listOf(
                    // takes no arguments, ok for prototype
                    Actor(testMethod, emptyList())
                )
            ),
            emptyList(),
            null
        )
        val lincheckOptions = ModelCheckingOptions()
            .iterations(0)
            .invocationsPerIteration(1)
            .addCustomScenario(scenario)
            .addGuarantee(forClasses(TimeTravellingInjections::class).allMethods().ignore())
            .verifier(FailingVerifier::class.java)

        @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
        val failure = lincheckOptions.checkImpl(testInstance::class.java)
        val result = failure!!.results.threadsResults[0][0]
        if (result is ExceptionResult) throw result.throwable
        // Otherwise, we just finish. For simplicity, the function always returns nothing.
    }

    fun isFirstRun(): Boolean {
        return firstRun
    }

    class FailingVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : Verifier {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?) = false
    }
}