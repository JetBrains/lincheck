/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.test.representation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.test.*

import org.jetbrains.kotlinx.lincheck.test.util.runModelCheckingTestAndCheckOutput
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*
import java.lang.StringBuilder

/**
 * This test checks interleaving reporting features related to methods, such as reporting of atomic functions with
 * and their parameters and results compression of calls that are executed without a context switch in the middle.
 */
class MethodReportingTest : VerifierState() {
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

    override fun extractState(): Any = a

    @Test
    fun test() {
        val options = ModelCheckingOptions()
            .actorsPerThread(1)
            .actorsBefore(0)
            .actorsAfter(0)
            .addGuarantee(forClasses(this::class.java.name).methods("inc").treatAsAtomic())
            .addGuarantee(forClasses(this::class.java.name).methods("ignored").ignore())
        val failure = options.checkImpl(this::class.java)
        check(failure != null) { "the test should fail" }
        val log = StringBuilder().appendFailure(failure).toString()
        check("uselessIncrements(2) at" in log) { "increments in uselessIncrements method should be compressed" }
        check("inc(): " in log) { "treated as atomic methods should be reported" }
        check("ignored" !in log) { "ignored methods should not be present in log" }
        check("nonPrimitiveParameter(IllegalStateException@1)" in log)
        check("nonPrimitiveResult(): IllegalStateException@2" in log)
        checkTraceHasNoLincheckEvents(log)
    }
}

/**
 * This test checks that exceptions that are caught in other methods do not corrupt internal call stack.
 */
class CaughtExceptionMethodReportingTest : VerifierState() {
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

    override fun extractState(): Any = counter

    @Test
    fun test() = runModelCheckingTestAndCheckOutput("method_reporting.txt") {
        actorsPerThread(1)
        actorsBefore(0)
        actorsAfter(0)
    }

}