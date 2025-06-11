/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.threadsResults
import org.jetbrains.kotlinx.lincheck.strategy.managed.forClasses
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.ensureClassHierarchyIsTransformed
import org.jetbrains.kotlinx.lincheck.util.LoggingLevel
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.reflect.jvm.kotlinFunction

private const val TRACE_DEBUGGER_MODE_PROPERTY = "lincheck.traceDebuggerMode"
private const val DUMP_TRANSFORMED_SOURCES_PROPERTY = "lincheck.dumpTransformedSources"
val isInTraceDebuggerMode by lazy { System.getProperty(TRACE_DEBUGGER_MODE_PROPERTY, "false").toBoolean() }
val dumpTransformedSources by lazy { System.getProperty(DUMP_TRANSFORMED_SOURCES_PROPERTY, "false").toBoolean() }

internal object TraceDebuggerInjections {
    @JvmStatic
    lateinit var classUnderTraceDebugging: String

    @JvmStatic
    lateinit var methodUnderTraceDebugging: String

    @JvmStatic
    var traceDumpFilePath: String? = null

    @JvmStatic
    fun parseArgs(args: String?) {
        if (args == null) {
            error("Please provide class and method names as arguments")
        }

        val actualArguments = args.split(",")
        classUnderTraceDebugging = actualArguments.getOrNull(0) ?: error("Class name was not provided")
        methodUnderTraceDebugging = actualArguments.getOrNull(1) ?: error("Method name was not provided")
        traceDumpFilePath = actualArguments.getOrNull(2)
    }

    @JvmStatic
    var firstRun = true

    @JvmStatic
    fun runWithLincheck() {
        firstRun = false

        val testClass = Class.forName(classUnderTraceDebugging)
        val testMethod = testClass.methods.find { it.name == methodUnderTraceDebugging }
            ?: error("Method \"$methodUnderTraceDebugging\" was not found in class \"$classUnderTraceDebugging\". Check that method exists and it is public.")

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

        if (!traceDumpFilePath.isNullOrEmpty() && failure.trace != null) {
            val dumpFile = File(traceDumpFilePath!!)
            dumpFile.parentFile.mkdirs()
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
}

internal class TraceDebuggerStaticMethodWrapper {
    fun callStaticMethod(clazz: Class<*>, method: Method) {
        ensureClassHierarchyIsTransformed(clazz.name)
        method.invoke(null)
    }
}