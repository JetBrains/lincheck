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
    val fileName: String,
    val lineNumber: Int,
) {
    companion object {
        fun read(rawString: String): SnapshotBreakpoint {
            val split = rawString.split(":")
            return SnapshotBreakpoint(split[0], split[1].toInt())
        }
    }
}