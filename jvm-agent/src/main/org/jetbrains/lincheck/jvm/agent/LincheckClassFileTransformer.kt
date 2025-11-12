/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.jetbrains.lincheck.jvm.agent.InstrumentationMode.*
import org.jetbrains.lincheck.jvm.agent.LincheckJavaAgent.instrumentationStrategy
import org.jetbrains.lincheck.jvm.agent.LincheckJavaAgent.instrumentationMode
import org.jetbrains.lincheck.jvm.agent.LincheckJavaAgent.instrumentedClasses
import org.jetbrains.lincheck.jvm.agent.analysis.controlflow.BasicBlockControlFlowGraph
import org.jetbrains.lincheck.jvm.agent.analysis.*
import org.jetbrains.lincheck.util.*
import org.objectweb.asm.*
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.util.TraceClassVisitor
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import java.util.SortedSet
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
        if (instrumentationStrategy == InstrumentationStrategy.LAZY &&
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

        val (includeClasses, excludeClasses) = if (instrumentationMode == TRACE_RECORDING) {
            TraceAgentParameters.getIncludePatterns() to TraceAgentParameters.getExcludePatterns()
        } else {
            emptyList<String>() to emptyList<String>()
        }
        val profile = createTransformationProfile(
            instrumentationMode,
            includeClasses = includeClasses,
            excludeClasses = excludeClasses,
        )

        // Don't use class/method visitors on classNode to collect labels, as
        // MethodNode reset all labels on a re-visit (WHY?!).
        // Only one visit is possible to have labels stable.
        // Visiting components like `MethodNode.instructions` is safe.
        val (lineRanges, linesToMethodNames) = getMethodsLineRanges(classNode)

        val classInfo = ClassInformation(
            smap = readClassSMAP(classNode, reader),
            locals = getMethodsLocalVariables(classNode, profile),
            labels = getMethodsLabels(classNode),
            methodsToLineRanges = lineRanges,
            linesToMethodNames = linesToMethodNames,
            basicCfgs = computeControlFlowGraphs(classNode, profile),
        )

        val writer = SafeClassWriter(reader, loader, ClassWriter.COMPUTE_FRAMES)
        val visitor = LincheckClassVisitor(writer, classInfo, instrumentationMode, profile)
        try {
            classNode.accept(visitor)
            writer.toByteArray().also { bytes ->
                if (dumpTransformedSources) {
                    dumpClassBytecode(classNode.name, bytes)
                }
            }
        } catch (e: Throwable) {
            System.err.println("Unable to transform $internalClassName")
            e.printStackTrace()
            classBytes
        }
    }

    private fun dumpClassBytecode(className: String, bytes: ByteArray?) {
        val cr = ClassReader(bytes)
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        cr.accept(TraceClassVisitor(pw), 0)
        File("build/transformedBytecode/$className.txt")
            .apply { parentFile.mkdirs() }
            .writeText(sw.toString())
    }

    private fun getMethodsLocalVariables(
        classNode: ClassNode, profile: TransformationProfile,
    ): Map<String, MethodVariables> {
        return classNode.methods.associateBy(
            keySelector = { m -> m.name + m.desc },
            valueTransform = { m ->
                val config = profile.getMethodConfiguration(classNode.name.toCanonicalClassName(), m.name, m.desc)
                mutableMapOf<Int, MutableList<LocalVariableInfo>>().also { map ->
                    m.localVariables?.forEach { local ->
                        val index = local.index
                        val type = Type.getType(local.desc)
                        val name = sanitizeVariableName(classNode.name, local.name, config, type) ?: return@forEach
                        val info = LocalVariableInfo(
                            name, local.index, type, local.start.label to local.end.label
                        )
                        map.getOrPut(index) { mutableListOf() }.add(info)
                    }
                }
            }
        )
        .mapValues { MethodVariables(it.value) }
    }

    private fun sanitizeVariableName(owner: String, originalName: String, config: TransformationConfiguration, type: Type): String? {
        fun callRecursive(originalName: String) = sanitizeVariableName(owner, originalName, config, type)

        fun callRecursiveForSuffixAfter(prefix: String): String =
            "$prefix${callRecursive(originalName.removePrefix(prefix))}"

        fun callRecursiveForPrefixBefore(suffix: String): String =
            "${callRecursive(originalName.removeSuffix(suffix))}$suffix"

        return when {
            originalName.startsWith($$"$i$a$-") -> if (config.trackInlineMethodCalls) {
                val firstSuffix = originalName.substringAfter($$"$i$a$-")
                val prefix =
                    if (firstSuffix.contains('-')) $$"$i$a$-$${firstSuffix.substringBefore('-')}-"
                    else $$"$i$a$-"
                callRecursiveForSuffixAfter(prefix)
            } else {
                null
            }
            originalName.startsWith($$"$i$f$") ->
                if (config.trackInlineMethodCalls) callRecursiveForSuffixAfter($$"$i$f$") else null
            originalName.endsWith($$"$iv") ->
                if (config.trackInlineMethodCalls) callRecursiveForPrefixBefore($$"$iv")
                else callRecursive(originalName.removeSuffix($$"$iv"))

            originalName.contains('-') -> callRecursive(originalName.substringBeforeLast('-'))
            originalName.contains("_u24lambda_u24") ->
                callRecursive(originalName.replace("_u24lambda_u24", $$"$lambda$"))
            else -> originalName
        }
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


    /**
     * Computes basic-block control-flow graphs for each concrete method of the given class.
     * For abstract/native or empty methods, returns an empty graph.
     */
    private fun computeControlFlowGraphs(
        classNode: ClassNode,
        profile: TransformationProfile,
    ): Map<String, BasicBlockControlFlowGraph> {
        return classNode.methods.mapNotNull { m ->
            val config = profile.getMethodConfiguration(classNode.name.toCanonicalClassName(), m.name, m.desc)
            if (!config.trackLoops) return@mapNotNull null

            val key = m.name + m.desc
            val isAbstractOrNative = (m.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE)) != 0
            val cfg = if (isAbstractOrNative) {
                emptyControlFlowGraph(classNode.name, m)
            } else {
                buildControlFlowGraph(classNode.name, m)
            }
            cfg.computeLoopInformation()
            key to cfg
        }.toMap()
    }

    private val NESTED_LAMBDA_RE = Regex($$"^([^$]+)\\$lambda\\$")
    /*
     * Collect all line numbers of all methods.
     * Some line numbers could be beyond source file line count and need to be mapped.
     * Sort all methods by first line (we believe it is true first line) and truncate all
     * lines beyond next method first line.
     *
     * It doesn't work for last method, but it is better than nothing
     */
    private fun getMethodsLineRanges(
        classNode: ClassNode
    ): Pair<Map<String, Pair<Int, Int>>, List<Triple<Int, Int, Set<String>>>> {
        fun isSetterGetterPair(a: String, b: String): Boolean {
            return a.length == b.length
                    && a.length > 3
                    && (
                           (a.startsWith("set") && b.startsWith("get"))
                        || (a.startsWith("get") && b.startsWith("set"))
                       )
                    && a.substring(3) == b.substring(3)
        }

        val allMethods = mutableListOf<Triple<String, String, SortedSet<Int>>>()
        classNode.methods.forEach { m ->
            val extractor = LinesCollectorMethodVisitor()
            m.instructions.accept(extractor)
            if (extractor.allLines.isNotEmpty()) {
                allMethods.add(Triple(m.name, m.desc, extractor.allLines))
            }
        }
        if (allMethods.isEmpty()) {
            return emptyMap<String, Pair<Int, Int>>() to emptyList()
        }

        // Remove all lambda-methods (non inlined lambdas), as they
        // are nested to normal methods and should be covered by
        // enclosing method, because logically it is code in
        // enclosing method
        val allMethodNames = allMethods.map { it.first }.toSet()
        allMethods.removeAll {
            val (name, _, _) = it
            val match = NESTED_LAMBDA_RE.find(name) ?: return@removeAll false
            val enclosingName = match.groupValues.getOrNull(1)
            return@removeAll allMethodNames.contains(enclosingName)
        }

        // Sort all remaining methods by start line
        allMethods.sortBy { it.third.first() }

        // Special case: on-line setter and getter for same name can share this line
        for (i in 0 ..< allMethods.size - 1) {
            val (curName, _, curLines) = allMethods[i]
            val (nxtName, _, nxtLines) = allMethods[i + 1]
            if (isSetterGetterPair(
                    curName,
                    nxtName
                ) && curLines.size == 1 && nxtLines.size == 1 && curLines == nxtLines
            ) {
                continue
            }
            curLines.tailSet(nxtLines.first()).clear()
        }

        val methodsToLines = allMethods.associateBy(
            keySelector = { it.first + it.second },
            valueTransform = {
                (it.third.firstOrNull() ?: 0) to (it.third.lastOrNull() ?: 0)
            }
        )

        val linesToMethodNames =  allMethods
            .filter { (it.third.firstOrNull() ?: 0) > 0 && (it.third.lastOrNull() ?: 0) > 0 }
            .groupBy(
                keySelector = { it.third.first() to it.third.last() },
                valueTransform = { it.first }
            )
            .map {
                val (k, v) = it
                Triple(k.first, k.second, v.toSet())
            }
        linesToMethodNames.sortedWith { a, b ->  a.first.compareTo(b.first) }

        return methodsToLines to linesToMethodNames
    }

    private const val CONSTANT_UTF8_TAG = 1
    private const val SMAP_START = "SMAP\n"
    private const val SMAP_END = "*E\n"

    /**
     *  This function trys to get SMAP (SourceDebugExtension, SDE, JSR45) from parsed class.
     * It try official SourceDebugExtension first. It could fail, as JVM strips it
     * together with invisible annotations when runs without debugger attached.
     *
     *  Kotlin compiler saves its SMAP twice: as proper SourceDebugExtension attribute and
     * as value of RuntimeInvisibleAnnotation. Again, RuntimeInvisibleAnnotation are stripped by JVM
     * if there is no debugger attached, but its value still lives in constant pool.
     *
     *  This code trys to find SMAP in constant pool as a last resort (see ticket KT-53438).
     */
    private fun readClassSMAP(classNode: ClassNode, classReader: ClassReader): SMAPInfo {
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
