/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck

import net.bytebuddy.ByteBuddy
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.dynamic.loading.ClassInjector
import net.bytebuddy.pool.TypePool
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import java.lang.instrument.ClassDefinition
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import kotlin.collections.set

internal object TransformationInjectionsInitializer {
    private var initialized = false

    @Synchronized
    fun initialize() {
        if (initialized) return
        val typePool: TypePool = TypePool.Default.ofSystemLoader()

        listOf(
            "sun.nio.ch.lincheck.Injections",
            "sun.nio.ch.lincheck.CodeLocations",
            "sun.nio.ch.lincheck.TestThread",
            "sun.nio.ch.lincheck.SharedEventsTracker",
            "sun.nio.ch.lincheck.DummySharedEventsTracker",
            "sun.nio.ch.lincheck.AtomicFieldNameMapper",
            "sun.nio.ch.lincheck.WeakIdentityHashMap",
            "sun.nio.ch.lincheck.WeakIdentityHashMap\$Ref",
            "kotlin.jvm.internal.Intrinsics",
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



object LincheckClassFileTransformer : ClassFileTransformer {
    private val transformedClasses = HashMap<Any, ByteArray>()
    private val oldClasses = HashMap<Any, ByteArray>()

    private val instrumentation = ByteBuddyAgent.install()

    fun install() {
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
    ): ByteArray = synchronized(LincheckClassFileTransformer) {
        if (!shouldTransform(className.canonicalClassName)) return classfileBuffer
        return transformedClasses.computeIfAbsent(classKey(loader, className)) {
            oldClasses[classKey(loader, className)] = classfileBuffer
            val reader = ClassReader(classfileBuffer)
            val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
            reader.accept(ManagedStrategyTransformer(writer), ClassReader.EXPAND_FRAMES)
            writer.toByteArray()
        }
    }

    private fun shouldTransform(className: String): Boolean {
        if (className == "kotlin.collections.ArraysKt___ArraysKt") return false
        if (className == "kotlin.collections.CollectionsKt___CollectionsKt") return false

        if (className.startsWith("sun.nio.ch.")) return false
        if (className.startsWith("org.gradle.")) return false
        if (className.startsWith("worker.org.gradle.")) return false
        if (className.startsWith("org.objectweb.asm.")) return false
        if (className.startsWith("net.bytebuddy.")) return false
        if (className.startsWith("org.junit.")) return false
        if (className.startsWith("junit.framework.")) return false
        if (className.startsWith("com.sun.tools.")) return false
        if (className.startsWith("java.util.")) {
            if (className.startsWith("java.util.zip")) return false
            if (className.startsWith("java.util.regex")) return false
            if (className.startsWith("java.util.jar")) return false
            if (className.startsWith("java.util.Immutable")) return false
            if (className.startsWith("java.util.logging")) return false
            if (className.startsWith("java.util.ServiceLoader")) return false
            if (className.startsWith("java.util.concurrent.atomic.")) return false
            if (className in NOT_TRANSFORMED_JAVA_UTIL_CLASSES) return false
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
            className.startsWith("org.jetbrains.kotlinx.lincheck.") &&
            !className.startsWith("org.jetbrains.kotlinx.lincheck.test.") ||
            className.startsWith("kotlinx.coroutines.DispatchedTask")
        ) return false

        return true
    }


}

private fun classKey(loader: ClassLoader?, className: String) =
    if (loader == null) className
    else loader to className