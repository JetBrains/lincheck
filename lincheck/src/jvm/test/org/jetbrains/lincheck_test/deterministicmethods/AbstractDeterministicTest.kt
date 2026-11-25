/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.deterministicmethods

import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.verifier.Verifier
import org.jetbrains.lincheck.scenario.ExecutionResult
import org.jetbrains.lincheck.scenario.ExecutionScenario
import org.jetbrains.lincheck.scenario.parallelResults
import org.jetbrains.lincheck.util.ExceptionResult
import org.jetbrains.lincheck_test.util.detectFailure
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Checks for absence of non-determinism and absence (or existence) of exceptions.
 */
abstract class AbstractDeterministicTest {
    
    private fun testTraceDebugger() {
        val oldStdOut = System.out
        val oldErr = System.err
        val stdOutOutputCollector = ByteArrayOutputStream()
        val myStdOut = PrintStream(stdOutOutputCollector)
        val stdErrOutputCollector = ByteArrayOutputStream()
        val myStdErr = PrintStream(stdErrOutputCollector)
        System.setOut(myStdOut)
        System.setErr(myStdErr)
        try {
            LincheckSettings {
                modelChecking()
                scenarioGeneration {
                    operationsBefore = 0
                    operationsAfter = 0
                    randomScenarios = 30
                    threads = 2
                    minimizeFailedScenario = false
                    operationsPerThread = 1
                }
                invocationTimeoutMs = 60_000L
                scenarioGeneration { verifier = FailingVerifier::class.java }
                customize()
            }.detectFailure(this::class.java) { lincheckFailure ->
                // The failure report is no longer printed by the framework;
                // print it here so the forbidden-string check below still sees it.
                lincheckFailure?.let(::println)
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
    
    open fun LincheckSettings.customize() {}
}

@Suppress("UNUSED_PARAMETER")
class FailingVerifier(sequentialSpecification: Class<*>) : Verifier {
    override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?) = false
}
