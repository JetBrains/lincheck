/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.jetbrains.lincheck.util.*
import java.io.File
import java.util.*

class LiveDebuggerSettings(lineBreakPoints: List<SnapshotBreakpoint>) {

    val lineBreakPoints: List<SnapshotBreakpoint>
        get() = synchronized(_lineBreakPoints) { _lineBreakPoints.toList() }

    private val _lineBreakPoints: MutableList<SnapshotBreakpoint> =
        Collections.synchronizedList(lineBreakPoints)

    fun addBreakpoints(breakpoints: List<SnapshotBreakpoint>): List<SnapshotBreakpoint> {
        val addedBreakpoints = mutableListOf<SnapshotBreakpoint>()
        for (breakpoint in breakpoints) {
            if (!lineBreakPoints.contains(breakpoint)) {
                _lineBreakPoints.add(breakpoint)
                addedBreakpoints.add(breakpoint)
            }
        }
        return addedBreakpoints
    }

    fun removeBreakpoints(breakpoints: List<SnapshotBreakpoint>): List<SnapshotBreakpoint> {
        val removedBreakpoints = mutableListOf<SnapshotBreakpoint>()
        for (breakpoint in breakpoints) {
            if (lineBreakPoints.contains(breakpoint)) {
                _lineBreakPoints.remove(breakpoint)
                removedBreakpoints.add(breakpoint)
            }
        }
        return removedBreakpoints
    }
override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LiveDebuggerSettings) return false

        return (lineBreakPoints == other.lineBreakPoints)
    }

    override fun hashCode(): Int {
        return lineBreakPoints.hashCode()
    }

    companion object {
        const val MAX_ARRAY_ELEMENTS = 10
    }
}

