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

import org.jetbrains.lincheck.settings.BreakpointExpressionSlot

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

    /**
     * Notification that a breakpoint expression — a condition or a watch ([slot]) — was detected as unsafe.
     */
    data class BreakpointExpressionUnsafetyDetected(
        val breakpointData: BreakpointData,
        val slot: BreakpointExpressionSlot,
        val safetyViolationMessage: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : LiveDebuggerNotification(timestamp)

    /** Notification that a breakpoint has reached its configured hit limit. */
    data class BreakpointHitLimitReached(
        val breakpointData: BreakpointData,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : LiveDebuggerNotification(timestamp)

    /**
     * Notification that a breakpoint was rejected by a sensitive-area blocklist — either at
     * registration or because the snapshot hook was never injected into a blocked area.
     * [reason] names the policy that rejected it, so the user never sees a silently dead breakpoint.
     */
    data class BreakpointBlocked(
        val breakpointData: BreakpointData,
        val reason: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : LiveDebuggerNotification(timestamp)

    /**
     * Notification that a hit was suppressed by dynamic-extent enforcement: the call stack passed
     * through the blocked [blockedFrameClass]. Unlike [BreakpointBlocked], the breakpoint stays
     * valid — it still fires on call paths that avoid blocked areas.
     */
    data class BreakpointHitSuppressed(
        val breakpointData: BreakpointData,
        val blockedFrameClass: String,
        val reason: String,
        override val timestamp: Long = System.currentTimeMillis(),
    ) : LiveDebuggerNotification(timestamp)
}