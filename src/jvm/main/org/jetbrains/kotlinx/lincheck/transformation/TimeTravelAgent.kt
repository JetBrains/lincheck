/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.jetbrains.kotlinx.lincheck.canonicalClassName
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain

object TimeTravelAgent {

    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        // install agent
        println("Installing Time Travelling Agent")

        inst.addTransformer(TimeTravelTransformer, true)
    }
}

internal object TimeTravelTransformer : ClassFileTransformer {
    private val classUnderTimeTravel = System.getProperty("rr.className") ?: error("Class name under time travel not found")
    private val methodUnderTimeTravel = System.getProperty("rr.methodName") ?: error("Method name under time travel not found")

    override fun transform(
        loader: ClassLoader?,
        internalClassName: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classBytes: ByteArray
    ): ByteArray? {
        println("Enter transform for $internalClassName")
        // If the class should not be transformed, return immediately.
        if (!shouldTransform(internalClassName.canonicalClassName)) {
            return null
        }
        return transformImpl(loader, internalClassName, classBytes)
    }

    private fun transformImpl(
        loader: ClassLoader?,
        internalClassName: String,
        classBytes: ByteArray
    ): ByteArray {
        println("Transforming class $internalClassName")
        try {
            val reader = ClassReader(classBytes)
            val writer = SafeClassWriter(reader, loader, ClassWriter.COMPUTE_FRAMES)
            reader.accept(
                TimeTravelClassVisitor(writer, classUnderTimeTravel, methodUnderTimeTravel),
                ClassReader.SKIP_FRAMES
            )
            return writer.toByteArray()
        } catch (e: Throwable) {
            println("Unable to transform '$internalClassName': $e")
            return classBytes
        }
    }


    // copy-paste from LincheckJavaAgent (maybe put in a separate static function)
    @Suppress("SpellCheckingInspection")
    fun shouldTransform(className: String): Boolean {
        // We do not need to instrument most standard Java classes.
        // It is fine to inject the Lincheck analysis only into the
        // `java.util.*` ones, ignored the known atomic constructs.
        if (className.startsWith("java.")) {
            if (className.startsWith("java.util.concurrent.") && className.contains("Atomic")) return false
            if (className.startsWith("java.util.")) return true
            if (className.startsWith("com.sun.")) return false
            return false
        }
        if (className.startsWith("sun.")) return false
        if (className.startsWith("javax.")) return false
        if (className.startsWith("jdk.")) return false
        // We do not need to instrument most standard Kotlin classes.
        // However, we need to inject the Lincheck analysis into the classes
        // related to collections, iterators, random and coroutines.
        if (className.startsWith("kotlin.")) {
            if (className.startsWith("kotlin.collections.")) return true
            if (className.startsWith("kotlin.jvm.internal.Array") && className.contains("Iterator")) return true
            if (className.startsWith("kotlin.ranges.")) return true
            if (className.startsWith("kotlin.random.")) return true
            if (className.startsWith("kotlin.coroutines.jvm.internal.")) return false
            if (className.startsWith("kotlin.coroutines.")) return true
            return false
        }
        if (className.startsWith("kotlinx.atomicfu.")) return false
        // We need to skip the classes related to the debugger support in Kotlin coroutines.
        if (className.startsWith("kotlinx.coroutines.debug.")) return false
        if (className == "kotlinx.coroutines.DebugKt") return false
        // We should never transform the coverage-related classes.
        if (className.startsWith("com.intellij.rt.coverage.")) return false
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
        if (className.startsWith("sun.nio.ch.lincheck.")) return false
        // All the classes that were not filtered out are eligible for transformation.
        return true
    }
}