/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
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