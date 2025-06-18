/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.traceagent

import java.lang.reflect.Method

private const val TRACE_DEBUGGER_MODE_PROPERTY = "lincheck.traceDebuggerMode"
private const val TRACE_RECORDER_MODE_PROPERTY = "lincheck.traceRecorderMode"
val isInTraceDebuggerMode by lazy { System.getProperty(TRACE_DEBUGGER_MODE_PROPERTY, "false").toBoolean() }
val isInTraceRecorderMode by lazy { System.getProperty(TRACE_RECORDER_MODE_PROPERTY, "false").toBoolean() }

internal object TraceAgentParameters {
    @JvmStatic
    lateinit var classUnderTraceDebugging: String

    @JvmStatic
    lateinit var methodUnderTraceDebugging: String

    @JvmStatic
    var traceDumpFilePath: String? = null

    @JvmStatic
    private var restOfArgs: MutableList<String> = mutableListOf()

    @JvmStatic
    fun parseArgs(args: String?) {
        if (args == null) {
            error("Please provide class and method names as arguments")
        }

        val actualArguments = args.split(",")
        classUnderTraceDebugging = actualArguments.getOrNull(0) ?: error("Class name was not provided")
        methodUnderTraceDebugging = actualArguments.getOrNull(1) ?: error("Method name was not provided")
        traceDumpFilePath = actualArguments.getOrNull(2)
        if (actualArguments.size > 3) {
            restOfArgs.addAll(actualArguments.subList(3, actualArguments.size))
        }
    }

    @JvmStatic
    fun getResatOfArgs(): List<String> = restOfArgs

    @JvmStatic
    fun getClassAndMethod(): Pair<Class<*>, Method> {
        val testClass = Class.forName(classUnderTraceDebugging)
        val testMethod = testClass.methods.find { it.name == methodUnderTraceDebugging }
            ?: error("Method \"${methodUnderTraceDebugging}\" was not found in class \"${classUnderTraceDebugging}\". Check that method exists and it is public.")
        return testClass to testMethod
    }
}
