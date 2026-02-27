/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

// ========================================================
//   Lincheck packages
// ========================================================

fun isInLincheckPackage(className: String) =
    className.startsWith(LINCHECK_PACKAGE_NAME) ||
    className.startsWith(LINCHECK_KOTLINX_PACKAGE_NAME) ||
    className.startsWith(LINCHECK_BOOTSTRAP_PACKAGE_NAME) ||
    className.startsWith(LINCHECK_RELOCATED_PACKAGE_PREFIX)

val StackTraceElement.isLincheckInternals get() =
    this.className.startsWith(LINCHECK_PACKAGE_NAME) ||
    this.className.startsWith(LINCHECK_KOTLINX_PACKAGE_NAME)

internal const val LINCHECK_PACKAGE_NAME             = "org.jetbrains.lincheck."
internal const val LINCHECK_KOTLINX_PACKAGE_NAME     = "org.jetbrains.kotlinx.lincheck."
internal const val LINCHECK_RUNNER_PACKAGE_NAME      = "org.jetbrains.kotlinx.lincheck.runner."
internal const val LINCHECK_BOOTSTRAP_PACKAGE_NAME   = "sun.nio.ch.lincheck."
internal const val LINCHECK_RELOCATED_PACKAGE_PREFIX = "org.jetbrains.lincheck.shadow."


// ========================================================
//   Java and Kotlin standard libraries
// ========================================================

/* Top-level package checks only, see below more specific checks for parts of Java/Kotlin stdlib */

/**
 * Checks if the given class name belongs to the Java standard library (`java` or `javax` packages).
 */
fun isJavaStandardLibraryClass(className: String) =
    className.startsWith("java.") ||
    className.startsWith("javax.")

/**
 * Checks if the given class name belongs to the Java platform library (`jdk` or `sun` packages).
 */
fun isJdkLibraryClass(className: String) =
    className.startsWith("jdk.")        ||
    className.startsWith("sun.")        ||
    className.startsWith("com.sun.")    ||
    // Old legacy Java std library for CORBA, for instance, `org/omg/stub/javax/management`;
    // can appear on Java 8 when JMX is used.
    className.startsWith("org.omg.")

/**
 * Checks if the given class name belongs to the Kotlin standard library (`kotlin` package).
 */
fun isKotlinStandardLibraryClass(className: String) =
    className.startsWith("kotlin.")

/**
 * Checks if the given class name belongs to a recognized uninstrumented standard library class
 * (including Java and Kotlin standard libraries, as well as JDK libraries).
 */
fun isRecognizedUninstrumentedStandardLibraryClass(className: String) =
    isJavaStandardLibraryClass(className) ||
    isJdkLibraryClass(className) ||
    isKotlinStandardLibraryClass(className)

// ========================================================
//   Instrumentation libraries
// ========================================================

fun isAsmClass(className: String): Boolean =
    // use a hack to circumvent package shadowing, see `TraceAgentTasks.kt`
    className.startsWith(listOf("org", "objectweb", "asm").joinToString("."))

fun isByteBuddyClass(className: String): Boolean =
    // use a hack to circumvent package shadowing, see `TraceAgentTasks.kt`
    className.startsWith(listOf("net", "bytebuddy").joinToString("."))

fun isJavaWebSocketClass(className: String): Boolean =
    // use a hack to circumvent package shadowing, see `TraceAgentTasks.kt`
    className.startsWith(listOf("org", "java_websocket").joinToString("."))

/**
 * Checks whether the given class name belongs to the Gradle framework.
 */
fun isGradleClass(className: String) =
    className.startsWith("org.gradle.") ||
    className.startsWith("worker.org.gradle.")


// ========================================================
//   Misc third-party libraries
// ========================================================

/**
 * Checks if the given class name corresponds to a recognized testing library class.
 */
fun isRecognizedTestingLibraryClass(className: String) =
    className.startsWith("org.junit.") ||
    className.startsWith("junit.framework.") ||
    className.startsWith("io.mockk.")

/**
 * Checks if the given class name corresponds to a recognized logging library class.
 */
fun isRecognizedLoggingLibraryClass(className: String) =
    className.startsWith("org.slf4j.")

/**
 * Determines whether a given class name belongs to a recognized Apache library.
 */
fun isRecognizedApacheLibraryClass(className: String) =
    className.startsWith("org.apache.commons.lang.")

/**
 * Checks if a given class name belongs to a recognized uninstrumented library class.
 * These are classes from specific third-party libraries that
 * are used as is and should not be subject to Lincheck instrumentation.
 */
fun isRecognizedUninstrumentedThirdPartyLibraryClass(className: String) =
    // fast-util data structures
    className.startsWith("it.unimi.dsi.fastutil.") ||
    // misc
    className.startsWith("com.esotericsoftware.kryo.") ||
    className.startsWith("net.rubygrapefruit.platform.")


// ========================================================
//   IntelliJ javaagents
// ========================================================

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
 * Checks if the given class name belongs to the IntelliJ runtime coverage instrumentation package.
 */
fun isIntellijInstrumentationCoverageAgentClass(className: String) =
    className.startsWith("com.intellij.rt.coverage.instrumentation")

/**
 * Checks if the given class name belongs to the JetBrains coverage package.
 */
