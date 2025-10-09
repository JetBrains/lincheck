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
import java.lang.reflect.Modifier

/**
 * Parses and stores arguments passed to Lincheck JVM javaagents (trace-recorder and trace-debugger).
 *
 * Arguments are provided as a comma-separated string of key=value pairs.
 * Keys must be valid Java identifiers (letters/digits/underscore, not starting with a digit).
 * Values may use quotation or escape characters, see below.
 *
 * Example:
 * - java -javaagent:path/to/agent.jar=class=org.example.MyTest,method=run,output="/tmp/trace.bin" -jar yourApp.jar
 *
 * Supported arguments:
 * - class — fully qualified class name to run/transform (required).
 *       Example: `class=org.example.MyTest`
 *
 * - method (required) — name of the public method in that class (required).
 *       Example: `method=run`
 *
 * - output — path to a file for trace dump, if supported by the agent (optional).
 *       Example: `output="/tmp/trace.bin"`
 *
 * - include — semicolon-separated list of include patterns (optional).
 *       Example: `include="org.example.*;com.acme.util.*"`
 *
 * - exclude — semicolon-separated list of exclude patterns (optional).
 *       Example: `exclude="org.example.internal.*;**.generated.*"`
 *
 * - lazy — boolean that can disable lazy transformation, it is true by default.
 *      Example: `lazy=false`
 *      
 * - pack — boolean that enables zipping trace artifact files, it is false by default.
 *      Example: `pack=true`
 *
 * Quotation rules:
 * - Unquoted values may contain any character; use backslash to escape comma (,) and backslash (\\).
 * - Values can be enclosed in double quotes ("...") to avoid escaping.
 *   Inside quotes only the double quote character must be escaped with a backslash (\").
 *   Backslashes and all other characters go as-is, which is handy for Windows paths.
 *   Examples:
 *   - `output="C:\\Users\\me\\Temp\\trace.bin"`
 *   - `output="/tmp/some \"quoted\" name.bin"`
 *
 * Old legacy positional format (deprecated, still supported for backward compatibility):
 *   1. class — fully qualified name of the class under test (required).
 *   2. method — public method name inside that class to run (required).
 *   3. output — path to a file where the trace will be written (optional).
 *   4+. agent-specific extra arguments in the order provided by a particular agent.
 * Example: `-javaagent:path/to/agent.jar=org.example.MyTest,run,/tmp/trace.bin`.
 *
 *
 * Notes:
 *
 * - Patterns (include/exclude): value is a single string with items separated by ';'.
 *   Each item is trimmed; empty items are ignored; duplicates are removed.
 *   Semicolons inside a single pattern are not supported — split into separate items instead.
 *
 * - Unknown keys are accepted but warned about in logs.
 *
 * - The parser first tries `key=value` style.
 *   On error, it falls back to positional style and emits a warning suggesting to migrate.
 *
 * - Individual agents pass the list of additional supported keys via `validAdditionalArgs`;
 *   unknown extra keys are warned in logs, but parsing proceeds.
 */
object TraceAgentParameters {
    const val ARGUMENT_CLASS = "class"
    const val ARGUMENT_METHOD = "method"
    const val ARGUMENT_OUTPUT = "output"
    const val ARGUMENT_INCLUDE = "include"
    const val ARGUMENT_EXCLUDE = "exclude"
    const val ARGUMENT_LAZY = "lazy"

    @JvmStatic
    lateinit var rawArgs: String

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
        // Store for metainformation
        rawArgs = args

        // Try to parse key=value format
        val kvArguments = parseKVArgs(args)
        if (kvArguments == null) {
            Logger.warn { "Looks like old-style arguments found, consider migrate to key-value arguments" }
            val actualArguments = splitArgs(args)

            classUnderTraceDebugging = actualArguments.getOrNull(0) ?: error("Class name was not provided")
            namedArgs[ARGUMENT_CLASS] = classUnderTraceDebugging
            methodUnderTraceDebugging = actualArguments.getOrNull(1) ?: error("Method name was not provided")
            namedArgs[ARGUMENT_METHOD] = methodUnderTraceDebugging
            setClassUnderTraceDebuggingToMethodOwner()
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
            classUnderTraceDebugging = kvArguments[ARGUMENT_CLASS]
                ?: error("Class name argument \"$ARGUMENT_CLASS\" was not provided")
            methodUnderTraceDebugging = kvArguments[ARGUMENT_METHOD]
                ?: error("Method name argument \"$ARGUMENT_METHOD\" was not provided")
            setClassUnderTraceDebuggingToMethodOwner()
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
    
    private fun setClassUnderTraceDebuggingToMethodOwner(
        startClass: String = classUnderTraceDebugging, method: String = methodUnderTraceDebugging
    ) {
        classUnderTraceDebugging =
            runCatching { Class.forName(startClass) }.getOrNull()
                ?.let { findDeclaringClassOrInterface(it, method) }
                ?: startClass
    }
    
    private fun findDeclaringClassOrInterface(startClass: Class<*>, methodName: String, set: MutableSet<String> = mutableSetOf()): String? {
        if (!set.add(startClass.name)) return null
        for (declaredMethod in startClass.declaredMethods) {
            when {
                declaredMethod.name != methodName -> continue
                !Modifier.isPublic(declaredMethod.modifiers) -> continue
                Modifier.isAbstract(declaredMethod.modifiers) -> continue
                Modifier.isStatic(declaredMethod.modifiers) -> continue
                else -> return startClass.name
            }
        }
        startClass.superclass?.let { findDeclaringClassOrInterface(it, methodName, set) }?.let { return it }
        return startClass.interfaces.firstNotNullOfOrNull { findDeclaringClassOrInterface(it, methodName, set) }
    }

    @JvmStatic
    fun getArg(name: String) = namedArgs[name]

    @JvmStatic
    fun getIncludePatterns(): List<String> = splitPatterns(namedArgs[ARGUMENT_INCLUDE])

    @JvmStatic
    fun getExcludePatterns(): List<String> = splitPatterns(namedArgs[ARGUMENT_EXCLUDE])

    /**
     * Is true by default
     */
    @JvmStatic
    fun getLazyTransformationEnabled(): Boolean = namedArgs[ARGUMENT_LAZY] != "false"

    private fun splitPatterns(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

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

    fun warn(m: String): Map<String, String>? {
        Logger.warn { "Agent arguments error at position $idx: $m" }
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
                    return warn("Invalid first key character '$ch'")
                }
                key.append(ch)
                state = KVParserState.KEY
            }
            KVParserState.KEY -> {
                if (ch == '=') {
                    state = KVParserState.VALUESTART
                } else if (!ch.isJavaIdentifierPart()) {
                    return warn("Invalid key character '$ch'")
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
                    return warn("Need ','")
                }
                state = KVParserState.KEYSTART
            }
        }
    }

    when (state) {
        KVParserState.KEY -> return warn("Cannot find value for last key")
        KVParserState.QUOTEESCAPE,
        KVParserState.QUOTEDVALUE -> return warn("Last value is unfinished, expect '\"'")
        KVParserState.ESCAPE -> return warn("Unfinished escape in last value")
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
