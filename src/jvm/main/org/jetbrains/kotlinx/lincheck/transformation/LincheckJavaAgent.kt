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
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.transformation.LincheckClassFileTransformer.TransformationMode
import org.jetbrains.kotlinx.lincheck.transformation.LincheckClassFileTransformer.TransformationMode.*
import org.objectweb.asm.*
import java.lang.instrument.*
import java.lang.module.*
import java.security.*

internal inline fun <R> withLincheckJavaAgent(transformationMode: TransformationMode, block: () -> R): R {
    LincheckClassFileTransformer.install(transformationMode)
    return try {
        block()
    } finally {
        LincheckClassFileTransformer.uninstall()
    }
}

object LincheckClassFileTransformer : ClassFileTransformer {
    private val transformedClassesModelChecking = HashMap<Any, ByteArray>()
    private val transformedClassesStress = HashMap<Any, ByteArray>()
    private val nonTransformedClasses = HashMap<Any, ByteArray>()

    private val instrumentation = ByteBuddyAgent.install()

    private var transformationMode = STRESS

    enum class TransformationMode {
        STRESS,
        MODEL_CHECKING
    }

    internal fun install(transformationMode: TransformationMode) {
        this.transformationMode = transformationMode
        TransformationInjectionsInitializer.initialize()
        instrumentation.addTransformer(this, true)
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
                    System.err.println("Failed to re-transform $it:")
                    t.printStackTrace()
                }
            }
        }
    }

    internal fun uninstall() {
        instrumentation.removeTransformer(this)
        val classDefinitions = instrumentation.allLoadedClasses.mapNotNull { clazz ->
            val bytes = nonTransformedClasses[classKey(clazz.classLoader, clazz.name)]
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
                val transformedClasses = when (transformationMode) {
                    STRESS -> transformedClassesStress
                    MODEL_CHECKING -> transformedClassesModelChecking
                }
                return transformedClasses.computeIfAbsent(classKey(loader, className)) {
                    nonTransformedClasses[classKey(loader, className)] = classfileBuffer
                    val reader = ClassReader(classfileBuffer)
                    val writer = ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
                    try {
                        reader.accept(LincheckClassVisitor(transformationMode, writer), ClassReader.SKIP_FRAMES)
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

    @Suppress("SpellCheckingInspection")
    private fun shouldTransform(className: String): Boolean {
        if (className.startsWith("[")) return false
        if (className.contains("\$\$Lambda\$")) return false

        if (className.contains("ClassLoader")) return true
        if (className.startsWith("sun.nio.ch.lincheck.")) return false

        if (className.startsWith("kotlin.collections.")) return false
        if (className.startsWith("kotlinx.atomicfu.")) return false

        if (className.startsWith("net.bytebuddy.")) return false
        if (className.startsWith("io.mockk.")) return false
        if (className.startsWith("it.unimi.dsi.fastutil.")) return false
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
}

internal object TransformationInjectionsInitializer {
    private var initialized = false

    fun initialize() {
        if (initialized) return



        ModuleFinder.ofSystem().find("java.base").get().open().use { reader ->
            reader.list()
                // Filter classes
                .filter { it.endsWith(".class") && it != "module-info.class" }
                .map { it.removeSuffix(".class").replace("/", ".") }
                // Trampoline must not be defined by the bootstrap classloader
                .filter { it != "sun.reflect.misc.Trampoline" }
                .forEach {
                    try {
                        Class.forName(it)
                    } catch (t: Throwable) {
                        throw IllegalStateException("Cannot initialize class $it", t)
                    }
                }
        }

        val typePool: TypePool = TypePool.Default.ofSystemLoader()
        listOf(
            "kotlin.jvm.internal.Intrinsics",
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
        // The agent initialization is completed.
        initialized = true
    }
}

private fun classKey(loader: ClassLoader?, className: String) =
    if (loader == null) className
    else loader to className