data class SnapshotBreakpoint(
    val className: String,
    val fileName: String,
    val lineNumber: Int,
    val conditionClassName: String?,
    val conditionFactoryMethodName: String?,
    val conditionCapturedVars: List<String>?,
    val conditionCodeFragment: ByteArray?
) {
    companion object {
        fun parseFromString(rawString: String): SnapshotBreakpoint {
            val parts = rawString.split(":")

            val className = parts[0]
            val fileName = parts[1]
            val lineNumber = parts[2].toInt()

            // Condition format: "$className:$factoryMethodName:$capturedVarsStr:$encodedBytecode"
            val conditionClassName = parts.getOrNull(3)?.let { if (it == "null") null else it }
            val conditionFactoryMethodName = parts.getOrNull(4)?.let { if (it == "null") null else it }
            val conditionCapturedVars = parts.getOrNull(5)?.let { if (it == "null") null else it.split(",") }
            val conditionCodeFragment = parts.getOrNull(6)?.let {
                if (it == "null") null else Base64.getDecoder().decode(it)
            }

            return SnapshotBreakpoint(
                className = className,
                fileName = fileName,
                lineNumber = lineNumber,
                conditionClassName = conditionClassName,
                conditionFactoryMethodName = conditionFactoryMethodName,
                conditionCapturedVars = conditionCapturedVars,
                conditionCodeFragment = conditionCodeFragment
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SnapshotBreakpoint

        if (lineNumber != other.lineNumber) return false
        if (className != other.className) return false
        if (fileName != other.fileName) return false

        return true
    }

    override fun hashCode(): Int {
        var result = lineNumber
        result = 31 * result + className.hashCode()
        result = 31 * result + fileName.hashCode()
        return result
    }
}

/**
 * Parses information about breakpoints from an INI configuration file.
 *
 * Expected file format:
 *   ```
 *   # comments are allowed
 *   ; this is also a comment
 *
 *   [Breakpoint 1]
 *   className = org.example.MyClass
 *   fileName = MyClass.java
 *   lineNumber = 101
 *
 *   # breakpoint id in a section header can be an arbitrary number
 *   [Breakpoint 2]
 *   className = org.example.MyClass
 *   fileName = MyClass.java
 *   lineNumber = 120
 *   # the following properties are optional
 *   conditionClassName = org.example.MyCondition
 *   conditionFactoryMethodName = create
 *   conditionCapturedVars = var1,var2
 *   conditionCodeFragment = <base64-encoded bytecode>
 * ```
 */
object BreakpointsFileParser {

    private val SECTION_NAME_REGEX = Regex("Breakpoint \\d+")

    private const val KEY_CLASS_NAME = "className"
    private const val KEY_FILE_NAME = "fileName"
    private const val KEY_LINE_NUMBER = "lineNumber"
    private const val KEY_CONDITION_CLASS_NAME = "conditionClassName"
    private const val KEY_CONDITION_FACTORY_METHOD_NAME = "conditionFactoryMethodName"
    private const val KEY_CONDITION_CAPTURED_VARS = "conditionCapturedVars"
    private const val KEY_CONDITION_CODE_FRAGMENT = "conditionCodeFragment"

    /**
     * Parses breakpoints from an INI file.
     *
     * @param filePath Path to the INI file containing breakpoint definitions.
     * @return List of parsed [SnapshotBreakpoint] objects.
     */
    fun parseBreakpointsFile(filePath: String): List<SnapshotBreakpoint> {
        val file = File(filePath)
        check(file.exists()) { "Breakpoints file not found: $filePath" }
        check(file.canRead()) { "Cannot read breakpoints file: $filePath" }

        val content = file.readText()
        val sections = parseIniSections(content)

        return sections.map { (sectionName, properties) ->
            try {
                convertToSnapshotBreakpoint(properties)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Failed to process breakpoint in section [$sectionName]: ${e.message}", e
                )
            }
        }
    }

    /**
     * Parses INI content into a list of (section name, key-value map) pairs.
     */
    private fun parseIniSections(content: String): List<Pair<String, Map<String, String>>> {
        val sections = mutableListOf<Pair<String, MutableMap<String, String>>>()
        var currentSection: Pair<String, MutableMap<String, String>>? = null

        for (line in content.lines()) {
            val trimmed = line.trim()

            // skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                continue
            }

            // section header
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                val sectionName = trimmed.substring(1, trimmed.length - 1).trim()
                require(SECTION_NAME_REGEX.matches(sectionName)) {
                    "Invalid section header: '$sectionName' (expected 'Breakpoint <number>')"
                }
                currentSection = sectionName to mutableMapOf()
                sections.add(currentSection)
                continue
            }

            // key = value
            val eqIndex = trimmed.indexOf('=')
            require(eqIndex >= 0) { "Invalid line (expected 'key = value'): $trimmed" }

            val key = trimmed.substring(0, eqIndex).trim()
            val value = trimmed.substring(eqIndex + 1).trim()
            val section = currentSection
            checkNotNull(section) { "Property '$key' found outside of any section" }

            section.second[key] = value
        }

        return sections
    }

    private fun convertToSnapshotBreakpoint(properties: Map<String, String>): SnapshotBreakpoint {
        val className = properties[KEY_CLASS_NAME]
        val fileName = properties[KEY_FILE_NAME]

        requireNotNull(className) { "Missing required property '$KEY_CLASS_NAME'" }
        requireNotNull(fileName) { "Missing required property '$KEY_FILE_NAME'" }
        require(className.isNotBlank()) { "Class name is blank" }
        require(fileName.isNotBlank()) { "File name is blank" }

        val lineNumber = properties[KEY_LINE_NUMBER]
            .ensureNotNull { "Missing required property '$KEY_LINE_NUMBER'" }
            .toIntOrNull()

        requireNotNull(lineNumber) { "Invalid line number: ${properties[KEY_LINE_NUMBER]}" }
        require(lineNumber > 0) { "Line number must be positive: $lineNumber" }

        val conditionClassName = properties[KEY_CONDITION_CLASS_NAME]
        val conditionFactoryMethodName = properties[KEY_CONDITION_FACTORY_METHOD_NAME]

        val conditionCapturedVars = properties[KEY_CONDITION_CAPTURED_VARS]
            ?.split(",")
            ?.map { it.trim() }

        val conditionCodeFragment = properties[KEY_CONDITION_CODE_FRAGMENT]?.let {
            try {
                Base64.getDecoder().decode(it)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "Invalid Base64 bytecode for condition class '$conditionClassName'",
                    e
                )
            }
        }

        return SnapshotBreakpoint(
            className = className,
            fileName = fileName,
            lineNumber = lineNumber,
            conditionClassName = conditionClassName,
            conditionFactoryMethodName = conditionFactoryMethodName,
            conditionCapturedVars = conditionCapturedVars,
            conditionCodeFragment = conditionCodeFragment
        )
    }
}
