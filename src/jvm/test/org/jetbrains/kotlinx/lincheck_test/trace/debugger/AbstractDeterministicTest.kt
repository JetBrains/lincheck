/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.trace.debugger

import org.jetbrains.kotlinx.lincheck.ExceptionResult
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.parallelResults
import org.jetbrains.lincheck.util.isInTraceDebuggerMode
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.verifier.Verifier
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Checks for absence of non-determinism and absence (or existence) of exceptions.
 */
abstract class AbstractDeterministicTest {
    open val alsoRunInLincheckMode: Boolean get() = false
    
    private fun testTraceDebugger() {
        if (!alsoRunInLincheckMode) {
            assumeTrue(isInTraceDebuggerMode)
        }
        val oldStdOut = System.out
        val oldErr = System.err
        val stdOutOutputCollector = ByteArrayOutputStream()
        val myStdOut = PrintStream(stdOutOutputCollector)
        val stdErrOutputCollector = ByteArrayOutputStream()
        val myStdErr = PrintStream(stdErrOutputCollector)
        System.setOut(myStdOut)
        System.setErr(myStdErr)
        try {
            ModelCheckingOptions()
                .actorsBefore(0)
                .actorsAfter(0)
                .iterations(30)
                .threads(2)
                .minimizeFailedScenario(false)
                .actorsPerThread(1)
                .invocationTimeout(60_000L)
                .verifier(FailingVerifier::class.java)
                .customize()
                .checkImpl(this::class.java) { lincheckFailure ->
                    val results = lincheckFailure?.results?.parallelResults?.flatten()?.takeIf { it.isNotEmpty() }
                    require(results != null) { lincheckFailure.toString() }
                    if (shouldFail()) {
                        require(results.all { it is ExceptionResult }) { lincheckFailure.toString() }
                    } else {
                        require(results.none { it is ExceptionResult }) { lincheckFailure.toString() }
                    }
                }
        } finally {
            val forbiddenString = "Non-determinism found."
            System.setOut(oldStdOut)
            System.setErr(oldErr)
            val stdOutOutput = stdOutOutputCollector.toString()
            print(stdOutOutput)
            val stdErrOutput = stdErrOutputCollector.toString()
            System.err.print(stdErrOutput)
            require(!stdOutOutput.contains(forbiddenString) && !stdErrOutput.contains(forbiddenString))
        }
    }
    
    open fun shouldFail() = false

    @Test
    fun test() = testTraceDebugger()
    
    open fun ModelCheckingOptions.customize(): ModelCheckingOptions = this
}

@Suppress("UNUSED_PARAMETER")
class FailingVerifier(sequentialSpecification: Class<*>) : Verifier {
    override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?) = false
}
