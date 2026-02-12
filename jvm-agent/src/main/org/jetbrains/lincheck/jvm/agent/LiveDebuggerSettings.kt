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

import java.util.*

data class LiveDebuggerSettings(
    val lineBreakPoints: MutableList<SnapshotBreakpoint>
) {
    fun addBreakpoints(list: List<String>): List<SnapshotBreakpoint> {
        val breakpoints = list.map { SnapshotBreakpoint.read(it) }
        val addedBreakpoints = mutableListOf<SnapshotBreakpoint>()
        for (breakpoint in breakpoints) {
            if (!lineBreakPoints.contains(breakpoint)) {
                lineBreakPoints.add(breakpoint)
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
                lineBreakPoints.remove(breakpoint)
                removedBreakpoints.add(breakpoint)
            }
        }
        return removedBreakpoints
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