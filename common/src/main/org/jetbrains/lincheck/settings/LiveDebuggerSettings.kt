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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.*

/**
 * Internal simple integer-based identifiers for breakpoints.
 * These identifiers are only used internally within javaagent ---
 * external clients should identify breakpoints by [UUID] instead.
 */
typealias BreakpointId = Int

class LiveDebuggerSettings(lineBreakpoints: List<SnapshotBreakpoint> = emptyList()) {

    /**
     * Source of unique [BreakpointId] handles assigned at registration time.
     */
    private val nextBreakpointId = AtomicInteger(0)

    /**
     * A concurrent map that stores the currently registered breakpoints in the debugger settings.
     */
    private val _lineBreakpoints: ConcurrentHashMap<BreakpointId, SnapshotBreakpoint> =
        ConcurrentHashMap<BreakpointId, SnapshotBreakpoint>().apply {
            for (breakpoint in lineBreakpoints) {
                put(nextBreakpointId.getAndIncrement(), breakpoint)
            }
        }

    /**
     * A read-only map that stores the currently registered breakpoints in the debugger settings.
     */
    val lineBreakpoints: Map<BreakpointId, SnapshotBreakpoint>
        get() = _lineBreakpoints

    /**
     * Registers each breakpoint, skipping any whose [SnapshotBreakpoint.uuid] is already present.
     * Same-line breakpoints with different UUIDs are deliberately treated as distinct.
     *
     * @return the breakpoints that were actually added (in input order).
     */
    fun addBreakpoints(breakpoints: List<SnapshotBreakpoint>): List<SnapshotBreakpoint> {
        val addedBreakpoints = mutableListOf<SnapshotBreakpoint>()
        for (breakpoint in breakpoints) {
            // Synchronize the entire check-then-act to prevent two concurrent callers from
            // both passing the duplicate check and registering the same breakpoint twice.
            synchronized(_lineBreakpoints) {
                val duplicate = _lineBreakpoints.values.firstOrNull { it.uuid == breakpoint.uuid }
                if (duplicate != null) {
                    Logger.warn {
                        """
                        Breakpoint with duplicate UUID is detected, skipping duplicate registration:
                            skipped    : $breakpoint
                            registered : $duplicate
                        """
                        .trimIndent()
                    }
                    return@synchronized
                }

                val id = nextBreakpointId.getAndIncrement()
                BreakpointStorage.registerBreakpoint(id, breakpoint.hitLimit, breakpoint)
                _lineBreakpoints[id] = breakpoint
                addedBreakpoints.add(breakpoint)
            }
        }
        return addedBreakpoints
    }

    /**
     * Removes the breakpoints with the given UUIDs.
     *
     * @return the breakpoints that were actually removed (in input order).
     */
    fun removeBreakpoints(uuids: List<UUID>): List<SnapshotBreakpoint> {
        val removedBreakpoints = mutableListOf<SnapshotBreakpoint>()
        for (uuid in uuids) {
            // Synchronize the entire find-then-remove to prevent two concurrent callers from
            // both finding the entry and attempting a double removal.
            synchronized(_lineBreakpoints) {
                val (id, removed) = _lineBreakpoints.entries
                     .firstOrNull { it.value.uuid == uuid }
                    ?.also { _lineBreakpoints.remove(it.key) }
                    ?: continue

                BreakpointStorage.removeBreakpoint(id)
                removedBreakpoints.add(removed)
            }
        }
        return removedBreakpoints
    }

    /**
     * Removes all registered breakpoints.
     *
     * @return the breakpoints that were removed.
     */
    fun removeAllBreakpoints(): List<SnapshotBreakpoint> {
        synchronized(_lineBreakpoints) {
            val removed = _lineBreakpoints.values.toList()
            for (id in _lineBreakpoints.keys) {
                BreakpointStorage.removeBreakpoint(id)
            }
            _lineBreakpoints.clear()
            return removed
        }
    }

