/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.network

/**
 * Notifications sent by the live debugger to the connected client.
 */
open class LiveDebuggerNotification(timestamp: Long) : TracingNotification(timestamp) {
    /**
     * Identifies a breakpoint by its [breakpointUuid] and source location.
     */
    data class BreakpointData(
        // TODO: reconsider what data to include: maybe only `breakpointUuid` is enough ?
        val breakpointUuid: java.util.UUID,
        val className: String,
        val fileName: String,
        val lineNumber: Int,
    ) {
        override fun toString(): String = "$breakpointUuid:$className:$fileName:$lineNumber"

        companion object {
            fun parseFromString(string: String): BreakpointData? {
                val parts = string.split(":")
                if (parts.size < 4) return null

                val breakpointUuid = try {
                    java.util.UUID.fromString(parts[0])
                } catch (_: IllegalArgumentException) {
                    return null
                }
                val className = parts[1]
                val fileName = parts[2]
                val lineNumber = parts[3].toIntOrNull() ?: return null

                return BreakpointData(breakpointUuid, className, fileName, lineNumber)
            }
        }
    }

    /** Notification that a breakpoint condition was detected as unsafe. */
    data class BreakpointConditionUnsafetyDetected(
        val breakpointData: BreakpointData,
        val safetyViolationMessage: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : LiveDebuggerNotification(timestamp)

    /** Notification that a breakpoint has reached its configured hit limit. */
    data class BreakpointHitLimitReached(
        val breakpointData: BreakpointData,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : LiveDebuggerNotification(timestamp)
}