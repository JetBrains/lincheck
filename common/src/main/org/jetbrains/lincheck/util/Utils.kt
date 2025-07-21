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
val isInTraceDebuggerMode by lazy { System.getProperty(TRACE_DEBUGGER_MODE_PROPERTY, "false").toBoolean() }
val isInTraceRecorderMode by lazy { System.getProperty(TRACE_RECORDER_MODE_PROPERTY, "false").toBoolean() }

// Transformation utilities
fun isInLincheckPackage(className: String) =
    className.startsWith(LINCHECK_PACKAGE_NAME) ||
    className.startsWith(LINCHECK_KOTLINX_PACKAGE_NAME) ||
    className.startsWith(LINCHECK_BOOTSTRAP_PACKAGE_NAME)

val StackTraceElement.isLincheckInternals get() =
    this.className.startsWith(LINCHECK_PACKAGE_NAME) ||
    this.className.startsWith(LINCHECK_KOTLINX_PACKAGE_NAME)

internal const val LINCHECK_PACKAGE_NAME            = "org.jetbrains.lincheck."
internal const val LINCHECK_KOTLINX_PACKAGE_NAME    = "org.jetbrains.kotlinx.lincheck."
internal const val LINCHECK_RUNNER_PACKAGE_NAME     = "org.jetbrains.kotlinx.lincheck.runner."
internal const val LINCHECK_BOOTSTRAP_PACKAGE_NAME  = "sun.nio.ch.lincheck."

/**
 * Test if the given class name corresponds to a Java lambda class.
 */
fun isJavaLambdaClass(className: String): Boolean =
    className.contains("\$\$Lambda")

/**
 * Tests if the provided [className] represents one of jdk internal [ThreadContainer] classes
 * that use [JavaLangAccess.start] API to start threads.
 */
fun isThreadContainerClass(className: String): Boolean =
    className == "jdk.internal.vm.SharedThreadContainer"  ||
    className == "jdk.internal.misc.ThreadFlock"

fun isThreadContainerThreadStartMethod(className: String, methodName: String): Boolean =
    isThreadContainerClass(className) && methodName == "start"

/**
 * Checks if the given class name belongs to the IntelliJ runtime debugger agent package.
 */
fun isIntellijRuntimeDebuggerAgentClass(className: String) =
    className.startsWith("com.intellij.rt.debugger.agent")

/**
 * Checks if the given class name belongs to the IntelliJ runtime coverage agent package.
 */
fun isIntellijRuntimeCoverageAgentClass(className: String) =
    className.startsWith("com.intellij.rt.coverage")

/**
 * Checks if the given class name belongs to the IntelliJ runtime agents.
 */
fun isIntellijRuntimeAgentClass(className: String) =
    isIntellijRuntimeDebuggerAgentClass(className) || isIntellijRuntimeCoverageAgentClass(className)


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
val ideaPluginEnabled by lazy { ideaPluginEnabled() }

/**
 * Debugger replaces the result of this method to `true` if idea plugin is enabled.
 */
private fun ideaPluginEnabled(): Boolean {
    // treat as enabled in tests if we want so
    return eventIdStrictOrderingCheck
}