/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util

private const val TRACE_DEBUGGER_MODE_PROPERTY = "lincheck.traceDebuggerMode"
private const val TRACE_RECORDER_MODE_PROPERTY = "lincheck.traceRecorderMode"
val isInTraceDebuggerMode by lazy { System.getProperty(TRACE_DEBUGGER_MODE_PROPERTY, "false").toBoolean() }
val isInTraceRecorderMode by lazy { System.getProperty(TRACE_RECORDER_MODE_PROPERTY, "false").toBoolean() }