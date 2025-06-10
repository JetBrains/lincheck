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

import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedCTestConfiguration
import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingCTestConfiguration
import org.jetbrains.kotlinx.lincheck.traceagent.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck.transformation.isThreadContainerClass
import org.jetbrains.kotlinx.lincheck.util.AnalysisSectionType.*
import sun.nio.ch.lincheck.Injections
import sun.nio.ch.lincheck.ThreadDescriptor

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
    if (descriptor == null || descriptor.eventTracker == null) {
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

/**
 * Configures how different code sections should be analyzed.
 * Used to control which classes should be transformed for analysis, what analysis sections
 * should be applied, and which methods should be hidden from trace results.
 *
 * @property analyzeStdLib Controls whether standard library code should be analyzed. When false,
 *                        standard collections and concurrent collections are hidden,
 *                        concurrent collections are muted.
 */
internal class AnalysisProfile(val analyzeStdLib: Boolean) {
    
    constructor(testConfiguration: ManagedCTestConfiguration?) : this(
        testConfiguration is ModelCheckingCTestConfiguration && testConfiguration.stdLibAnalysisEnabled
    )


    /**
     * Determines whether a given class and method should be transformed (instrumented) for analysis.
     *
     * @param className The fully qualified name of the class to check
     * @param methodName The name of the method to check
     * @return true if the class/method should be transformed, false otherwise
     */
    @Suppress("UNUSED_PARAMETER") // methodName is here for uniformity and might become useful in the future  
    fun shouldTransform(className: String, methodName: String): Boolean {
        // We do not need to instrument most standard Java classes.
        // It is fine to inject the Lincheck analysis only into the
        // `java.util.*` ones, ignored the known atomic constructs.
        if (className.startsWith("java.")) {
            if (className == "java.lang.Thread") return true
            if (className.startsWith("java.util.concurrent.") && className.contains("Atomic")) return false
            if (className.startsWith("java.util.")) return true
            if (isInTraceDebuggerMode) {
                if (className.startsWith("java.io.")) return true
                if (className.startsWith("java.nio.")) return true
                if (className.startsWith("java.time.")) return true
            }
            return false
        }
        if (className.startsWith("com.sun.")) return false
        if (className.startsWith("sun.")) {
            // We should never instrument the Lincheck classes.
            if (className.startsWith("sun.nio.ch.lincheck.")) return false
            if (isInTraceDebuggerMode && className.startsWith("sun.nio.")) return true
            return false
        }
        if (className.startsWith("javax.")) return false
        if (className.startsWith("jdk.")) {
            // Transform `ThreadContainer.start` to detect thread forking.
            if (isThreadContainerClass(className)) return true
            return false
        }
        // We do not need to instrument most standard Kotlin classes.
        // However, we need to inject the Lincheck analysis into the classes
        // related to collections, iterators, random and coroutines.
        if (className.startsWith("kotlin.")) {
            if (className.startsWith("kotlin.concurrent.ThreadsKt")) return true
            if (className.startsWith("kotlin.collections.")) return true
            if (className.startsWith("kotlin.jvm.internal.Array") && className.contains("Iterator")) return true
            if (className.startsWith("kotlin.ranges.")) return true
            if (className.startsWith("kotlin.random.")) return true
            if (className.startsWith("kotlin.coroutines.jvm.internal.")) return false
            if (className.startsWith("kotlin.coroutines.")) return true
            if (isInTraceDebuggerMode && className.startsWith("kotlin.io.")) return true
            return false
        }
        // We do not instrument AtomicFU atomics.
        if (className.startsWith("kotlinx.atomicfu.")) {
            if (className.contains("Atomic")) return false
            return true
        }
        // We need to skip the classes related to the debugger support in Kotlin coroutines.
        if (className.startsWith("kotlinx.coroutines.debug.")) return false
        if (className == "kotlinx.coroutines.DebugKt") return false
        // We should never transform the coverage-related classes.
        if (className.startsWith("com.intellij.rt.coverage.")) return false
        // We should skip intellij debugger agent classes.
        if (className.startsWith("com.intellij.rt.debugger.agent.")) return false
        // We can also safely do not instrument some libraries for performance reasons.
        if (className.startsWith("com.esotericsoftware.kryo.")) return false
        if (className.startsWith("net.bytebuddy.")) return false
        if (className.startsWith("net.rubygrapefruit.platform.")) return false
        if (className.startsWith("io.mockk.")) return false
        if (className.startsWith("it.unimi.dsi.fastutil.")) return false
        if (className.startsWith("worker.org.gradle.")) return false
        if (className.startsWith("org.objectweb.asm.")) return false
        if (className.startsWith("org.gradle.")) return false
        if (className.startsWith("org.slf4j.")) return false
        if (className.startsWith("org.apache.commons.lang.")) return false
        if (className.startsWith("org.junit.")) return false
        if (className.startsWith("junit.framework.")) return false
        // Finally, we should never instrument the Lincheck classes.
        if (className.startsWith("org.jetbrains.kotlinx.lincheck.")) return false
        // All the classes that were not filtered out are eligible for transformation.
        return true
    }

    /**
     * Determines what type of analysis section should be applied to a given class and method.
     *
     * @param className The fully qualified name of the class to check
     * @param methodName The name of the method to check
     * @return The [AnalysisSectionType] to use for analyzing this class/method
     */
    fun getAnalysisSectionFor(className: String,  methodName: String): AnalysisSectionType = when {
        isJavaExecutorService(className) && methodName == "submit" -> AnalysisSectionType.SILENT_PROPAGATING
        isJavaExecutorService(className) -> AnalysisSectionType.SILENT
        className.startsWith("java.util.concurrent.locks.AbstractQueuedSynchronizer") -> AnalysisSectionType.SILENT
        className == "java.util.concurrent.FutureTask" -> AnalysisSectionType.SILENT
        isConcurrentCollectionsLibrary(className) && !analyzeStdLib -> AnalysisSectionType.SILENT
        
        else -> AnalysisSectionType.NORMAL
    }

    /**
     * Determines whether calls to a given class/method should be hidden from trace results.
     * Used to filter out standard library implementation details.
     *
     * @param className The fully qualified name of the class to check
     * @param methodName The name of the method to check
     * @return true if calls should be hidden from results, false otherwise
     */
    @Suppress("UNUSED_PARAMETER") // methodName is here for uniformity and might become useful in the future
    fun shouldBeHidden(className: String, methodName: String): Boolean = 
        !analyzeStdLib && (isConcurrentCollectionsLibrary(className) || isCollectionsLibrary(className))
}

internal fun isCollectionsLibrary(className: String) = className in setOf(
    // Interfaces
    "java.lang.Iterable",
    "java.util.Collection",
    "java.util.List",
    "java.util.Set",
    "java.util.Queue",
    "java.util.Deque",
    "java.util.NavigableSet",
    "java.util.SortedSet",
    "java.util.Map",
    "java.util.SortedMap",
    "java.util.NavigableMap",
    

    // Abstract implementations
    "java.util.AbstractCollection",
    "java.util.AbstractList",
    "java.util.AbstractQueue",
    "java.util.AbstractSequentialList",
    "java.util.AbstractSet",
    "java.util.AbstractMap",

    // Concrete implementations
    "java.util.ArrayDeque",
    "java.util.ArrayList",
    "java.util.AttributeList",
    "java.util.EnumSet",
    "java.util.HashSet",
    "java.util.LinkedHashSet",
    "java.util.LinkedList",
    "java.util.PriorityQueue",
    "java.util.Stack",
    "java.util.TreeSet",
    "java.util.Vector",
    "java.util.HashMap",
    "java.util.LinkedHashMap",
    "java.util.WeakHashMap",
    "java.util.TreeMap",
)

internal fun isConcurrentCollectionsLibrary(className: String) = className in setOf(
    // Interfaces
    "java.util.concurrent.BlockingDeque",
    "java.util.concurrent.BlockingQueue",
    "java.util.concurrent.TransferQueue",
    "java.util.concurrent.ConcurrentMap",
    "java.util.concurrent.ConcurrentNavigableMap",

    // Concrete implementations
    // Concurrent collections
    "java.util.concurrent.ConcurrentHashMap",
    "java.util.concurrent.ConcurrentLinkedDeque",
    "java.util.concurrent.ConcurrentLinkedQueue",
    "java.util.concurrent.ConcurrentSkipListMap",
    "java.util.concurrent.ConcurrentSkipListSet",

    // Blocking queues
    "java.util.concurrent.ArrayBlockingQueue",
    "java.util.concurrent.LinkedBlockingDeque",
    "java.util.concurrent.LinkedBlockingQueue",
    "java.util.concurrent.DelayQueue",
    "java.util.concurrent.PriorityBlockingQueue",
    "java.util.concurrent.SynchronousQueue",
    "java.util.concurrent.LinkedTransferQueue",

    // Copy-on-write collections
    "java.util.concurrent.CopyOnWriteArrayList",
    "java.util.concurrent.CopyOnWriteArraySet",

    // Inner class view
    "java.util.concurrent.ConcurrentHashMap\$KeySetView"
)

private fun isJavaExecutorService(className: String) =
    className.startsWith("java.util.concurrent.AbstractExecutorService") 
    || className.startsWith("java.util.concurrent.ThreadPoolExecutor") 
    || className.startsWith("java.util.concurrent.ForkJoinPool")
