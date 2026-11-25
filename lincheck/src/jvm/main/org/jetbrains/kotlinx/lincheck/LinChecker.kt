/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
@file:Suppress("DEPRECATION")

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.LincheckFailure
import org.jetbrains.lincheck.scenario.ExecutionScenarioChecker
import org.jetbrains.lincheck.datastructures.toSettings
import org.jetbrains.lincheck.datastructures.Options
import kotlin.reflect.KClass

/**
 * This class runs concurrent tests.
 */
class LinChecker
@Deprecated(
    level = DeprecationLevel.WARNING,
    message = "Use Lincheck.runConcurrentTest with LincheckSettings instead.",
)
constructor(private val testClass: Class<*>, private val options: Options<*>?) {

    /**
     * @throws LincheckAssertionError if the testing data structure is incorrect.
     */
    fun check() {
        check(options != null) { "No Lincheck test configuration to run" }
        options.check(testClass)
    }

    // This companion object is used for backwards compatibility.
    companion object {
        /**
         * Runs the specified concurrent tests. An explicit [options] value is required.
         *
         * @throws AssertionError if any of the tests fails.
         */
        @Deprecated(
            "Use Lincheck.runConcurrentTest with LincheckSettings instead.",
            level = DeprecationLevel.WARNING
        )
        @JvmOverloads
        @JvmStatic
        fun check(testClass: Class<*>, options: Options<*>? = null) {
            @Suppress("DEPRECATION")
            LinChecker(testClass, options).check()
        }
    }
}

/**
 * This is a short-cut for the following code:
 * ```
 *  val options = ...
 *  LinChecker.check(testClass, options)
 * ```
 */
@Deprecated("Use Lincheck.runConcurrentTest with LincheckSettings instead.", level = DeprecationLevel.ERROR)
fun <O : Options<O>> O.check(testClass: Class<*>) = check(testClass)

/**
 * This is a short-cut for the following code:
 * ```
 *  val options = ...
 *  LinChecker.check(testClass.java, options)
 * ```
 */
@Deprecated("Use Lincheck.runConcurrentTest with LincheckSettings instead.", level = DeprecationLevel.ERROR)
fun <O : Options<O>> O.check(testClass: KClass<*>) = check(testClass)

/**
 * Runs the configured scenarios and returns the first failure, or null if none is found.
 */
internal fun Options<*>.detectFailure(testClass: Class<*>): LincheckFailure? =
    ExecutionScenarioChecker(testClass, toSettings().copy(forLambda = false)).detectFailure()

/**
 * Runs Lincheck to check the tested class under given configurations.
 *
 * Takes a failure callback as an argument.
 * This is required due to current limitations of our testing infrastructure.
 * Some tests need to inspect the internals of the failure object
 * (for example, the stack traces of exceptions thrown during the execution).
 * However, because Lincheck dynamically installs java-agent and then uninstalls it,
 * this process can invalidate some internal state of the failure object
 * (for example, the source code mapping information in the stack traces is typically lost).
 * To overcome this problem, we run the continuation in the context when Lincheck java-agent is still attached.
 *
 * @param testClass Tested class.
 * @param cont Continuation taking [LincheckFailure] as an argument.
 *   The continuation is run in the context when Lincheck java-agent is still attached.
 */
internal fun Options<*>.detectFailure(testClass: Class<*>, cont: (LincheckFailure?) -> Unit) {
    ExecutionScenarioChecker(testClass, toSettings().copy(forLambda = false)).detectFailure(cont)
}