    /**
     * Removes the breakpoint with the given internal [id] handle.
     *
     * Unlike [removeBreakpoints] (which matches by [SnapshotBreakpoint.uuid]),
     * this matches by the internal [BreakpointId].
     *
     * Important for the hit-limit path (and other re-enable after notification cases):
     * if the user re-adds a breakpoint after the hit-limit fires, it will receive a new internal id;
     * so this method will correctly find *nothing* (the old id is no longer in the map)
     * rather than accidentally removing the freshly re-added breakpoint.
     *
     * @return the removed [SnapshotBreakpoint], or `null` if no breakpoint with that id exists.
     */
    fun removeBreakpoint(id: BreakpointId): SnapshotBreakpoint? {
        synchronized(_lineBreakpoints) {
            val breakpoint = _lineBreakpoints.remove(id)
            BreakpointStorage.removeBreakpoint(id)
            return breakpoint
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LiveDebuggerSettings) return false

        return (lineBreakpoints == other.lineBreakpoints)
    }

    override fun hashCode(): Int {
        return lineBreakpoints.hashCode()
    }

    companion object {
        const val MAX_ARRAY_ELEMENTS = 10
    }
}

/**
 * [SnapshotBreakpoint] stores information about non-suspending snapshot-based breakpoints.
 *
 * [SnapshotBreakpoint]-s are identified and compared by their [uuid].
 * Same-line breakpoints with different UUIDs are deliberately distinct;
 * so multiple clients can each install their own breakpoint on the same source code line.
 *
 * @property uuid The client-assigned globally unique identifier for this breakpoint.
 * @property className The fully qualified name of the class where the breakpoint is set.
 * @property fileName The name of the file containing the breakpoint.
 * @property lineNumber The specific line number in the file where the breakpoint is set.
 * @property conditionClassName The class name that provides the conditional logic for the breakpoint, if any.
 * @property conditionFactoryMethodName The factory method name in the [conditionClassName] that generates the condition logic, if any.
 * @property conditionCapturedVars A list of variable names captured as part of the condition, if any.
 * @property conditionCodeFragment A serialized byte array of code fragments used for evaluating the condition, if any.
 * @property hitLimit The maximum number of times the breakpoint can be hit before it is automatically disabled.
 */
