/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.bytecodeinfo

import org.jetbrains.lincheck.descriptors.LocalKind
import org.jetbrains.lincheck.jvm.agent.TransformationConfiguration
import org.jetbrains.lincheck.jvm.agent.analysis.controlflow.buildControlFlowGraph
import org.jetbrains.lincheck.jvm.agent.analysis.controlflow.BasicBlockControlFlowGraph
import org.jetbrains.lincheck.jvm.agent.analysis.controlflow.emptyControlFlowGraph
import org.jetbrains.lincheck.settings.BlockMatch
import org.jetbrains.lincheck.settings.blocklist.BlocklistEngine
import org.jetbrains.lincheck.trace.isThisName
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.MethodNode
import java.util.SortedSet

/**
 *  This class consolidates all information about one method extracted after
 * class reading and pre-processed to be useful for method transformers.
 *
 *  Now it contains:
 *
 *   - [smap] - SMAP read from the class file or empty mapper. It is shared among all methods.
 *   - [locals] - Locals of this method.
 *   - [labels] - Comparator for all method's labels.
 *   - [lineRange] - Approximate Range of lines in source file covered by this method.
 *   - [linesToMethodNames] - Sorted list of all known line numbers ranges and method names (without `desc`) for these ranges.
 *   - [nonSyntheticMethodLines] - All source lines found in non-synthetic methods of this class.
 *   - [blockMatch] - Sensitive-area blocklist rule blocking this method (whole-class or per-method), or `null`.
 *       Only computed for classes with applicable snapshot breakpoints.
 */
internal data class MethodInformation(
    val smap: SMAPInfo,
    val locals: MethodVariables,
    val labels: MethodLabels,
    val lineRange: Pair<Int, Int>,
    private val linesToMethodNames: List<MethodsLineRange>,
    val nonSyntheticMethodLines: Set<Int>,
    val basicControlFlowGraph: BasicBlockControlFlowGraph?,
    val blockMatch: BlockMatch?,
) {
    // TODO: This method should be used by [LincheckBaseMethodVisitor],
    //  but now it leads to flaky tests on TeamCity.
    fun findMethodByLine(line: Int, currentMethodName: String): String {
        val idx = linesToMethodNames.binarySearch {
            when {
                line < it.firstLine -> +1
                line > it.lastLine -> -1
                else -> 0
            }
        }
        if (idx < 0) return UNKNOWN_METHOD_MARKER
        val allNames = linesToMethodNames[idx].methodNames
        if (allNames.size == 1) return allNames.first()
        if (allNames.contains(currentMethodName)) return currentMethodName
        return UNKNOWN_METHOD_MARKER
    }
}

/**
 * Source lines range `[firstLine, lastLine]` and names of the methods covering exactly this range.
 *
 * @property firstLine first non-zero source line of the range, inclusive.
 * @property lastLine last non-zero source line of the range, inclusive.
 * @property methodNames names (without `desc`) of all methods covering this range.
 *   Several methods can share one range, e.g., overloads or a one-line getter/setter pair;
 *   the names are deduplicated, so that overloads with the same name count as one method
 *   and [MethodInformation.findMethodByLine] can resolve their lines unambiguously.
 */
internal data class MethodsLineRange(
    val firstLine: Int,
    val lastLine: Int,
    val methodNames: Set<String>,
)

internal fun getMethodLocalVariables(
    className: String,
    methodNode: MethodNode,
    config: TransformationConfiguration,
): MethodVariables {
    val map = LocalVariablesMutableMap()
    methodNode.localVariables?.forEach { local ->
        val index = local.index
        val type = Type.getType(local.desc)
        val name = sanitizeVariableName(className, local.name, config, type) ?: return@forEach
        val localKind = computeLocalKind(name, index, methodNode)
        val info = LocalVariableInfo(
            name, local.index, type, local.start.label to local.end.label, localKind
        )
        map.getOrPut(index) { mutableListOf() }.add(info)
    }
    return MethodVariables(map)
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

internal fun getMethodLabels(methodNode: MethodNode): MethodLabels {
    val labels = mutableMapOf<Label, Int>()
    val jumpTargets = mutableSetOf<Label>()
    val extractor = LabelCollectorMethodVisitor(labels, jumpTargets)
    methodNode.instructions.accept(extractor)
    val catches = methodNode.tryCatchBlocks.map { it.handler.label }.toSet()
    return MethodLabels(labels, catches, jumpTargets)
}

/**
 * Collects all non-zero source lines referenced by LINENUMBER instructions of [methodNode].
 * Returns a fresh set on each call, so callers may mutate it.
 */
internal fun getMethodLines(methodNode: MethodNode): SortedSet<Int> {
    val extractor = LinesCollectorMethodVisitor()
    methodNode.instructions.accept(extractor)
    return extractor.allLines
}

/**
 * Computes the basic-block control-flow graph of [methodNode] with loop information,
 * or returns `null` if loops are not tracked by [config].
 * For abstract/native methods, returns an empty graph.
 */
internal fun computeControlFlowGraph(
    className: String,
    methodNode: MethodNode,
    config: TransformationConfiguration,
): BasicBlockControlFlowGraph? {
    if (!config.trackLoops) return null
    val isAbstractOrNative = (methodNode.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE)) != 0
    val cfg = if (isAbstractOrNative) {
        emptyControlFlowGraph(className, methodNode)
    } else {
        buildControlFlowGraph(className, methodNode)
    }
    cfg.computeLoopInformation(computeIrreducibleLoops = config.trackIrreducibleLoops)
    return cfg
}

/**
 * Computes the per-method sensitive-area blocklist verdict (Stage 2) of [methodNode].
 */
internal fun computeMethodBlockMatch(
    canonicalClassName: String,
    methodNode: MethodNode,
    blocklistEngine: BlocklistEngine,
): BlockMatch? {
    // The visitor never transforms native methods; mirror its skip to avoid needless matching.
    if ((methodNode.access and Opcodes.ACC_NATIVE) != 0) return null
    return blocklistEngine.methodBlock(canonicalClassName, methodNode.name, methodNode.desc)
}

internal const val UNKNOWN_METHOD_MARKER = "<unknown method>"