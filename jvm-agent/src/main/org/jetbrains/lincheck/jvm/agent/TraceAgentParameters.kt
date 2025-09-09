/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.jetbrains.annotations.TestOnly
import org.jetbrains.lincheck.util.Logger
import java.lang.reflect.Method

object TraceAgentParameters {
    const val ARGUMENT_CLASS = "class"
    const val ARGUMENT_METHOD = "method"
    const val ARGUMENT_OUTPUT = "output"

    @JvmStatic
    lateinit var classUnderTraceDebugging: String

    @JvmStatic
    lateinit var methodUnderTraceDebugging: String

    @JvmStatic
    var traceDumpFilePath: String? = null

    @JvmStatic
    private val namedArgs: MutableMap<String, String?> = mutableMapOf()

    @JvmStatic
    fun parseArgs(args: String?, validAdditionalArgs: List<String>) {
        if (args == null) {
            error("Please provide class and method names as arguments")
        }
        // Try to parse new-style
        val kvArguments = parseKVArgs(args)
        if (kvArguments == null) {
            Logger.warn { "Looks like old-style arguments found, consider migrate to key-value arguments" }
            val actualArguments = splitArgs(args)

            classUnderTraceDebugging = actualArguments.getOrNull(0) ?: error("Class name was not provided")
            namedArgs[ARGUMENT_CLASS] = classUnderTraceDebugging
            methodUnderTraceDebugging = actualArguments.getOrNull(1) ?: error("Method name was not provided")
            namedArgs[ARGUMENT_METHOD] = methodUnderTraceDebugging
            traceDumpFilePath = actualArguments.getOrNull(2)
            namedArgs[ARGUMENT_OUTPUT] = traceDumpFilePath

            for (idx in 3 ..< actualArguments.size) {
                if (idx - 3 == validAdditionalArgs.size) {
                    // Allows unused arguments for backward compatibility
                    break
                }
                namedArgs[validAdditionalArgs[idx - 3]] = actualArguments[idx]
            }
        } else {
            classUnderTraceDebugging = kvArguments[ARGUMENT_CLASS] ?: error("Class name argument \"$ARGUMENT_CLASS\" was not provided")
            methodUnderTraceDebugging = kvArguments[ARGUMENT_METHOD] ?: error("Method name argument \"$ARGUMENT_METHOD\" was not provided")
            traceDumpFilePath = kvArguments[ARGUMENT_OUTPUT]

            val allowedKeys = mutableSetOf(ARGUMENT_CLASS, ARGUMENT_METHOD, ARGUMENT_OUTPUT)
            allowedKeys.addAll(validAdditionalArgs)

            val unsupportedKeys = kvArguments.keys - allowedKeys
            unsupportedKeys.forEach {
                Logger.warn { "Unknown agent argument \"$it\"" }
            }

            namedArgs.putAll(kvArguments)
        }
    }

    @JvmStatic
    fun getArg(name: String) = namedArgs[name]

    @JvmStatic
    fun getClassAndMethod(): Pair<Class<*>, Method> {
        val testClass = Class.forName(classUnderTraceDebugging)
        val testMethod = testClass.methods.find { it.name == methodUnderTraceDebugging }
            ?: error("Method \"${methodUnderTraceDebugging}\" was not found in class \"${classUnderTraceDebugging}\". Check that method exists and it is public.")
        return testClass to testMethod
    }