class SnapshotBreakpoint(
    val uuid: UUID,
    val className: String,
    val fileName: String,
    val lineNumber: Int,
    val conditionClassName: String?,
    val conditionFactoryMethodName: String?,
    val conditionCapturedVars: List<String>?,
    val conditionCodeFragment: ByteArray?,
    val hitLimit: Int = DEFAULT_HIT_LIMIT,
) {
    companion object {
        const val DEFAULT_HIT_LIMIT = 10_000

        /**
         * Decodes a breakpoint from the string produced by [encodeToString].
         */
        fun decodeFromString(string: String): SnapshotBreakpoint {
            val parts = string.split(":")

            val uuid = UUID.fromString(parts[0])
            val className = parts[1]
            val fileName = parts[2]
            val lineNumber = parts[3].toInt()

            // Condition format: "$className:$factoryMethodName:$capturedVarsStr:$encodedBytecode"
            val conditionClassName = parts.getOrNull(4)?.let { if (it == "null") null else it }
            val conditionFactoryMethodName = parts.getOrNull(5)?.let { if (it == "null") null else it }
            val conditionCapturedVars = parts.getOrNull(6)?.let { if (it == "null") null else it.split(",") }
            val conditionCodeFragment = parts.getOrNull(7)?.let {
                if (it == "null") null else Base64.getDecoder().decode(it)
            }
            val hitLimit = parts.getOrNull(8)?.toIntOrNull() ?: DEFAULT_HIT_LIMIT

            return SnapshotBreakpoint(
                uuid = uuid,
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

        /**
         * Decodes a list of breakpoints' from a list of strings produced by [encodeToString].
         */
        fun decodeListFromString(string: String): List<SnapshotBreakpoint> {
            return string.split(",").map { decodeFromString(it) }
        }
    }

    /**
     * Encodes this breakpoint as a colon-separated string accepted by [decodeListFromString].
     *
     * Format:
     *   `uuid:className:fileName:lineNumber:conditionClassName:conditionFactoryMethodName:conditionCapturedVars:conditionCodeFragment:hitLimit`.
     *
     * Missing condition fields are encoded as the literal `"null"`.
     */
    fun encodeToString(): String {
        val parts = listOf(
            uuid.toString(),
            className,
            fileName,
            lineNumber.toString(),
            conditionClassName ?: "null",
            conditionFactoryMethodName ?: "null",
            conditionCapturedVars?.joinToString(",") ?: "null",
            conditionCodeFragment?.let { Base64.getEncoder().encodeToString(it) } ?: "null",
            hitLimit.toString(),
        )
        return parts.joinToString(":")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SnapshotBreakpoint) return false
        return (uuid == other.uuid)
    }

    override fun hashCode(): Int =
        uuid.hashCode()

    override fun toString(): String {
        return buildString {
            append("Breakpoint #$uuid at $fileName:$lineNumber")

            append("[")
            append("class=$className,")
            if (conditionClassName != null) {
                append("condition=$conditionClassName,")
            }
            if (conditionFactoryMethodName != null) {
                append("factory=$conditionFactoryMethodName,")
            }
            if (conditionCapturedVars != null) {
                append("captured=(${conditionCapturedVars.joinToString(", ")}),")
            }
            if (conditionCodeFragment != null) {
                append("code=${conditionCodeFragment.toHexPreview(8)},")
            }
            append("hitLimit=$hitLimit")
            append("]")
        }
    }
}

fun List<SnapshotBreakpoint>.encodeToString(): String =
    joinToString(",") { it.encodeToString() }

/**
 * Class-only pre-filter: returns true when [className] (in canonical form)
 * is this breakpoint's declared owner or a `<owner>$...` generated descendant.
 *
 * Used when the source file name is not available.
 * The file-aware overload below narrows the match.
 */
fun SnapshotBreakpoint.isApplicableTo(className: String): Boolean =
    className.startsWith(this.className)

/**
 * Returns true if this breakpoint applies to
 * a class whose canonical name is [className] residing in source file [sourceFileName].
 * A breakpoint applies to its declared owner class or to a generated JVM descendant
 * compiled from the same source file.
 *
 * Kotlin lambdas, SAM conversions, suspend continuations, and anonymous objects
 * compile into separate JVM classes whose names start with `<owner>$`,
 * so strict owner matching would drop live breakpoints set on such source lines.
 */
fun SnapshotBreakpoint.isApplicableTo(className: String, sourceFileName: String): Boolean =
    this.className == className || (isApplicableTo(className) && fileName == sourceFileName)

/**
 * Class-only pre-filter: returns the breakpoints whose
 * owner class is [className] or a `<owner>$...` generated descendant.
 *
 * Used when the source file name is not available.
 * The file-aware overload below narrows the match.
 */
fun Iterable<SnapshotBreakpoint>.applicableTo(className: String): List<SnapshotBreakpoint> =
    filter { it.isApplicableTo(className) }

/**
 * Returns the breakpoints applicable to a class with the given canonical name and source file.
 * See [SnapshotBreakpoint.isApplicableTo] for the matching rules.
 *
 * Class names should be passed in **canonical** form (e.g., `com.example.Foo$Bar`).
 */
fun Iterable<SnapshotBreakpoint>.applicableTo(className: String, sourceFileName: String): List<SnapshotBreakpoint> =
    filter { it.isApplicableTo(className, sourceFileName) }

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
 *   uuid = 550e8400-e29b-41d4-a716-446655440000
 *   hitLimit = 50
 *   conditionClassName = org.example.MyCondition
 *   conditionFactoryMethodName = create
 *   conditionCapturedVars = var1,var2
 *   conditionCodeFragment = <base64-encoded bytecode>
 * ```
 *
 * The `uuid` field is optional; if omitted, a random UUID is assigned at parse time.
 */
object BreakpointsFileParser {

    private val SECTION_NAME_REGEX = Regex("Breakpoint \\d+")

    private const val KEY_UUID = "uuid"
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

        // UUID is optional: when omitted, the parser mints a fresh one
        val uuid = properties[KEY_UUID]?.let {
            try {
                UUID.fromString(it)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid UUID: '$it'", e)
            }
        } ?: UUID.randomUUID()

        return SnapshotBreakpoint(
            uuid = uuid,
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
