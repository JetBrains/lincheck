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

import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.INSTRUMENT_ALL_CLASSES
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.instrumentationMode
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.instrumentedClasses
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode.*
import org.jetbrains.lincheck.util.*
import org.objectweb.asm.*
import org.objectweb.asm.tree.ClassNode
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import java.util.concurrent.ConcurrentHashMap
import java.io.StringWriter
import java.io.PrintWriter
import java.io.File

object LincheckClassFileTransformer : ClassFileTransformer {
    /*
     * In order not to transform the same class several times,
     * Lincheck caches the transformed bytes in this object.
     * Notice that the transformation depends on the [InstrumentationMode].
     * Additionally, this object caches bytes of non-transformed classes.
     */
    val transformedClassesModelChecking = ConcurrentHashMap<String, ByteArray>()
    val transformedClassesStress = ConcurrentHashMap<String, ByteArray>()
    val transformedClassesTraceRecroding = ConcurrentHashMap<String, ByteArray>()

    private val transformedClassesCache
        get() = when (instrumentationMode) {
            STRESS -> transformedClassesStress
            MODEL_CHECKING -> transformedClassesModelChecking
            TRACE_RECORDING -> transformedClassesTraceRecroding
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
            (instrumentationMode == MODEL_CHECKING || instrumentationMode == TRACE_RECORDING) &&
            // do not re-transform already instrumented classes
            internalClassName.toCanonicalClassName() !in instrumentedClasses &&
            // always transform eagerly instrumented classes
            !isEagerlyInstrumentedClass(internalClassName.toCanonicalClassName())
        ) {
            return null
        }
        return transformImpl(loader, internalClassName, classBytes)
    }

    fun transformImpl(
        loader: ClassLoader?,
        internalClassName: String,
        classBytes: ByteArray
    ): ByteArray = transformedClassesCache.computeIfAbsent(internalClassName.toCanonicalClassName()) {
        Logger.debug { "Transforming $internalClassName" }

        val reader = ClassReader(classBytes)

        // the following code is required for local variables access tracking
        val classNode = ClassNode()
        reader.accept(classNode, ClassReader.EXPAND_FRAMES)

        // Don't use class/method visitors on classNode to collect labels, as
        // MethodNode reset all labels on a re-visit (WHY?!).
        // Only one visit is possible to have labels stable.
        // Visiting components like `MethodNode.instructions` is safe.
        val methodVariables = getMethodsLocalVariables(classNode)
        val methodLabels = getMethodsLabels(classNode)

        val writer = SafeClassWriter(reader, loader, ClassWriter.COMPUTE_FRAMES)
        val visitor = LincheckClassVisitor(writer, instrumentationMode, methodVariables, methodLabels)
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

    private fun getMethodsLocalVariables(
        classNode: ClassNode
    ): Map<String, MethodVariables> {
        return classNode.methods.associateBy(
            keySelector = { m -> m.name + m.desc },
            valueTransform = { m ->
                mutableMapOf<Int, MutableList<LocalVariableInfo>>().also { map ->
                    m.localVariables?.forEach { local ->
                        if (
                            m.name == "<init>" &&
                            isCoroutineStateMachineClass(classNode.name) &&
                            local.name.isOuterReceiverName()
                        ) return@forEach

                        val index = local.index
                        val type = Type.getType(local.desc)
                        val info = LocalVariableInfo(local.name, local.index, type,
                            local.start.label to local.end.label
                        )
                        map.getOrPut(index) { mutableListOf() }.add(info)
                    }
                }
            }
        )
        .mapValues { MethodVariables(it.value) }
    }

    private fun getMethodsLabels(
        classNode: ClassNode
    ): Map<String, MethodLabels> {
        return classNode.methods.associateBy(
            keySelector = { m -> m.name + m.desc },
            valueTransform = { m ->
                mutableMapOf<Label, Int>().also { map ->
                    val extractor = LabelCollectorMethodVisitor(map)
                    m.instructions.accept(extractor)
                }
            }
        )
        .mapValues { MethodLabels(it.value) }
    }

    private fun String.isOuterReceiverName() = this == "this$0"


    @Suppress("SpellCheckingInspection")
    fun shouldTransform(className: String, instrumentationMode: InstrumentationMode): Boolean {
        // In the stress testing mode, we can simply skip the standard
        // Java and Kotlin classes -- they do not have coroutine suspension points.
        if (instrumentationMode == STRESS) {
            if (className.startsWith("java.") || className.startsWith("kotlin.")) return false
        }
        if (instrumentationMode == TRACE_RECORDING) {
            if (className == "java.lang.Thread") return true
            if (className.startsWith("java.") || className.startsWith("kotlin.") || className.startsWith("jdk.")) return false
        }
        if (isEagerlyInstrumentedClass(className)) return true

        return AnalysisProfile(analyzeStdLib = true).shouldTransform(className, "")
    }


    // We should always eagerly transform the following classes.
    internal fun isEagerlyInstrumentedClass(className: String): Boolean =
        // `ClassLoader` classes, to wrap `loadClass` methods in the ignored section.
        (instrumentationMode != TRACE_RECORDING && isClassLoaderClassName(className)) ||
        // `MethodHandle` class, to wrap its methods (except `invoke` methods) in the ignored section.
        (instrumentationMode != TRACE_RECORDING && isMethodHandleRelatedClass(className)) ||
        // `StackTraceElement` class, to wrap all its methods into the ignored section.
        isStackTraceElementClass(className) ||
        // IntelliJ runtime agents, to wrap all their methods into the ignored section.
        isIntellijRuntimeAgentClass(className) ||
        // `ThreadContainer` classes, to detect threads started in the thread containers.
        isThreadContainerClass(className) ||
        // TODO: instead of eagerly instrumenting `DispatchedContinuation`
        //  we should try to fix lazy class re-transformation logic
        isCoroutineDispatcherInternalClass(className) ||
        isCoroutineConcurrentKtInternalClass(className)
}
