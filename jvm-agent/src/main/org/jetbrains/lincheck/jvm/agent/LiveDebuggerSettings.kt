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

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.lincheck.util.Logger
import java.io.File
import java.util.*

class LiveDebuggerSettings(lineBreakPoints: List<SnapshotBreakpoint>) {

    val lineBreakPoints: List<SnapshotBreakpoint>
        get() = synchronized(_lineBreakPoints) { _lineBreakPoints.toList() }

    private val _lineBreakPoints: MutableList<SnapshotBreakpoint> =
        Collections.synchronizedList(lineBreakPoints)

    fun addBreakpoints(list: List<String>): List<SnapshotBreakpoint> {
        val breakpoints = list.map { SnapshotBreakpoint.read(it) }
        return addBreakpointsFromList(breakpoints)
    }

    fun addBreakpointsFromList(breakpoints: List<SnapshotBreakpoint>): List<SnapshotBreakpoint> {
        val addedBreakpoints = mutableListOf<SnapshotBreakpoint>()
        for (breakpoint in breakpoints) {
            if (!lineBreakPoints.contains(breakpoint)) {
                _lineBreakPoints.add(breakpoint)
                addedBreakpoints.add(breakpoint)
            }
        }
        return addedBreakpoints
    }

    fun removeBreakpoints(list: List<String>): List<SnapshotBreakpoint> {
        val breakpoints = list.map { SnapshotBreakpoint.read(it) }
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
        fun read(rawString: String): SnapshotBreakpoint {
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
 * JSON representation of a breakpoint condition.
 */
@Serializable
data class BreakpointConditionJson(
    val className: String,
    val factoryMethodName: String,
    val capturedVariables: List<String>,
    val bytecode: String // Base64-encoded bytecode
)

/**
 * JSON representation of a breakpoint definition.
 */
@Serializable
data class BreakpointJson(
    val className: String,
    val filePath: String,
    val line: Int,
    val condition: BreakpointConditionJson?
)

/**
 * Parser for breakpoints JSON file passed via the `breakpointsFile` agent argument.
 */
object BreakpointsFileParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses breakpoints from a JSON file.
     *
     * @param filePath Path to the JSON file containing breakpoint definitions
     * @return List of parsed [SnapshotBreakpoint] objects
     * @throws BreakpointsFileException if the file cannot be read or parsed
     */
    fun parseBreakpointsFile(filePath: String): List<SnapshotBreakpoint> {
        val file = File(filePath)
        if (!file.exists()) {
            throw BreakpointsFileException("Breakpoints file not found: $filePath")
        }
        if (!file.canRead()) {
            throw BreakpointsFileException("Cannot read breakpoints file: $filePath")
        }

        val jsonContent = try {
            file.readText()
        } catch (e: Exception) {
            throw BreakpointsFileException("Failed to read breakpoints file: $filePath", e)
        }

        return parseBreakpointsJson(jsonContent)
    }

    /**
     * Parses breakpoints from a JSON string.
     *
     * @param jsonContent JSON string containing breakpoint definitions
     * @return List of parsed [SnapshotBreakpoint] objects
     * @throws BreakpointsFileException if the JSON is malformed or contains invalid data
     */
    fun parseBreakpointsJson(jsonContent: String): List<SnapshotBreakpoint> {
        val breakpointsJson = try {
            json.decodeFromString<List<BreakpointJson>>(jsonContent)
        } catch (e: Exception) {
            throw BreakpointsFileException("Failed to parse breakpoints JSON: ${e.message}", e)
        }

        return breakpointsJson.mapIndexed { index, bp ->
            try {
                convertToSnapshotBreakpoint(bp)
            } catch (e: Exception) {
                throw BreakpointsFileException(
                    "Failed to process breakpoint at index $index (${bp.className}:${bp.line}): ${e.message}",
                    e
                )
            }
        }
    }

    private fun convertToSnapshotBreakpoint(bp: BreakpointJson): SnapshotBreakpoint {
        require(bp.className.isNotBlank()) { "Class name is blank" }
        require(bp.filePath.isNotBlank()) { "File path is blank" }
        require(bp.line > 0) { "Line number must be positive" }

        val condition = bp.condition
        if (condition != null) {
            require(condition.className.isNotBlank()) { "Condition class name is blank" }
            require(condition.factoryMethodName.isNotBlank()) { "Condition factory method name is blank" }
        }
        val conditionBytecode = condition?.let {
            try {
                Base64.getDecoder().decode(it.bytecode)
            } catch (e: IllegalArgumentException) {
                throw BreakpointsFileException(
                    "Invalid Base64 bytecode for condition class '${it.className}'",
                    e
                )
            }
        }

        return SnapshotBreakpoint(
            className = bp.className,
            fileName = bp.filePath,
            lineNumber = bp.line,
            conditionClassName = condition?.className,
            conditionFactoryMethodName = condition?.factoryMethodName?.ifBlank { null },
            conditionCapturedVars = condition?.capturedVariables,
            conditionCodeFragment = conditionBytecode
        )
    }

    /**
     * Loads and registers breakpoints from a file into the given [LiveDebuggerSettings].
     *
     * @param filePath Path to the JSON file containing breakpoint definitions
     * @param settings The [LiveDebuggerSettings] to register breakpoints with
     * @return List of successfully added breakpoints
     */
    fun loadAndRegisterBreakpoints(filePath: String, settings: LiveDebuggerSettings): List<SnapshotBreakpoint> {
        Logger.info { "Loading breakpoints from file: $filePath" }
        val breakpoints = parseBreakpointsFile(filePath)
        val addedBreakpoints = settings.addBreakpointsFromList(breakpoints)
        Logger.info { "Registered ${addedBreakpoints.size} new breakpoints from $filePath" }
        return addedBreakpoints
    }
}

/**
 * Exception thrown when there's an error processing the breakpoints file.
 */
class BreakpointsFileException(message: String, cause: Throwable? = null) : Exception(message, cause)
