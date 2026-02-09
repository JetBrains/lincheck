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
import org.jetbrains.lincheck.util.LIVE_DEBUGGER_MODE_PROPERTY
import org.jetbrains.lincheck.util.TRACE_DEBUGGER_MODE_PROPERTY
import org.jetbrains.lincheck.util.TRACE_RECORDER_MODE_PROPERTY
import org.jetbrains.lincheck.util.isInLiveDebuggerMode
import org.jetbrains.lincheck.util.isInTraceDebuggerMode
import org.jetbrains.lincheck.util.isInTraceRecorderMode
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import javax.management.remote.JMXServiceURL

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
 *
 * - mode — agent mode. Possible options are:
 *       * `traceRecorder` --- enables trace recorder mode;
 *       * `liveDebugger` --- enables live debugger mode;
 *       * `traceDebugger` --- enables trace debugger mode.
 *       Example: `mode=traceRecorder`
 *       If not specified, falls back to system properties for backward compatibility.
 *
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
 * - pack — boolean that enables zipping trace artifact files, it is false by default.
 *       Example: `pack=true`
 *
 * - format — output format for trace recorder dumps. Possible options are:
 *       * `binary` --- serialized binary format;
 *       * `text` --- text output;
 *       * `null` --- discards recorded trace.
 *       Example: `format=text`
 *
 * - formatOption — extra options for the selected format. Possible options are:
 *       * for `binary`:
 *           * `dump` --- keeps the whole trace in-memory and dumps it to the file at the end;
 *           * `stream` --- writes the trace points incrementally during the execution (default);
 *       * for `text`: `verbose` --- enables verbose output (disabled by default).
 *       Example: `formatOption=dump`
 *
 * - jmxServer — boolean that enables JMX server for remote monitoring and management, it is off by default.
 *     The server is available at the URL `service:jmx:rmi:///jndi/rmi://<jmxHost>:<jmxPort>/tracing`.
 *       Example: `jmxServer=on` or `jmxServer=off`
 *
 * - jmxHost — hostname or IP address for the JMX server, defaults to localhost.
 *       Example: `jmxHost=127.0.0.1`
 *
 * - jmxPort — port number for JMX connections, defaults to 9999.
 *       Example: `jmxPort=9999`
 *
 * - rmiPort — port number for RMI registry, defaults to 9998.
 *       Example: `rmiPort=9998`
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
    const val ARGUMENT_MODE = "mode"
    const val ARGUMENT_CLASS = "class"
    const val ARGUMENT_METHOD = "method"
    const val ARGUMENT_OUTPUT = "output"
    const val ARGUMENT_INCLUDE = "include"
    const val ARGUMENT_EXCLUDE = "exclude"
    const val ARGUMENT_JMX_SERVER = "jmxServer"
    const val ARGUMENT_JMX_HOST = "jmxHost"
    const val ARGUMENT_JMX_PORT = "jmxPort"
    const val ARGUMENT_RMI_PORT = "rmiPort"

    const val DEFAULT_JMX_HOST = "localhost"
    const val DEFAULT_JMX_PORT = 9999
    const val DEFAULT_RMI_PORT = 9998
    const val DEFAULT_TRACE_PORT = 9997

    @JvmStatic
    lateinit var rawArgs: String

    @JvmStatic
    lateinit var classUnderTraceDebugging: String

    @JvmStatic
    lateinit var methodUnderTraceDebugging: String

    @JvmStatic
    var traceDumpFilePath: String? = null

    @JvmStatic
    val jmxServerEnabled: Boolean
        get() = getArg(ARGUMENT_JMX_SERVER)?.lowercase() == "on"

    @JvmStatic
    val jmxHost: String
        get() = getArg(ARGUMENT_JMX_HOST) ?: DEFAULT_JMX_HOST

    @JvmStatic
    val jmxPort: Int
        get() = getArg(ARGUMENT_JMX_PORT)?.toIntOrNull() ?: DEFAULT_JMX_PORT

    @JvmStatic
    val rmiPort: Int
        get() = getArg(ARGUMENT_RMI_PORT)?.toIntOrNull() ?: DEFAULT_RMI_PORT

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

            classUnderTraceDebugging = actualArguments.getOrNull(0) ?: ""
            methodUnderTraceDebugging = actualArguments.getOrNull(1) ?: ""
            namedArgs[ARGUMENT_CLASS] = classUnderTraceDebugging
            namedArgs[ARGUMENT_METHOD] = methodUnderTraceDebugging

            validateClassAndMethodArgumentsAreProvided()
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
            classUnderTraceDebugging = kvArguments[ARGUMENT_CLASS] ?: ""
            methodUnderTraceDebugging = kvArguments[ARGUMENT_METHOD] ?: ""
            if (!classUnderTraceDebugging.isEmpty() && !methodUnderTraceDebugging.isEmpty()) {
                setClassUnderTraceDebuggingToMethodOwner()
            }

            traceDumpFilePath = kvArguments[ARGUMENT_OUTPUT]

            val allowedKeys = mutableSetOf(ARGUMENT_CLASS, ARGUMENT_METHOD, ARGUMENT_OUTPUT)
            allowedKeys.addAll(validAdditionalArgs)

            val unsupportedKeys = kvArguments.keys - allowedKeys
            unsupportedKeys.forEach {
                Logger.warn { "Unknown agent argument \"$it\"" }
            }

            namedArgs.putAll(kvArguments)
        }

        setupMode()
        validateJmxParameters()
    }

    @JvmStatic
    fun validateClassAndMethodArgumentsAreProvided() {
        if (classUnderTraceDebugging.isBlank()) {
            error("Class name was not provided")
        }
        if (methodUnderTraceDebugging.isBlank()) {
            error("Method name was not provided")
        }
    }

    @JvmStatic
    private fun setupMode() {
        // Parse mode from agent arguments, 
        // if the mode is not passed, fallback to system properties.
        val modeArg = getArg(ARGUMENT_MODE) ?: return

        val isInTraceRecorderMode = (System.getProperty(TRACE_RECORDER_MODE_PROPERTY)?.toBoolean() == true)
        val isInTraceDebuggerMode = (System.getProperty(TRACE_DEBUGGER_MODE_PROPERTY)?.toBoolean() == true)
        val isInLiveDebuggerMode = (System.getProperty(LIVE_DEBUGGER_MODE_PROPERTY)?.toBoolean() == true)

        // Check system properties for consistency.
        when (modeArg) {
            "traceRecorder" -> {
                if (isInTraceDebuggerMode) {
                    error("Mode argument is 'traceRecorder', but inconsistent system property `lincheck.traceDebuggerMode` is set")
                }
                if (isInLiveDebuggerMode) {
                    error("Mode argument is 'traceRecorder', but inconsistent system property `lincheck.liveDebuggerMode` is set")
                }
                // Set system property if not already set.
                if (!isInTraceRecorderMode) {
                    System.setProperty(TRACE_RECORDER_MODE_PROPERTY, "true")
                }
            }
            
            "traceDebugger" -> {
                if (isInTraceRecorderMode) {
                    error("Mode argument is 'traceDebugger', but inconsistent system property `lincheck.traceRecorderMode` is set")
                }
                if (isInLiveDebuggerMode) {
                    error("Mode argument is 'traceDebugger', but inconsistent system property `lincheck.liveDebuggerMode` is set")
                }
                // Set system property if not already set.
                if (!isInTraceDebuggerMode) {
                    System.setProperty(TRACE_DEBUGGER_MODE_PROPERTY, "true")
                }
            }
            
            "liveDebugger" -> {
                if (isInTraceRecorderMode) {
                    error("Mode argument is 'liveDebugger', but inconsistent system property `lincheck.traceRecorderMode` is set")
                }
                if (isInTraceDebuggerMode) {
                    error("Mode argument is 'liveDebugger', but inconsistent system property `lincheck.traceDebuggerMode` is set")
                }
                // Set system property if not already set.
                if (!isInLiveDebuggerMode) {
                    System.setProperty(LIVE_DEBUGGER_MODE_PROPERTY, "true")
                }
            }
            
            else -> {
                error("Invalid mode argument: '$modeArg'. Expected one of: traceRecorder, liveDebugger, traceDebugger")
            }
        }
    }

    @JvmStatic
    fun validateMode() {
        // Check if one of the required parameters is set.
        check(isInTraceRecorderMode || isInTraceDebuggerMode || isInLiveDebuggerMode) {
            """
            When lincheck agent is attached to process,
            mode should be selected by agent parameter `mode` or by VM parameter:
            `lincheck.traceRecorderMode`, `lincheck.traceDebuggerMode`, or `lincheck.liveDebuggerMode`.
            One of them is expected to be set.
            
            Rerun with: 
            - `-Dlincheck.traceRecorderMode=true` or `mode=traceRecorder` as agent argument;
            - `-Dlincheck.traceDebuggerMode=true` or `mode=traceDebugger` as agent argument;
            - `-Dlincheck.liveDebuggerMode=true` or `mode=liveDebugger` as agent argument.
            """
            .trimIndent()
        }
        
        // Check that only one parameter is set
        val modesEnabled = listOf(isInTraceRecorderMode, isInTraceDebuggerMode, isInLiveDebuggerMode).count { it }
        check(modesEnabled == 1) {
            """
            When lincheck agent is attached to process,
            mode should be selected by one of agent parameter `mode` or VM parameters:
            `lincheck.traceRecorderMode`, `lincheck.traceDebuggerMode`, or `lincheck.liveDebuggerMode`.
            Only one of them expected to be set.
            
            Rerun with exactly one mode flag set:
            - `-Dlincheck.traceRecorderMode=true` or `mode=traceRecorder` as agent argument;
            - `-Dlincheck.traceDebuggerMode=true` or `mode=traceDebugger` as agent argument;
            - `-Dlincheck.liveDebuggerMode=true` or `mode=liveDebugger` as agent argument.
            """
            .trimIndent()
        }
    }

    @JvmStatic
    private fun validateJmxParameters() {
        val jmxServerEnabled = getArg(ARGUMENT_JMX_SERVER) == "on"

        if (!jmxServerEnabled) {
            // JMX server is not enabled, check that JMX-related parameters are not set
            val jmxHost = getArg(ARGUMENT_JMX_HOST)
            val jmxPort = getArg(ARGUMENT_JMX_PORT)
            val rmiPort = getArg(ARGUMENT_RMI_PORT)

            if (jmxHost != null) {
                Logger.warn { "JMX parameter \"$ARGUMENT_JMX_HOST\" is set but JMX server is not enabled (jmxServer=off)" }
            }
            if (jmxPort != null) {
                Logger.warn { "JMX parameter \"$ARGUMENT_JMX_PORT\" is set but JMX server is not enabled (jmxServer=off)" }
            }
            if (rmiPort != null) {
                Logger.warn { "JMX parameter \"$ARGUMENT_RMI_PORT\" is set but JMX server is not enabled (jmxServer=off)" }
            }
        }
    }

    private fun setClassUnderTraceDebuggingToMethodOwner(
        startClass: String = classUnderTraceDebugging,
        method: String = methodUnderTraceDebugging,
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

    @JvmStatic
    fun getJmxServerUrl(jmxHost: String, jmxPort: Int, rmiPort: Int): JMXServiceURL =
        JMXServiceURL("service:jmx:rmi://$jmxHost:$jmxPort/jndi/rmi://$jmxHost:$rmiPort/tracing")

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
