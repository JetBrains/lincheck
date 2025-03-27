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

import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy
import sun.nio.ch.lincheck.Injections
import sun.nio.ch.lincheck.ThreadDescriptor

/**
 * Enables analysis for the current thread.
 */
internal fun enableAnalysis() {
    Injections.enableAnalysis()
}

/**
 * Disables analysis for the current thread.
 */
internal fun disableAnalysis() {
    Injections.disableAnalysis()
}

/**
 * Enters an ignored section for the current thread.
 *
     * Does not affect the current thread if it is untracked
     * (e.g. not registered in the Lincheck strategy).
 */
internal fun enterIgnoredSection() {
    Injections.enterIgnoredSection()
}

/**
 * Leaves an ignored section for the current thread.
 *
     * Does not affect the current thread if it is untracked
     * (e.g. not registered in the Lincheck strategy).
 */
internal fun leaveIgnoredSection() {
    Injections.leaveIgnoredSection()
}

/**
 * Executes a given block of code within an ignored section.
 *
 * NOTE: this method is intended to be used during runtime,
 * *not* during bytecode instrumentation in the [org.jetbrains.kotlinx.lincheck.transformation] package.
 * In that context, please use [org.jetbrains.kotlinx.lincheck.transformation.invokeInsideIgnoredSection] instead.
 *
 * @param block the code to execute within the ignored section.
 * @return result of the [block] invocation.
 */
internal inline fun <R> runInsideIgnoredSection(block: () -> R): R {
    val descriptor = ThreadDescriptor.getCurrentThreadDescriptor()
    if (descriptor == null || descriptor.eventTracker !is ManagedStrategy) {
        return block()
    }
    descriptor.enterIgnoredSection()
    try {
        return block()
    } finally {
        descriptor.leaveIgnoredSection()
    }
}

/**
 * Exits the ignored section and invokes the provided [block] outside an ignored section,
 * restoring the ignored section back after the [block] is executed.
 *
 * @param block the code to execute outside the ignored section.
 * @return result of [block] invocation.
 * @throws IllegalStateException if the method is called not from an ignored section.
 */
internal inline fun <R> runOutsideIgnoredSection(block: () -> R): R {
    val descriptor = ThreadDescriptor.getCurrentThreadDescriptor()
    if (descriptor == null || descriptor.eventTracker !is ManagedStrategy) {
        return block()
    }
    check(descriptor.inIgnoredSection()) {
        "Current thread must be in ignored section"
    }
    val depth = descriptor.saveAndResetIgnoredSectionDepth()
    try {
        return block()
    } finally {
        descriptor.restoreIgnoredSectionDepth(depth)
    }
}