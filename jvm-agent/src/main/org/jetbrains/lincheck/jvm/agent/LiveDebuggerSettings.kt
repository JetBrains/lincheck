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

internal data class LiveDebuggerSettings(
    val lineBreakPoints: List<SnapshotBreakpoint>
) {
    companion object {
        fun readList(list: List<String>): LiveDebuggerSettings {
            val parsed = list.map { SnapshotBreakpoint.read(it) }
            return LiveDebuggerSettings(parsed)
        }
    }
}

internal data class SnapshotBreakpoint(
    val className: String,
    val fileName: String,
    val lineNumber: Int,
    val conditionClassName: String?,
    val conditionMethodName: String?,
    val conditionArgs: List<String>?
) {
    companion object {
        fun read(rawString: String): SnapshotBreakpoint {
            val split = rawString.split(":")

            val className = split[0]

            val condition = split[3].let { if (it == "null") null else it }
            val conditionClassName = condition?.let { className }
            val conditionMethodName = condition?.substringBefore("(")
            val conditionArgs = condition?.substringAfter("(")?.substringBefore(")")?.split(",")?.map { it.trim() }

            return SnapshotBreakpoint(
                className = className,
                fileName = split[1],
                lineNumber = split[2].toInt(),
                conditionClassName = conditionClassName,
                conditionMethodName = conditionMethodName,
                conditionArgs = conditionArgs
            ).also { println("Read breakpoint: $it") }
        }
    }
}