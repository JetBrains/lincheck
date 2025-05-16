/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.trace

/**
 * Logs thread events such as thread switches and passed code locations.
 */
internal class TraceCollector {
    private val _trace = mutableListOf<TracePoint>()
    val trace: List<TracePoint> = _trace

    fun addTracePoint(tracePoint: TracePoint) {
        _trace += tracePoint
    }
}