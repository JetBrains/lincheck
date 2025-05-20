/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.*

import org.junit.*

/**
 * This test checks interleaving reporting features related to methods, such as reporting of atomic functions with
 * and their parameters and results compression of calls that are executed without a context switch in the middle.
 */
@Suppress("UNUSED_PARAMETER")
class MethodReportingTest {
    @Volatile
    var a = 0
    var b = 0

    @Operation
    fun operation(): Int {
        uselessIncrements(2)
        ignored()
        nonPrimitiveParameter(IllegalStateException())
        nonPrimitiveResult()
        ++a
        return inc()
    }

    private fun inc() = ++a

    private fun ignored() {
        b++
    }

    private fun uselessIncrements(count: Int) {
        repeat(count) {
            b++
        }
    }

    private fun nonPrimitiveParameter(throwable: Throwable) {
        b++
    }

    private fun nonPrimitiveResult(): Throwable {
        b++
        return IllegalStateException()
    }

    @Test
    fun test() {
        val options = ModelCheckingOptions()
            .addCustomScenario {
                parallel {
                    thread { actor(::operation) }
                    thread { actor(::operation) }
                }
            }
            .iterations(0)
            .addGuarantee(forClasses(this::class.java.name).methods("inc").treatAsAtomic())
            .addGuarantee(forClasses(this::class.java.name).methods("ignored").ignore())
        val failure = options.checkImpl(this::class.java)
        check(failure != null) { "the test should fail" }
        val log = StringBuilder().appendFailure(failure).toString()
        check("uselessIncrements(2) at" in log) { "increments in uselessIncrements method should be compressed" }
        check("inc(): " in log) { "treated as atomic methods should be reported" }
        check("ignored" !in log) { "ignored methods should not be present in log" }
        check("nonPrimitiveParameter(IllegalStateException#1)" in log)
        check("nonPrimitiveResult(): IllegalStateException#2" in log)
        checkTraceHasNoLincheckEvents(log)
    }
}

/**
 * This test checks that exceptions that are caught in other methods do not corrupt internal call stack.
 */
class CaughtExceptionMethodReportingTest {
    @Volatile
    private var counter = 0
    private var useless = 0

    @Operation
    fun operation(): Int {
        try {
            return badMethod()
        } catch (e: Throwable) {
            counter++
            return counter++
        }
    }

    private fun badMethod(): Int {
        useless++
        TODO()
    }

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(::operation) }
                thread { actor(::operation) }
            }
        }
        .iterations(0)
        .checkImpl(this::class.java)
        .checkLincheckOutput("method_reporting")

}