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

// Trace agent modes
private const val TRACE_DEBUGGER_MODE_PROPERTY = "lincheck.traceDebuggerMode"
private const val TRACE_RECORDER_MODE_PROPERTY = "lincheck.traceRecorderMode"
val isInTraceDebuggerMode by lazy { System.getProperty(TRACE_DEBUGGER_MODE_PROPERTY, "false").toBoolean() }
val isInTraceRecorderMode by lazy { System.getProperty(TRACE_RECORDER_MODE_PROPERTY, "false").toBoolean() }


// Idea plugin flags
/**
 * Internal property to check that trace point IDs are in a strict sequential order.
 */
val eventIdStrictOrderingCheck =
    System.getProperty("lincheck.debug.withEventIdSequentialCheck") != null

/**
 * This property on the top level serves as a cache.
 * We will call `ideaPluginEnabled` method only once
 * and so on the plugin side the callback will be called also only once.
 */
val ideaPluginEnabled by lazy { ideaPluginEnabled() }

/**
 * Debugger replaces the result of this method to `true` if idea plugin is enabled.
 */
private fun ideaPluginEnabled(): Boolean {
    // treat as enabled in tests if we want so
    return eventIdStrictOrderingCheck
}