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

import net.bytebuddy.agent.*
import org.jetbrains.kotlinx.lincheck.*
import org.objectweb.asm.*
import java.lang.instrument.*
import java.security.*
import java.util.*
import java.util.Collections.*
import java.util.concurrent.*
import java.util.jar.*

internal inline fun <R> withLincheckJavaAgent(transformationMode: TransformationMode, block: () -> R): R {
    Class.forName("kotlin.text.StringsKt")
    Class.forName("kotlin.jvm.internal.ArrayIteratorKt")
    Class.forName("kotlin.jvm.internal.ArrayIterator")

    LincheckClassFileTransformer.install(transformationMode)
    return try {
        block()
    } finally {
        LincheckClassFileTransformer.uninstall()
    }
}

object LincheckClassFileTransformer : ClassFileTransformer {
    private val transformedClassesModelChecking = ConcurrentHashMap<Any, ByteArray>()
    private val transformedClassesStress = ConcurrentHashMap<Any, ByteArray>()
    private val nonTransformedClasses = ConcurrentHashMap<Any, ByteArray>()

    private val failedToRetransformClasses = newSetFromMap<Class<*>>(ConcurrentHashMap())

    private val instrumentation = ByteBuddyAgent.install()

    private var transformationMode = TransformationMode.STRESS

    private val instrumentedClasses = HashSet<String>()

    fun ensureClassAndAllSuperclassesAreInstrumented(className: String) {
        if (className in instrumentedClasses) return // already instrumented
        ensureClassAndAllSuperclassesAreInstrumentedImpl(Class.forName(className))
    }

    fun ensureClassAndAllSuperclassesAreInstrumented(clazz: Class<*>) {
        if (clazz.name in instrumentedClasses) return // already instrumented
        ensureClassAndAllSuperclassesAreInstrumentedImpl(clazz)
    }

    private fun ensureClassAndAllSuperclassesAreInstrumentedImpl(clazz: Class<*>) {
        if (instrumentation.isModifiableClass(clazz) && shouldTransform(clazz.name, transformationMode)) {
            instrumentedClasses += clazz.name
            println("Retransform $clazz")
            instrumentation.retransformClasses(clazz)
        }
        clazz.superclass?.let { ensureClassAndAllSuperclassesAreInstrumented(it) }
    }

    fun ensureAllTestInstanceFieldsAreTransformed(testInstance: Any) =
        ensureAllTestInstanceFieldsAreTransformed(testInstance, Collections.newSetFromMap(IdentityHashMap()))

