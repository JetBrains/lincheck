/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.jetbrains.kotlinx.lincheck.TraceDebuggerInjections
import org.jetbrains.kotlinx.lincheck.dumpTransformedSources
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode.MODEL_CHECKING
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode.STRESS
import org.jetbrains.kotlinx.lincheck.transformation.JavaAgent.INSTRUMENT_ALL_CLASSES
import org.jetbrains.kotlinx.lincheck.transformation.JavaAgent.instrumentationMode
import org.jetbrains.kotlinx.lincheck.transformation.JavaAgent.instrumentedClasses
import org.jetbrains.kotlinx.lincheck.util.AnalysisProfile
import org.jetbrains.kotlinx.lincheck.util.Logger
import org.jetbrains.kotlinx.lincheck.util.runInsideIgnoredSection
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import java.util.concurrent.ConcurrentHashMap


internal object LincheckClassFileTransformer : ClassFileTransformer {
    /*
     * In order not to transform the same class several times,
     * Lincheck caches the transformed bytes in this object.
     * Notice that the transformation depends on the [InstrumentationMode].
     * Additionally, this object caches bytes of non-transformed classes.
     */
    val transformedClassesModelChecking = ConcurrentHashMap<String, ByteArray>()
    val transformedClassesStress = ConcurrentHashMap<String, ByteArray>()

    private val transformedClassesCache
        get() = when (instrumentationMode) {
            STRESS -> transformedClassesStress
            MODEL_CHECKING -> transformedClassesModelChecking
        }

    override fun transform(
        loader: ClassLoader?,
        internalClassName: String?,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classBytes: ByteArray
    ): ByteArray? = runInsideIgnoredSection {
        if (classBeingRedefined != null) {
            require(internalClassName != null) {
                "Internal class name of redefined class ${classBeingRedefined.name} must not be null"
            }
        }
        // Internal class name could be `null` in some cases (can be witnessed on JDK-8),
        // this can be related to the Kotlin compiler bug:
        // - https://youtrack.jetbrains.com/issue/KT-16727/
        if (internalClassName == null) return null
        // If the class should not be transformed, return immediately.
        if (!shouldTransform(internalClassName.toCanonicalClassName(), instrumentationMode)) {
            return null
        }
        // In the model checking mode, we transform classes lazily,
        // once they are used in the testing code.
        if (!INSTRUMENT_ALL_CLASSES &&
            instrumentationMode == MODEL_CHECKING &&
            // do not re-transform already instrumented classes
            internalClassName.toCanonicalClassName() !in instrumentedClasses &&
            // always transform eagerly instrumented classes
            !isEagerlyInstrumentedClass(internalClassName.toCanonicalClassName())) {
            return null
        }
        return transformImpl(loader, internalClassName, classBytes)
    }

    internal fun transformImpl(
        loader: ClassLoader?,
        internalClassName: String,
        classBytes: ByteArray
    ): ByteArray = transformedClassesCache.computeIfAbsent(internalClassName.toCanonicalClassName()) {
        Logger.debug { "Transforming $internalClassName" }

        val reader = ClassReader(classBytes)

        // the following code is required for local variables access tracking
        val classNode = ClassNode()
        reader.accept(classNode, ClassReader.EXPAND_FRAMES)

        val methods = mapMethodsToLabels(classNode)
        val methodVariables = methods.mapValues { MethodVariables(it.value) }

        val writer = SafeClassWriter(reader, loader, ClassWriter.COMPUTE_FRAMES)
        val visitor = LincheckClassVisitor(writer, instrumentationMode, methodVariables)
        try {
            classNode.accept(visitor)
            writer.toByteArray().also {
                if (dumpTransformedSources) {
                    val cr = ClassReader(it)
                    val sw = StringWriter()
                    val pw = PrintWriter(sw)
                    cr.accept(org.objectweb.asm.util.TraceClassVisitor(pw), 0)

                    File("build/transformedBytecode/${classNode.name}.txt")
                        .apply { parentFile.mkdirs() }
                        .writeText(sw.toString())
                }
            }
        } catch (e: Throwable) {
            System.err.println("Unable to transform $internalClassName")
            e.printStackTrace()
            classBytes
        }
    }

    private fun mapMethodsToLabels(
        classNode: ClassNode
    ): Map<String, Map<Int, List<LocalVariableInfo>>> {
        return classNode.methods.associateBy(
            keySelector = { m -> m.name + m.desc },
            valueTransform = { m ->
                mutableMapOf<Int, MutableList<LocalVariableInfo>>().also { map ->
                    m.localVariables?.forEach { local ->
                        val index = local.index
                        val type = Type.getType(local.desc)
                        val info = LocalVariableInfo(local.name, local.index, local.start.label to local.end.label, type)
                        map.getOrPut(index) { mutableListOf() }.add(info)
                    }
                }
            }
        )
    }

    @Suppress("SpellCheckingInspection")
    fun shouldTransform(className: String, instrumentationMode: InstrumentationMode): Boolean {
        // In the stress testing mode, we can simply skip the standard
        // Java and Kotlin classes -- they do not have coroutine suspension points.
        if (instrumentationMode == STRESS) {
            if (className.startsWith("java.") || className.startsWith("kotlin.")) return false
        }
        if (isEagerlyInstrumentedClass(className)) return true

        return AnalysisProfile(analyzeStdLib = true).shouldTransform(className, "")
    }


    // We should always eagerly transform the following classes.
    internal fun isEagerlyInstrumentedClass(className: String): Boolean =
        // `ClassLoader` classes, to wrap `loadClass` methods in the ignored section.
        isClassLoaderClassName(className) ||
                // `MethodHandle` class, to wrap its methods (except `invoke` methods) in the ignored section.
                isMethodHandleRelatedClass(className) ||
                // `StackTraceElement` class, to wrap all its methods into the ignored section.
                isStackTraceElementClass(className) ||
                // `ThreadContainer` classes, to detect threads started in the thread containers.
                isThreadContainerClass(className) ||
                // TODO: instead of eagerly instrumenting `DispatchedContinuation`
                //  we should try to fix lazy class re-transformation logic
                isCoroutineDispatcherInternalClass(className) ||
                isCoroutineConcurrentKtInternalClass(className)
}

internal object TraceDebuggerTransformer : ClassFileTransformer {

    override fun transform(
        loader: ClassLoader?,
        internalClassName: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classBytes: ByteArray
    ): ByteArray? {
        // If the class should not be transformed, return immediately.
        if (TraceDebuggerInjections.classUnderTraceDebugging != internalClassName.toCanonicalClassName()) {
            return null
        }
        println("Transforming by trace debugger: $internalClassName")
        return transformImpl(loader, internalClassName, classBytes)
    }

    private fun transformImpl(
        loader: ClassLoader?,
        internalClassName: String,
        classBytes: ByteArray
    ): ByteArray {
        try {
            val bytes: ByteArray
            val reader = ClassReader(classBytes)
            val writer = SafeClassWriter(reader, loader, ClassWriter.COMPUTE_FRAMES)

            reader.accept(
                TraceDebuggerClassVisitor(writer),
                ClassReader.SKIP_FRAMES
            )
            bytes = writer.toByteArray()

            return bytes
        } catch (e: Throwable) {
            System.err.println("Unable to transform '$internalClassName': $e")
            return classBytes
        }
    }
}