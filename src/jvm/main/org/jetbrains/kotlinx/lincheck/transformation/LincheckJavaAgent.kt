/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import net.bytebuddy.*
import net.bytebuddy.agent.*
import net.bytebuddy.dynamic.*
import net.bytebuddy.dynamic.loading.*
import net.bytebuddy.pool.*
import org.jetbrains.kotlinx.lincheck.runInIgnoredSection
import org.objectweb.asm.*
import org.reflections.Reflections
import org.reflections.scanners.SubTypesScanner
import java.io.FileOutputStream
import java.lang.instrument.*
import java.security.*


inline fun <R> withLincheckJavaAgent(stressTest: Boolean, block: () -> R): R {
    LincheckClassFileTransformer.install(stressTest)
    return try {
        block()
    } finally {
        LincheckClassFileTransformer.uninstall()
    }
}

object LincheckClassFileTransformer : ClassFileTransformer {
    private val transformedClassesModelChecking = HashMap<Any, ByteArray>()
    private val transformedClassesStress = HashMap<Any, ByteArray>()
    private val oldClasses = HashMap<Any, ByteArray>()

    private val instrumentation = ByteBuddyAgent.install()

    var isStressTest: Boolean = false

    fun install(stressTest: Boolean) {
        isStressTest = stressTest
        TransformationInjectionsInitializer.initialize()
        instrumentation.addTransformer(LincheckClassFileTransformer, true)
        val loadedClasses = instrumentation.allLoadedClasses
            .filter(instrumentation::isModifiableClass)
            .filter { shouldTransform(it.name) }
        try {
            instrumentation.retransformClasses(*loadedClasses.toTypedArray())
        } catch (t: Throwable) {
            loadedClasses.forEach {
                try {
                    instrumentation.retransformClasses(it)
                } catch (t: Throwable) {
                    System.err.println("Failed to re-transform $it")
                    t.printStackTrace()
                }
            }
        }
    }

    fun uninstall() {
        instrumentation.removeTransformer(LincheckClassFileTransformer)
        val classDefinitions = instrumentation.allLoadedClasses.mapNotNull { clazz ->
            val bytes = oldClasses[classKey(clazz.classLoader, clazz.name)]
            bytes?.let { ClassDefinition(clazz, it) }
        }
        instrumentation.redefineClasses(*classDefinitions.toTypedArray())
    }

    override fun transform(
        loader: ClassLoader?,
        className: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray
    ): ByteArray? {
        runInIgnoredSection {
            if (!shouldTransform(className.canonicalClassName)) return null
            synchronized(LincheckClassFileTransformer) {
                val transformedClasses = if (isStressTest) transformedClassesStress else transformedClassesModelChecking
                return transformedClasses.computeIfAbsent(classKey(loader, className)) {
                    oldClasses[classKey(loader, className)] = classfileBuffer
                    val reader = ClassReader(classfileBuffer)
                    val writer = ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
                    try {
                        val wrapInIgnoredSection = false // COMPLETION_NAME in className
                        reader.accept(LincheckClassVisitor(writer, wrapInIgnoredSection), ClassReader.SKIP_FRAMES)
                        writer.toByteArray()
                    } catch (e: Exception) {
                        System.err.println("Unable to transform $className")
                        e.printStackTrace()
                        classfileBuffer
                    }
                }
            }
        }
    }

    private fun shouldTransform(className: String): Boolean {
        if (className.contains("ClassLoader")) return true
        if (className.startsWith("sun.nio.ch.lincheck.")) return false
        if (className == "kotlin.collections.ArraysKt___ArraysKt") return false
        if (className == "kotlin.collections.CollectionsKt___CollectionsKt") return false

        if (className.startsWith("io.mockk.")) return false
        if (className.startsWith("kotlinx.atomicfu.")) return false
        if (className.startsWith("org.gradle.")) return false
        if (className.startsWith("org.slf4j.")) return false
        if (className.startsWith("worker.org.gradle.")) return false
        if (className.startsWith("org.objectweb.asm.")) return false
        if (className.startsWith("net.bytebuddy.")) return false
        if (className.startsWith("org.junit.")) return false
        if (className.startsWith("junit.framework.")) return false
        if (className.startsWith("com.sun.")) return false
        if (className.startsWith("java.util.")) {
            if (className.startsWith("java.util.zip")) return false
            if (className.startsWith("java.util.regex")) return false
            if (className.startsWith("java.util.jar")) return false
            if (className.startsWith("java.util.Immutable")) return false
            if (className.startsWith("java.util.logging")) return false
            if (className.startsWith("java.util.ServiceLoader")) return false
            if (className.startsWith("java.util.concurrent.atomic.") && className.contains("Atomic")) return false
            if (className.startsWith("java.util.function.")) return false
            if (className.contains("Exception")) return false
            return true
        }

        if (className.startsWith("sun.") ||
            className.startsWith("java.") ||
            className.startsWith("jdk.internal.") ||
            className.startsWith("kotlin.") &&
            !className.startsWith("kotlin.collections.") &&  // transform kotlin collections
            !(className.startsWith("kotlin.jvm.internal.Array") && className.contains("Iterator")) &&
            !className.startsWith("kotlin.ranges.") ||
            className.startsWith("com.intellij.rt.coverage.") ||
            className.startsWith("org.jetbrains.kotlinx.lincheck.") && !className.startsWith("org.jetbrains.kotlinx.lincheck.test.") ||
            className.startsWith("kotlinx.coroutines.DispatchedTask")
        ) return false

        return true
    }