    private fun ensureAllTestInstanceFieldsAreTransformed(obj: Any, processedObjects: MutableSet<Any>) {
        if (processedObjects.contains(obj)) return
        ensureClassAndAllSuperclassesAreInstrumented(obj.javaClass)
        processedObjects += obj
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            clazz.declaredFields.filter { !it.type.isPrimitive }.forEach { field ->
                if (field.isAccessible || field.trySetAccessible()) {
                    val fieldValue = field.get(obj)
                    if (fieldValue != null) {
                        ensureAllTestInstanceFieldsAreTransformed(fieldValue, processedObjects)
                    }
                }
            }
            clazz = clazz.superclass
        }
    }

    internal fun install(transformationMode: TransformationMode) {
        this.transformationMode = transformationMode
        TransformationInjectionsInitializer.initialize(instrumentation)
        instrumentation.addTransformer(this@LincheckClassFileTransformer, true)
        if (transformationMode == TransformationMode.STRESS) {
            instrumentation.retransformClasses(*getLoadedClassesToInstrument().toTypedArray())
        }
    }

    private fun getLoadedClassesToInstrument() = instrumentation.allLoadedClasses
        .filter(instrumentation::isModifiableClass)
        .filter { shouldTransform(it.name, transformationMode) }
        .filter { it !in failedToRetransformClasses }

    internal fun uninstall() {
        if (System.getProperty("INTERNAL_TESTS") == "true") {
            val transformedClassesNames = transformedClassCache.keys
                .map { if (it is Pair<*, *>) it.second.toString() else it.toString() }
                .map { it.replace("/", ".").replace("\$", "\\\$") }
                .toHashSet()

            val loadedClasses = if (transformationMode == TransformationMode.STRESS) {
                getLoadedClassesToInstrument()
            } else {
                getLoadedClassesToInstrument().filter { it.name in instrumentedClasses }
            }
        }
        instrumentation.removeTransformer(this)
        val classDefinitions = getLoadedClassesToInstrument().filter {
            if (transformationMode == TransformationMode.MODEL_CHECKING) {
                it.name in instrumentedClasses
            } else {
                true
            }
        }.mapNotNull { clazz ->
            val bytes = nonTransformedClasses[classKey(clazz.classLoader, clazz.name)]
            bytes?.let { ClassDefinition(clazz, it) }
        }
        instrumentation.redefineClasses(*classDefinitions.toTypedArray())
        instrumentedClasses.clear()
    }

    override fun transform(
        loader: ClassLoader?,
        className: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classBytes: ByteArray
    ): ByteArray? {
        runInIgnoredSection {
            if (transformationMode == TransformationMode.STRESS) {
                if (!shouldTransform(className.canonicalClassName, transformationMode)) return null
            } else {
                if (className.canonicalClassName !in instrumentedClasses) return null
            }
            return transformImpl(loader, className, classBytes)
        }
    }

    private fun transformImpl(loader: ClassLoader?, className: String, classBytes: ByteArray): ByteArray =
        transformedClassCache.computeIfAbsent(classKey(loader, className)) {
            nonTransformedClasses[classKey(loader, className)] = classBytes
            val reader = ClassReader(classBytes)
            val writer = SafeClassWriter(reader, loader, ClassWriter.COMPUTE_FRAMES)
            try {
                reader.accept(LincheckClassVisitor(transformationMode, writer), ClassReader.SKIP_FRAMES)
                writer.toByteArray()
            } catch (e: Throwable) {
                System.err.println("Unable to transform $className")
                e.printStackTrace()
                classBytes
            }
        }

    private val transformedClassCache
        get() = when (transformationMode) {
            TransformationMode.STRESS -> transformedClassesStress
            TransformationMode.MODEL_CHECKING -> transformedClassesModelChecking
        }

    @Suppress("SpellCheckingInspection")
    private fun shouldTransform(className: String, transformationMode: TransformationMode): Boolean {
        if (transformationMode == TransformationMode.STRESS) {
            if (className.startsWith("java.") || className.startsWith("kotlin.")) return false
        }

        if (className.startsWith("net.bytebuddy.")) return false
        if (className.startsWith("io.mockk.")) return false
        if (className.startsWith("it.unimi.dsi.fastutil.")) return false
        if (className.startsWith("kotlinx.atomicfu.")) return false
        if (className.startsWith("worker.org.gradle.")) return false
        if (className.startsWith("org.objectweb.asm.")) return false
        if (className.startsWith("org.junit.")) return false
        if (className.startsWith("junit.framework.")) return false
        if (className.startsWith("com.sun.")) return false
        if (className.startsWith("java.util.")) {
            if (className.startsWith("java.util.concurrent.atomic.") && className.contains("Atomic")) return false
            if (className.endsWith("Exception")) return false
            return true
        }

        if (className.startsWith("org.jetbrains.kotlinx.lincheck.ExecutionClassLoader")) return false

        if (className.startsWith("sun.") ||
            className.startsWith("java.") ||
            className.startsWith("javax.") ||
            className.startsWith("jdk.") ||
            className.startsWith("org.jetbrains.kotlinx.lincheck.") ||
            className.startsWith("sun.nio.ch.lincheck.") ||
            className == "kotlinx.coroutines.DebugKt" ||
            className == "kotlin.coroutines.jvm.internal.DebugProbesKt"
        ) return false

        return true
    }
}

internal object TransformationInjectionsInitializer {
    private var initialized = false

    fun initialize(instrumentation: Instrumentation) {
        if (initialized) return

        val bootstrapJarFile = JarFile(ClassLoader.getSystemResource("bootstrap.jar").file)
        instrumentation.appendToBootstrapClassLoaderSearch(bootstrapJarFile)

        initialized = true
    }
}

private fun classKey(loader: ClassLoader?, className: String) =
    if (loader == null) {
        className
    } else {
        className to loader
    }