    @TestOnly
    fun reset() {
        classUnderTraceDebugging = ""
        methodUnderTraceDebugging = ""
        traceDumpFilePath = null
        namedArgs.clear()
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

/**
 * Parse a given string of arguments into a map, using a comma as a delimiter
 * between args and equal between name of arg and its value.
 *
 * Names of args (keys) must be simple words (as Java identifiers) and doesn't
 * support any escaping or quotation.
 *
 * Values of args can contain escapes with backslash OR can be quoted with double-quotes.
 * Inside quoted values only double-quote can be escaped with backslash, all other characters
 * goes as-is, it allows writing Windows paths more easily without doubling all backslash
 * symbols.
 *
 * Example:
 * Input: "class=some.Class,method=operation"
 * Output: {"class" -> "some.Class", "method" -> "operation"}
 *
 * Input: "class=some.Class,method=operation\ with\ spaces\,\ equals\ (\=)\,\ backslashes\ (\\)\,\ and\ commas"
 * Output: {"class" -> "some.Class", "method" -> "operation with spaces, equals (=), backslashes (\), and commas"}
 *
 * Input: "class=some.Class,method=methodNameWith\"Quote"
 * Output: {"class" -> "some.Class", "method" -> "methodNameWith"Quote"}
 *
 * Input: "class=some.Class,method=test,output="C:\Users\lev s\LocalData\Temp\==xx,yy==.bin""
 * Output: {"class" -> "some.Class", "method" -> "test", "output" -> "C:\Users\lev s\LocalData\Temp\==xx,yy==.bin"}
 *
 * Input: "class=some.Class,method=test,output="/tmp/some \"quoted\" name.tmp""
 * Output: {"class" -> "some.Class", "method" -> "test", "output" -> "/tmp/some "quoted" name.tmp"}
 *
 * Input: "5name=xxx"
 * Output: null (error: name is not valid identifier)
 *
 * Input: "name=xxx,yyy"
 * Output: null (error: no value for key "yyy")
 *
 * Input: "name=xxx,yyy zzz"
 * Output: null (error: no value for key "yyy")
 *
 * Input: "na\ me=xxx"
 * Output: null (error: escapes are not enabled in key names)

 * @param args the string containing arguments to be split, with commas as delimiters
 * @return a list of strings after splitting the input, with escaped characters processed correctly
 */
internal fun parseKVArgs(args: String): Map<String, String>? {
    var idx = 0

    fun error(m: String): Map<String, String>? {
        Logger.error { "Agent arguments error at position $idx: $m" }
        return null
    }

    val result = mutableMapOf<String, String>()

    val key = StringBuilder()
    val value = StringBuilder()
    var state = KVParserState.KEYSTART

    while (idx < args.length) {
        val ch = args[idx++]
        when (state) {
            KVParserState.KEYSTART -> {
                if (key.isNotEmpty()) {
                    result[key.toString()] = value.toString()
                }
                key.clear()
                value.clear()
                if (!ch.isJavaIdentifierStart()) {
                    return error("Invalid first key character '$ch'")
                }
                key.append(ch)
                state = KVParserState.KEY
            }
            KVParserState.KEY -> {
                if (ch == '=') {
                    state = KVParserState.VALUESTART
                } else if (!ch.isJavaIdentifierPart()) {
                    return error("Invalid key character '$ch'")
                } else {
                    key.append(ch)
                }
            }
            KVParserState.VALUESTART -> {
                when (ch) {
                    '"' -> state = KVParserState.QUOTEDVALUE
                    ',' -> state = KVParserState.KEYSTART // Empty value is Ok
                    '\\' -> state = KVParserState.ESCAPE
                    else -> {
                        value.append(ch)
                        state = KVParserState.VALUE
                    }
                }
            }
            KVParserState.VALUE -> {
                when (ch) {
                    ',' -> state = KVParserState.KEYSTART // Empty value is Ok
                    '\\' -> state = KVParserState.ESCAPE
                    else -> value.append(ch)
                }
            }
            KVParserState.QUOTEDVALUE -> {
                when (ch) {
                    '"' -> state = KVParserState.NEEDCOMMA
                    '\\' -> state = KVParserState.QUOTEESCAPE
                    else -> value.append(ch)
                }
            }
            KVParserState.ESCAPE -> {
                value.append(ch)
                state = KVParserState.VALUE
            }
            KVParserState.QUOTEESCAPE -> {
                if (ch != '"') {
                    // Return escape character back! We need to escape only double-quote
                    value.append('\\')
                }
                value.append(ch)
                state = KVParserState.QUOTEDVALUE
            }
            KVParserState.NEEDCOMMA -> {
                if (ch != ',') {
                    return error("Need ','")
                }
                state = KVParserState.KEYSTART
            }
        }
    }

    when (state) {
        KVParserState.KEY -> return error("Cannot find value for last key")
        KVParserState.QUOTEESCAPE,
        KVParserState.QUOTEDVALUE -> return error("Last value is unfinished, expect '\"'")
        KVParserState.ESCAPE -> return error("Unfinished escape in last value")
        else -> {} // All other states are ACCEPT ones
    }

    if (key.isNotEmpty()) {
        result[key.toString()] = value.toString()
    }
    return result
}

enum class KVParserState {
    KEYSTART, KEY, VALUESTART, VALUE, QUOTEDVALUE, ESCAPE, QUOTEESCAPE, NEEDCOMMA
}
