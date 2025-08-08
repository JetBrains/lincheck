/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.trace.agent

import java.lang.reflect.Method

object TraceAgentParameters {
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
        val actualArguments = splitArgs(args)
        classUnderTraceDebugging = actualArguments.getOrNull(0) ?: error("Class name was not provided")
        methodUnderTraceDebugging = actualArguments.getOrNull(1) ?: error("Method name was not provided")
        traceDumpFilePath = actualArguments.getOrNull(2)
        if (actualArguments.size > 3) {
            restOfArgs.addAll(actualArguments.subList(3, actualArguments.size))
        }
    }

    @JvmStatic
    fun getRestOfArgs(): List<String> = restOfArgs

    @JvmStatic
    fun getClassAndMethod(): Pair<Class<*>, Method> {
        val testClass = Class.forName(classUnderTraceDebugging)
        val testMethod = testClass.methods.find { it.name == methodUnderTraceDebugging }
            ?: error("Method \"${methodUnderTraceDebugging}\" was not found in class \"${classUnderTraceDebugging}\". Check that method exists and it is public.")
        return testClass to testMethod
    }
}

/**
 * Splits a given string of arguments into a list, using a comma as a delimiter.
 * Supports escaping of commas and other characters using a backslash.
 *
 * Example:
 * Input: "org.example.Test,testMethod\,with\,commas,path"
 * Output: ["org.example.Test", "testMethod,with,commas", "path"]
 *
 * @param args the string containing arguments to be split, with commas as delimiters
 * @return a list of strings after splitting the input, with escaped characters processed correctly
 */
private fun splitArgs(args: String): List<String> {
    val splitArgs = mutableListOf<String>()
    var currentArg = ""
    var escaping = false
    args.forEach { char ->
        when {
            escaping -> {
                escaping = false
                currentArg += char
            }
            char == ',' -> {
                splitArgs.add(currentArg)
                currentArg = ""
            }
            char == '\\' && !escaping -> escaping = true
            else -> currentArg += char
        }
    }
    splitArgs.add(currentArg)
    return splitArgs
}
