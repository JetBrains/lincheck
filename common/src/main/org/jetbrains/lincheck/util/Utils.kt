/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util

// Trace agent modes
private const val TRACE_DEBUGGER_MODE_PROPERTY = "lincheck.traceDebuggerMode"
private const val TRACE_RECORDER_MODE_PROPERTY = "lincheck.traceRecorderMode"
internal val isInTraceDebuggerMode by lazy { System.getProperty(TRACE_DEBUGGER_MODE_PROPERTY, "false").toBoolean() }
internal val isInTraceRecorderMode by lazy { System.getProperty(TRACE_RECORDER_MODE_PROPERTY, "false").toBoolean() }

// Transformation utilities
internal fun isInLincheckPackage(className: String) =
    className.startsWith(LINCHECK_PACKAGE_NAME) ||
    className.startsWith(LINCHECK_KOTLINX_PACKAGE_NAME) ||
    className.startsWith(LINCHECK_BOOTSTRAP_PACKAGE_NAME)

internal val StackTraceElement.isLincheckInternals get() =
    this.className.startsWith(LINCHECK_PACKAGE_NAME) ||
    this.className.startsWith(LINCHECK_KOTLINX_PACKAGE_NAME)

internal const val LINCHECK_PACKAGE_NAME            = "org.jetbrains.lincheck."
internal const val LINCHECK_KOTLINX_PACKAGE_NAME    = "org.jetbrains.kotlinx.lincheck."
internal const val LINCHECK_RUNNER_PACKAGE_NAME     = "org.jetbrains.kotlinx.lincheck.runner."
internal const val LINCHECK_BOOTSTRAP_PACKAGE_NAME  = "sun.nio.ch.lincheck."

/**
 * Test if the given class name corresponds to a Java lambda class.
 */
internal fun isJavaLambdaClass(className: String): Boolean =
    className.contains("\$\$Lambda")

// Idea plugin flags
/**
 * Internal property to check that trace point IDs are in a strict sequential order.
 */
internal val eventIdStrictOrderingCheck =
    System.getProperty("lincheck.debug.withEventIdSequentialCheck") != null

/**
 * This property on the top level serves as a cache.
 * We will call `ideaPluginEnabled` method only once
 * and so on the plugin side the callback will be called also only once.
 */
internal val ideaPluginEnabled by lazy { ideaPluginEnabled() }

/**
 * Debugger replaces the result of this method to `true` if idea plugin is enabled.
 */
private fun ideaPluginEnabled(): Boolean {
    // treat as enabled in tests if we want so
    return eventIdStrictOrderingCheck
}