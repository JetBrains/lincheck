/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.trace.debugger

import org.jetbrains.kotlinx.lincheck.ExceptionResult
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.threadsResults
import org.jetbrains.kotlinx.lincheck.traceagent.TraceAgentParameters
import org.jetbrains.lincheck.datastructures.scenario
import org.jetbrains.lincheck.datastructures.forClasses
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent
import org.jetbrains.kotlinx.lincheck.util.LoggingLevel
import org.jetbrains.lincheck.datastructures.verifier.Verifier
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.jvm.kotlinFunction

object TraceDebuggerInjections {
    @JvmStatic
    var firstRun = true

    @JvmStatic
    fun runWithLincheck() {
        firstRun = false

        val (testClass, testMethod) = TraceAgentParameters.getClassAndMethod()

        val isStaticMethod = Modifier.isStatic(testMethod.modifiers)
        val instanceClass = if (isStaticMethod) TraceDebuggerStaticMethodWrapper::class.java else testClass

        val scenario = scenario {
            parallel {
                thread {
                    // TODO: current implementation does not support `testMethod`
                    //  that accepts arguments (e.g. java main function)
                    if (isStaticMethod) {
                        actor(
                            TraceDebuggerStaticMethodWrapper::callStaticMethod,
                            testClass, testMethod
                        )
                    } else {
                        actor(testMethod.kotlinFunction!!)
                    }
                }
            }
        }

        val lincheckOptions = ModelCheckingOptions()
            .iterations(0)
            .invocationsPerIteration(1)
            .addCustomScenario(scenario)
            .addGuarantee(forClasses(TraceDebuggerInjections::class).allMethods().ignore())
            .verifier(FailingVerifier::class.java)
            .logLevel(LoggingLevel.OFF)
            .invocationTimeout(5 * 60 * 1000) // 5 mins

        val failure = lincheckOptions.checkImpl(instanceClass)

        val result = failure!!.results.threadsResults[0][0]
        if (result is ExceptionResult) throw result.throwable

        if (!TraceAgentParameters.traceDumpFilePath.isNullOrEmpty() && failure.trace != null) {
            val dumpFile = File(TraceAgentParameters.traceDumpFilePath!!)
            dumpFile.parentFile?.mkdirs()
            dumpFile.createNewFile()
            dumpFile.writeText(failure.toString())
        }
    }

    @JvmStatic
    fun isFirstRun(): Boolean {
        return firstRun
    }

    internal class FailingVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : Verifier {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?) = false
    }


    class TraceDebuggerStaticMethodWrapper {
        fun callStaticMethod(clazz: Class<*>, method: Method) {
            LincheckJavaAgent.ensureClassHierarchyIsTransformed(clazz.name)
            method.invoke(null)
        }
    }
}
