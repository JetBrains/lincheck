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

data class LiveDebuggerSettings(
    val lineBreakPoints: MutableList<SnapshotBreakpoint>
) {
    companion object {
        fun readList(list: List<String>): LiveDebuggerSettings {
            val parsed = list.map { SnapshotBreakpoint.read(it) }
            return LiveDebuggerSettings(parsed.toMutableList())
        }
    }

    fun addBreakpoints(list: List<String>): List<SnapshotBreakpoint> {
        val breakpoints = list.map { SnapshotBreakpoint.read(it) }
        for (breakpoint in breakpoints) {
            if (!lineBreakPoints.contains(breakpoint)) {
                lineBreakPoints.add(breakpoint)
            }
        }
        return breakpoints
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
}

data class SnapshotBreakpoint(
    val className: String,
    val fileName: String,
    val lineNumber: Int,
) {
    companion object {
        fun read(rawString: String): SnapshotBreakpoint {
            val split = rawString.split(":")
            return SnapshotBreakpoint(split[0], split[1], split[2].toInt())
        }
    }
}