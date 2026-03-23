/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.controller

open class LiveDebuggerNotification(timestamp: Long) : TracingNotification(timestamp) {
    data class BreakpointData(
        // TODO: reconsider what data to include: maybe just breakpointId?
        val className: String,
        val fileName: String,
        val lineNumber: Int,
    ) {
        override fun toString(): String = "$className:$fileName:$lineNumber"

        companion object {
            fun parseFromString(string: String): BreakpointData? {
                val parts = string.split(":")
                if (parts.size < 3) return null

                val className = parts[0]
                val fileName = parts[1]
                val lineNumber = parts[2].toIntOrNull() ?: return null

                return BreakpointData(className, fileName, lineNumber)
            }
        }
    }

    data class BreakpointConditionUnsafetyDetected(
        val breakpointData: BreakpointData,
        val safetyViolationMessage: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : LiveDebuggerNotification(timestamp)

    data class BreakpointHitLimitReached(
        val breakpointData: BreakpointData,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : LiveDebuggerNotification(timestamp)
}