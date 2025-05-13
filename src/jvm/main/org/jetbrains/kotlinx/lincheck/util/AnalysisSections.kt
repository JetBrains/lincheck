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
import sun.nio.ch.lincheck.ThreadDescriptor
import sun.nio.ch.lincheck.Injections

/**
 * Represents different types of analysis sections within the Lincheck framework.
 * These sections provide various analysis guarantees and characteristics.

 * The sections are ordered by the strength of the provided guarantees, from weakest to strongest:
 *   [NORMAL], [SILENT], [SILENT_PROPAGATING], [ATOMIC], [IGNORED].
 * Guarantees of each particular section are documented separately.
 *
 * Section types can be split into two categories: local and propagating (see [isCallStackPropagating]).
 *
 *   - Local section is only applied to the current method in the call stack.
 *     Other methods down the call stack can reside in different analysis section types,
 *     including weaker section types.
 *     Thus, it is possible to have an alternating sequence of different section types in the call stack.
 *
 *   - Propagating sections propagate down the call stack.
 *     Other methods down the call stack can only reside in the same analysis section, or a stronger one.
 *
 * Local sections are required to support methods like `ConcurrentHashMap.computeIfAbsent(key, lambda)`.
 * The method `computeIfAbsent` itself can be trusted and not analyzed by the framework in the user code by default.
 * However, the user-provided lambda is not trusted and needs to be analyzed.
 *
 * Thus, `computeIfAbsent` can be put into a (local, non-propagating) silent section.
 * As such, code inside `computeIfAbsent` will be muted,
 * but if this method calls the provided lambda, it still will be analyzed fully.
 *
 * Local sections: [NORMAL], [SILENT].
 * Propagating sections: [SILENT_PROPAGATING], [ATOMIC], [IGNORED].
 */
internal enum class AnalysisSectionType {

    /**
     * Normal section without special handling.
     * Inside normal sections, all events are tracked.
     * All the events occurring inside a normal section are added to the trace,
     * unless other factors prevent it.
     * Thread switch points can occur arbitrarily within a normal section.
     */
    NORMAL,

    /**
     * Silent sections are used to mute analysis.
     * Note that events are still tracked inside silent sections, but
     * they are not added to the trace by default.
     * Thread switch points can occur inside a silent section only if they are forced.
     * For instance, because of some blocking synchronization primitive,
     * like an attempt to acquire a monitor that is already held by another thread.
     */
    SILENT,

    /**
     * Same as the silent section, but propagates down the call stack.
     *
     * As an example of the difference between regular and propagating silent sections, consider:
     *
     *   - If `ConcurrentHashMap.computeIfAbsent(key, lambda)` is put into [SILENT] section,
     *     the analyses of code from `computeIfAbsent` itself will be muted,
     *     but analyses of code from `lambda` (called from `computeIfAbsent`) will not be muted.
     *
     *   - If `ConcurrentHashMap.computeIfAbsent(key, lambda)` is put into [SILENT_PROPAGATING] section,
     *     the analyses of code from `computeIfAbsent` and all other functions called from it
     *     (including code of `lambda`) will be muted.
     */
    SILENT_PROPAGATING,

    /**
     * Code inside an atomic section behaves like an atomic indivisible operation.
     * Technically, the atomic section is a stronger version of the nested silent section,
     * with an additional guarantee that thread switch points cannot occur inside an atomic section at all.
     * TODO: "no-switch-points inside atomic section" guarantee is not checked currently.
     */
    ATOMIC,

    /**
     * Code inside ignored sections is completely ignored by the framework.
     * No analysis is performed and no events are tracked inside an ignored section.
     */
    IGNORED,
}

internal fun AnalysisSectionType.isCallStackPropagating() =
    this >= AnalysisSectionType.SILENT_PROPAGATING

internal fun AnalysisSectionType.isSilent() =
    this == AnalysisSectionType.SILENT         ||
    this == AnalysisSectionType.SILENT_PROPAGATING

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

internal fun getDefaultSilentSectionType(className: String, methodName: String): AnalysisSectionType? {
    if (className.startsWith("java.util.concurrent.")) {
        if (isJavaExecutorService(className)) {
            if (methodName == "submit") {
                return AnalysisSectionType.SILENT_PROPAGATING
            }
            return AnalysisSectionType.SILENT
        }
        if (className.startsWith("java.util.concurrent.locks.AbstractQueuedSynchronizer"))
            return AnalysisSectionType.SILENT
        if (className == "java.util.concurrent.FutureTask")
            return AnalysisSectionType.SILENT
    }
    return null
}

private fun isJavaExecutorService(className: String) =
    className.startsWith("java.util.concurrent.AbstractExecutorService") ||
    className.startsWith("java.util.concurrent.ThreadPoolExecutor") ||
    className.startsWith("java.util.concurrent.ForkJoinPool")