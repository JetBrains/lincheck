/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent

import org.jetbrains.lincheck.descriptors.LocalKind
import org.jetbrains.lincheck.jvm.agent.analysis.buildControlFlowGraph
import org.jetbrains.lincheck.jvm.agent.analysis.controlflow.BasicBlockControlFlowGraph
import org.jetbrains.lincheck.jvm.agent.analysis.emptyControlFlowGraph
import org.jetbrains.lincheck.trace.isThisName
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import java.util.SortedSet

/**
 * This class consolidates all information about a class
 * extracted after class reading and pre-processed to be useful for method transformers.
 *
 *  Contains:
 *
 *   - [smap] - SMAP read from the class file or empty mapper.
 *   - [locals] - Locals of all methods, indexed by `"$methodName$methodDesc"`.
 *   - [labels] - Comparator for all methods' labels, indexed by `"$methodName$methodDesc"`.
 *   - [methodsToLineRanges] - Pair of first and last non-zero LINENUMBER in method.
 *       Not min and max, as max can be mapped later to another place.
 *   - [linesToMethodNames] - Sorted list of all known line numbers ranges and method names (without `desc`) for these ranges.
 *   - [nonSyntheticMethodLines] - All source lines found in non-synthetic methods of this class.
 */
internal data class ClassInformation(
    private val smap: SMAPInfo,
    private val locals: Map<String, MethodVariables>,
    private val labels: Map<String, MethodLabels>,
    private val methodsToLineRanges: Map<String, Pair<Int, Int>>,
    private val linesToMethodNames: List<Triple<Int, Int, Set<String>>>,
    private val nonSyntheticMethodLines: Set<Int>,
    private val basicCfgs: Map<String, BasicBlockControlFlowGraph>,
) {
    /**
     * Returns [MethodInformation] for given method.
     */
    fun methodInformation(methodName: String, methodDesc: String): MethodInformation =
        MethodInformation(
            smap = smap,
            locals = locals[methodName + methodDesc] ?: MethodVariables.EMPTY,
            labels = labels[methodName + methodDesc] ?: MethodLabels.EMPTY,
            lineRange = methodsToLineRanges[methodName + methodDesc] ?: (0 to 0),
            linesToMethodNames = linesToMethodNames,
            nonSyntheticMethodLines = nonSyntheticMethodLines,
            basicControlFlowGraph = basicCfgs[methodName + methodDesc]
        )
}

/**
 * Assembles a [ClassInformation] for the class described by [classNode] / [classReader],
 * pre-processed against the given [profile].
 *
 * Don't use class/method visitors on [classNode] to collect labels:
 * [MethodNode] resets all labels on a re-visit.
 * Only one visit is possible to have labels stable.
 * Visiting components like `MethodNode.instructions` is safe.
 */
internal fun buildClassInformation(
    classNode: ClassNode,
    classReader: ClassReader,
    profile: TransformationProfile,
): ClassInformation {
    val (lineRanges, linesToMethodNames) = getMethodsLineRanges(classNode)
    return ClassInformation(
        smap = readClassSMAP(classNode, classReader),
        locals = getMethodsLocalVariables(classNode, profile),
        labels = getMethodsLabels(classNode),
        methodsToLineRanges = lineRanges,
        linesToMethodNames = linesToMethodNames,
        nonSyntheticMethodLines = getNonSyntheticMethodLines(classNode),
        basicCfgs = computeControlFlowGraphs(classNode, profile),
    )
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
                    val localKind = computeLocalKind(name, index, m)
                    val info = LocalVariableInfo(
                        name, local.index, type, local.start.label to local.end.label, localKind
                    )
                    map.getOrPut(index) { mutableListOf() }.add(info)
                }
            }
        }
    )
    .mapValues { MethodVariables(it.value) }
}

private fun computeLocalKind(name: String, index: Int, methodNode: MethodNode): LocalKind {
    val isStatic = (methodNode.access and Opcodes.ACC_STATIC) != 0
    val parameterSlotCount = Type.getArgumentTypes(methodNode.desc).sumOf { it.size }
    val firstLocalVarIndex = parameterSlotCount + if (isStatic) 0 else 1
    return when {
        isThisName(name) -> LocalKind.THIS
        index < firstLocalVarIndex -> LocalKind.PARAMETER
        else -> LocalKind.VARIABLE
    }
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
            val labels = mutableMapOf<Label, Int>()
            val jumpTargets = mutableSetOf<Label>()
            val extractor = LabelCollectorMethodVisitor(labels, jumpTargets)
            m.instructions.accept(extractor)
            val catches = m.tryCatchBlocks.map { it.handler.label }.toSet()
            MethodLabels(labels, catches, jumpTargets)
        }
    )
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
        cfg.computeLoopInformation(computeIrreducibleLoops = config.trackIrreducibleLoops)
        key to cfg
    }.toMap()
}

/**
 * Collects all source lines referenced by any non-synthetic-lambda method of [classNode].
 */
private fun getNonSyntheticMethodLines(classNode: ClassNode): Set<Int> {
    return buildSet {
        classNode.methods.forEach { method ->
            if (isSyntheticLambdaMethod(method.access, method.name)) return@forEach

            val extractor = LinesCollectorMethodVisitor()
            method.instructions.accept(extractor)
            addAll(extractor.allLines)
        }
    }
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

private fun readUTF(classReader: ClassReader, utfOffset: Int, utfLength: Int, buffer: ByteArray): String {
    for (offset in 0 ..< utfLength) {
        buffer[offset] = (classReader.readByte(offset + utfOffset) and 0xff).toByte()
    }
    return String(buffer, 0, utfLength, Charsets.UTF_8)
}