    private val jobSupportClass = Class.forName("kotlinx/coroutines/JobSupportKt".replace("/", "."))

    val names = listOf(
        "kotlinx.coroutines.AbstractCoroutine",
        "kotlinx.coroutines.channels.ActorCoroutine",
        "kotlinx.coroutines.BlockingCoroutine",
        "kotlinx.coroutines.channels.BroadcastCoroutine",
        "kotlinx.coroutines.channels.ChannelCoroutine",
        "kotlinx.coroutines.CompletableDeferredImpl",
        "kotlinx.coroutines.DeferredCoroutine",
        "kotlinx.coroutines.DispatchedCoroutine",
        "kotlinx.coroutines.flow.internal.FlowCoroutine",
        "kotlinx.coroutines.JobImpl",
        "kotlinx.coroutines.channels.LazyActorCoroutine",
        "kotlinx.coroutines.channels.LazyBroadcastCoroutine",
        "kotlinx.coroutines.LazyDeferredCoroutine",
        "kotlinx.coroutines.LazyStandaloneCoroutine",
        "kotlinx.coroutines.channels.ProducerCoroutine",
        "kotlinx.coroutines.internal.ScopeCoroutine",
        "kotlinx.coroutines.StandaloneCoroutine",
        "kotlinx.coroutines.SupervisorCoroutine",
        "kotlinx.coroutines.SupervisorCoroutine",
        "kotlinx.coroutines.TimeoutCoroutine",
        "kotlinx.coroutines.UndispatchedCoroutine",
        "kotlinx.coroutines.JobSupport"
    ).map { it.replace(".", "/") }

}

object TransformationInjectionsInitializer {
    private var initialized = false

    fun initialize() {
        if (initialized) return

        // This line is a pure magic, it fixes "java.lang.NoClassDefFoundError:
        // Could not initialize class java.lang.StackTraceElement$HashedModules".
        // No clue why it occurs...
        NullPointerException().stackTrace

        val typePool: TypePool = TypePool.Default.ofSystemLoader()

        listOf(
            "kotlin.jvm.internal.Intrinsics",
            "sun.nio.ch.lincheck.CoroutineInternalCallTracker",
            "sun.nio.ch.lincheck.AtomicFields",
            "sun.nio.ch.lincheck.FinalFields",
            "sun.nio.ch.lincheck.CodeLocations",
            "sun.nio.ch.lincheck.SharedEventsTracker",
            "sun.nio.ch.lincheck.TestThread",
            "sun.nio.ch.lincheck.Injections",
        ).forEach { className ->
            ByteBuddy().redefine<Any>(
                typePool.describe(className).resolve(),
                ClassFileLocator.ForClassLoader.ofSystemLoader()
            ).make().allTypes.let {
                ClassInjector.UsingUnsafe.ofBootLoader().inject(it)
            }
        }

        initialized = true
    }
}

private fun classKey(loader: ClassLoader?, className: String) =
    if (loader == null) className
    else loader to className

private val COMPLETION_NAME = "org.jetbrains.kotlinx.lincheck.runner.ParallelThreadsRunner.Completion".replace(".", "/")

fun main() {
    val reflections = Reflections("ваш.пакет", SubTypesScanner(false))

    val subTypes = reflections.getSubTypesOf(Class.forName("kotlinx/coroutines/JobSupportKt".replace("/", ".")))

    for (clazz in subTypes) {
        println(clazz.getName())
    }
}