fun isJetBrainsCoverageClass(className: String) =
    className.startsWith("org.jetbrains.coverage")

/**
 * Checks if the given class name belongs to the IntelliJ runtime agents.
 */
fun isIntellijRuntimeAgentClass(className: String) =
    isIntellijRuntimeDebuggerAgentClass(className) ||
    isIntellijRuntimeCoverageAgentClass(className)


// ========================================================
//   Kotlinx libraries
// ========================================================

/**
 * Checks whether the given class name belongs to the `kotlinx.atomicfu` package.
 */
fun isKotlinxAtomicFUClass(className: String) =
    className.startsWith("kotlinx.atomicfu.")

/**
 * Checks if the given class name belongs to the `kotlinx.coroutines` debugger support-related classes.
 */
fun isKotlinxCoroutinesDebugClass(className: String) =
    className.startsWith("kotlinx.coroutines.debug.") ||
    className == "kotlinx.coroutines.DebugKt"


// ========================================================
//   Java: misc
// ========================================================

/**
 * Checks whether the given method corresponds to the `toString()` Java method.
 */
internal fun isToStringMethod(methodName: String, desc: String) =
    methodName == "toString" && desc == "()Ljava/lang/String;"

/**
 * Tests if the provided [className] represents [StackTraceElement] class.
 */
internal fun isStackTraceElementClass(className: String): Boolean =
    className == "java.lang.StackTraceElement"

/**
 * Checks whether the provided [className] corresponds to the [java.util.Arrays] class.
 */
internal fun isJavaUtilArraysClass(className: String): Boolean =
    className == "java.util.Arrays"

/**
 * Checks if the provided class name matches the [jdk.internal.access.JavaLangAccess] class.
 */
internal fun isJavaLangAccessClass(className: String): Boolean =
    className == "jdk.internal.access.JavaLangAccess"


// ========================================================
//   Java: lambdas
// ========================================================

/**
 * Test if the given class name corresponds to a Java lambda class.
 */
fun isJavaLambdaClass(className: String): Boolean =
    className.contains("\$\$Lambda")

/**
 * Extracts and returns the enclosing class name of a Java lambda class.
 */
internal fun getJavaLambdaEnclosingClass(className: String): String {
    require(isJavaLambdaClass(className)) { "Not a Java lambda class: $className" }
    return className.substringBefore("\$\$Lambda")
}


// ========================================================
//   Java: class loaders
// ========================================================

/**
 * Tests if the provided [className] contains `"ClassLoader"` as a substring.
 */
internal fun isClassLoaderClassName(className: String): Boolean =
    className.contains("ClassLoader")

/**
 * Checks if the given method name and descriptor correspond to
 * the `ClassLoader.loadClass(String name)` method.
 */
internal fun isLoadClassMethod(methodName: String, desc: String) =
    methodName == "loadClass" && desc == "(Ljava/lang/String;)Ljava/lang/Class;"


// ========================================================
//   Java stdlib: thread-related classes
// ========================================================

/**
 * Tests if the provided [className] represents one of jdk internal [ThreadContainer] classes
 * that use [JavaLangAccess.start] API to start threads.
 */
fun isThreadContainerClass(className: String): Boolean =
    className == "jdk.internal.vm.SharedThreadContainer"  ||
    className == "jdk.internal.misc.ThreadFlock"

fun isThreadContainerThreadStartMethod(className: String, methodName: String): Boolean =
    isThreadContainerClass(className) && methodName == "start"


// ========================================================
//   Java stdlib: executors
// ========================================================

internal fun isJavaExecutorService(className: String) =
    className.startsWith("java.util.concurrent.AbstractExecutorService") ||
    className.startsWith("java.util.concurrent.ThreadPoolExecutor") ||
    className.startsWith("java.util.concurrent.ForkJoinPool")


// ========================================================
//   Java stdlib: collections
// ========================================================

internal fun isCollectionsLibrary(className: String) =
    (className in COLLECTION_LIBRARIES)

internal fun isConcurrentCollectionsLibrary(className: String) =
    (className in CONCURRENT_COLLECTION_LIBRARIES)

private val COLLECTION_LIBRARIES = setOf(
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

private val CONCURRENT_COLLECTION_LIBRARIES = setOf(
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


// ========================================================
//   Java stdlib: method handle classes
// ========================================================

/**
 * Determines if a given class name represents a method handle related class,
 * that is one of the following classes:
 *   - [MethodHandle]
 *   - [MethodHandles]
 *   - [MethodHandles.Lookup]
 *   - [MethodType]
 */
fun isMethodHandleRelatedClass(className: String): Boolean =
    className.startsWith("java.lang.invoke") &&
    (className.contains("MethodHandle") || className.contains("MethodType"))

/**
 * Determines whether the specified [MethodHandle] method should be ignored.
 *
 * We ignore all methods from [MethodHandle], except various `invoke` methods, such as:
 *   - [MethodHandle.invoke]
 *   - [MethodHandle.invokeExact]
 *   - [MethodHandle.invokeWithArguments]
 * These methods are not ignored because we need to analyze the invoked target method.
 */
fun isIgnoredMethodHandleMethod(className: String, methodName: String): Boolean =
    isMethodHandleRelatedClass(className) && !methodName.contains("invoke")