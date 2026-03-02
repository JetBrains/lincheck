/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.settings

import org.jetbrains.lincheck.util.*
import sun.nio.ch.lincheck.BreakpointStorage
import java.io.File
import java.util.*

class LiveDebuggerSettings(lineBreakPoints: List<SnapshotBreakpoint> = emptyList()) {

    val lineBreakPoints: List<SnapshotBreakpoint>
        get() = synchronized(_lineBreakPoints) { _lineBreakPoints.toList() }

    private val _lineBreakPoints: MutableList<SnapshotBreakpoint> =
        Collections.synchronizedList(lineBreakPoints.toMutableList())

    fun addBreakpoints(breakpoints: List<SnapshotBreakpoint>): List<SnapshotBreakpoint> {
        val addedBreakpoints = mutableListOf<SnapshotBreakpoint>()
        for (breakpoint in breakpoints) {
            // Synchronize the entire check-then-act to prevent two concurrent callers from
            // both passing the duplicate check and registering the same breakpoint twice.
            val registered = synchronized(_lineBreakPoints) {
                if (_lineBreakPoints.any { it == breakpoint }) return@synchronized null
                // Register in BreakpointStorage, which assigns the unique id and stores the
                // breakpoint as userData (passed directly to the hit-limit callback).
                val id = BreakpointStorage.registerBreakpoint(breakpoint.hitLimit, breakpoint)
                val registered = breakpoint.copy(id = id)
                _lineBreakPoints.add(registered)
                registered
            } ?: continue
            addedBreakpoints.add(registered)
        }
        return addedBreakpoints
    }

    /**
     * Removes breakpoints matched by location (className / fileName / lineNumber).
     * Used for explicit user-initiated removal (e.g. via JMX), where the caller supplies
     * breakpoints parsed from strings and therefore [SnapshotBreakpoint.UNASSIGNED_ID].
     */
    fun removeBreakpoints(breakpoints: List<SnapshotBreakpoint>): List<SnapshotBreakpoint> {
        val removedBreakpoints = mutableListOf<SnapshotBreakpoint>()
        for (breakpoint in breakpoints) {
            // Synchronize the entire find-then-remove to prevent two concurrent callers from
            // both finding the entry and attempting a double removal.
            val existing = synchronized(_lineBreakPoints) {
                val found = _lineBreakPoints.firstOrNull { it == breakpoint }
                    ?: return@synchronized null
                _lineBreakPoints.remove(found)
                found
            } ?: continue
            BreakpointStorage.removeBreakpoint(existing.id)
            removedBreakpoints.add(existing)
        }
        return removedBreakpoints
    }

    fun removeAllBreakpoints(): List<SnapshotBreakpoint> {
        synchronized(_lineBreakPoints) {
            val removed = _lineBreakPoints.toList()
            _lineBreakPoints.clear()
            return removed
        }
    }

    /**
     * Removes the breakpoint with the given [id].
     *
     * Unlike [removeBreakpoints] (which matches by location), this matches by the breakpoint's
     * unique integer id.  This is important for the hit-limit path: if the user re-adds a
     * breakpoint at the same location after the hit-limit fires (giving it a new id), this
     * method will correctly find *nothing* (the old id is no longer in the list) rather than
     * accidentally removing the freshly re-added breakpoint.
     *
     * Returns the removed [SnapshotBreakpoint], or `null` if no breakpoint with that id exists.
     */
    fun removeBreakpointById(id: Int): SnapshotBreakpoint? {
        val existing = synchronized(_lineBreakPoints) {
            val found = _lineBreakPoints.firstOrNull { it.id == id } ?: return null
            _lineBreakPoints.remove(found)
            found
        }
        BreakpointStorage.removeBreakpoint(id)
        return existing
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
    val conditionCodeFragment: ByteArray?,
    val hitLimit: Int = DEFAULT_HIT_LIMIT,
    /** Unique integer id assigned by [LiveDebuggerSettings] when the breakpoint is registered. */
    val id: Int = UNASSIGNED_ID,
) {
    companion object {
        const val DEFAULT_HIT_LIMIT = 100
        /** Sentinel value indicating the breakpoint has not yet been registered with [LiveDebuggerSettings]. */
        const val UNASSIGNED_ID = -1

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
            val hitLimit = parts.getOrNull(7)?.toIntOrNull() ?: DEFAULT_HIT_LIMIT

            return SnapshotBreakpoint(
                className = className,
                fileName = fileName,
                lineNumber = lineNumber,
                conditionClassName = conditionClassName,
                conditionFactoryMethodName = conditionFactoryMethodName,
                conditionCapturedVars = conditionCapturedVars,
                conditionCodeFragment = conditionCodeFragment,
                hitLimit = hitLimit,
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
 *   hitLimit = 50
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
    private const val KEY_HIT_LIMIT = "hitLimit"
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

        val hitLimit = properties[KEY_HIT_LIMIT]?.toIntOrNull() ?: SnapshotBreakpoint.DEFAULT_HIT_LIMIT

        return SnapshotBreakpoint(
            className = className,
            fileName = fileName,
            lineNumber = lineNumber,
            conditionClassName = conditionClassName,
            conditionFactoryMethodName = conditionFactoryMethodName,
            conditionCapturedVars = conditionCapturedVars,
            conditionCodeFragment = conditionCodeFragment,
            hitLimit = hitLimit,
        )
    }
}
