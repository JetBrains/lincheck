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

import org.jetbrains.kotlinx.lincheck.transformation.SMAPInfo
import org.jetbrains.kotlinx.lincheck.transformation.InstrumentationMode.*
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.INSTRUMENT_ALL_CLASSES
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.instrumentationMode
import org.jetbrains.kotlinx.lincheck.transformation.LincheckJavaAgent.instrumentedClasses
import org.jetbrains.lincheck.util.*
import org.objectweb.asm.*
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import java.util.concurrent.ConcurrentHashMap

object LincheckClassFileTransformer : ClassFileTransformer {
    /*
     * In order not to transform the same class several times,
     * Lincheck caches the transformed bytes in this object.
     * Notice that the transformation depends on the [InstrumentationMode].
     * Additionally, this object caches bytes of non-transformed classes.
     */
    private val transformedClassesCachesByMode =
        ConcurrentHashMap<InstrumentationMode, ConcurrentHashMap<String, ByteArray>>()

    val transformedClassesCache
        get() = transformedClassesCachesByMode.computeIfAbsent(instrumentationMode) { ConcurrentHashMap() }

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
        // If lazy mode is used, transform classes lazily,
        // once they are used in the testing code.
        if (!INSTRUMENT_ALL_CLASSES && instrumentationMode.supportsLazyTransformation &&
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
        val metaInfo = ClassInformation(
            smap = getSMAP(classNode, reader),
            locals = getMethodsLocalVariables(classNode),
            labels = getMethodsLabels(classNode)
        )

        val writer = SafeClassWriter(reader, loader, ClassWriter.COMPUTE_FRAMES)
        val visitor = LincheckClassVisitor(writer, instrumentationMode, metaInfo)
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

    private const val CONSTANT_UTF8_TAG = 1
    private const val SMAP_START = "SMAP\n"
    private const val SMAP_END = "*E\n"

    private fun getSMAP(classNode: ClassNode, classReader: ClassReader): SMAPInfo {
        // Try to get SMAP for Kotlin easy way
        if (classNode.sourceDebug != null) {
            return SMAPInfo(classNode.sourceDebug)
        }

        // Try to get it from a constant pool, attribute `sourceDebugExtension` can be stripped down by JVM:
        // https://youtrack.jetbrains.com/issue/KT-53438
        // Unfortunately, `classNode.invisibleAnnotations` can be stripped too, so we need to
        // parse constant pool manually. Start from the end, as SMAP is written last by the kotlin compiler.
        var buffer: ByteArray? = null
        // Zero index in a constant pool is always 0, why?
        for (idx in classReader.itemCount - 1 downTo 1) {
            val offset = classReader.getItem(idx) - 1
            // Sometimes offset = 0 even in the middle of constant pool
            if (offset < 0) continue
            val tag = classReader.readByte(offset)
            // Check only UTF-8 tags
            if (tag != CONSTANT_UTF8_TAG) continue
            val len = classReader.readUnsignedShort(offset + 1)
            if (buffer == null || buffer.size < len) buffer = ByteArray(len)
            // We cannot call `ClassReader.readUTF8()` as it requires an offset to index, not to data
            // And `ClassReader.readUtf()` is package-private in ClassReader
            val str = readUTF(classReader, offset + 3, len, buffer)
            if (str.startsWith(SMAP_START) && str.endsWith(SMAP_END)) {
                return SMAPInfo(str)
            }
        }
        return SMAPInfo("")
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
            if (className.startsWith("kotlin.concurrent.ThreadsKt")) return true
            if (className.startsWith("java.") || className.startsWith("kotlin.") || className.startsWith("jdk.")) return false
        }
        if (isEagerlyInstrumentedClass(className)) return true

        return AnalysisProfile.DEFAULT.shouldTransform(className, "")
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

    private fun readUTF(classReader: ClassReader, utfOffset: Int, utfLength: Int, buffer: ByteArray): String {
        for (offset in 0 ..< utfLength) {
            buffer[offset] = (classReader.readByte(offset + utfOffset) and 0xff).toByte()
        }
        return String(buffer, 0, utfLength, Charsets.UTF_8)
    }

}
