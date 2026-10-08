/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.bytecodeinfo

import org.jetbrains.lincheck.jvm.agent.TransformationProfile
import org.jetbrains.lincheck.jvm.agent.isSyntheticLambdaMethod
import org.jetbrains.lincheck.jvm.agent.toCanonicalClassName
import org.jetbrains.lincheck.settings.BreakpointId
import org.jetbrains.lincheck.settings.LiveDebuggerSettings
import org.jetbrains.lincheck.settings.SnapshotBreakpoint
import org.jetbrains.lincheck.settings.isApplicableTo
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import java.util.SortedSet

/**
 * This class consolidates all information about a class
 * extracted after class reading and pre-processed to be useful for method transformers.
 *
 *  Contains:
 *
 *   - [methods] - [MethodInformation] of all methods, indexed by `"$methodName$methodDesc"`.
 *   - [applicableBreakpoints] - Snapshot breakpoints applicable to this class, snapshotted at build time.
 *   - [enclosingClass] - Model of this class exposed to expression compilation.
 */
internal data class ClassInformation(
    private val methods: Map<String, MethodInformation>,
    val applicableBreakpoints: Map<BreakpointId, SnapshotBreakpoint>,
    val enclosingClass: ClassModel,
) {
    /**
     * Returns [MethodInformation] for given method, or `null` if the method is not declared in this class.
     */
    fun methodInformation(methodName: String, methodDesc: String): MethodInformation? =
        methods[methodName + methodDesc]
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
    liveDebuggerSettings: LiveDebuggerSettings,
    classLoader: ClassLoader? = null,
): ClassInformation {
    val canonicalClassName = classNode.name.toCanonicalClassName()
    val smap = readClassSMAP(classNode, classReader)
    val (lineRanges, linesToMethodNames) = getMethodsLineRanges(classNode)
    val nonSyntheticMethodLines = getNonSyntheticMethodLines(classNode)
    val applicableBreakpoints = computeApplicableBreakpoints(classNode, liveDebuggerSettings)
    // Sensitive-area blocklist verdicts (Stage 2) are consumed only by classes with applicable snapshot breakpoints.
    // Per-method verdicts are computed only when the class as a whole is not blocked.
    val blocklistEngine = liveDebuggerSettings.blocklistEngine
        .takeIf { !it.isEmpty() && applicableBreakpoints.isNotEmpty() }
    val classBlockMatch = blocklistEngine?.classBlock(canonicalClassName)
    val methods = classNode.methods.associateBy(
        keySelector = { m -> m.name + m.desc },
        valueTransform = { m ->
            val config = profile.getMethodConfiguration(canonicalClassName, m.name, m.desc)
            MethodInformation(
                smap = smap,
                locals = getMethodLocalVariables(classNode.name, m, config),
                labels = getMethodLabels(m),
                lineRange = lineRanges[m.name + m.desc] ?: (0 to 0),
                linesToMethodNames = linesToMethodNames,
                nonSyntheticMethodLines = nonSyntheticMethodLines,
                basicControlFlowGraph = computeControlFlowGraph(classNode.name, m, config),
                blockMatch = classBlockMatch
                    ?: blocklistEngine?.let { computeMethodBlockMatch(canonicalClassName, m, it) },
            )
        }
    )
    return ClassInformation(
        methods = methods,
        applicableBreakpoints = applicableBreakpoints,
        enclosingClass = ClassModel.fromClassNode(classNode),
    )
}

/**
 * Snapshots the breakpoints applicable to this class.
 *
 * The single snapshot both gates the blocklist verdicts and drives injection in the visitor:
 * re-reading the live settings later would let a breakpoint registered mid-transform
 * be injected without its pre-computed Stage 2 verdict.
 */
private fun computeApplicableBreakpoints(
    classNode: ClassNode,
    liveDebuggerSettings: LiveDebuggerSettings,
): Map<BreakpointId, SnapshotBreakpoint> {
    val canonicalClassName = classNode.name.toCanonicalClassName()
    val sourceFileName = classNode.sourceFile ?: ""
    return liveDebuggerSettings.lineBreakpoints.filterValues { it.isApplicableTo(canonicalClassName, sourceFileName) }
}

/**
 * Collects all source lines referenced by any non-synthetic-lambda method of [classNode].
 */
private fun getNonSyntheticMethodLines(classNode: ClassNode): Set<Int> {
    return buildSet {
        classNode.methods.forEach { method ->
            if (isSyntheticLambdaMethod(method.access, method.name)) return@forEach
            addAll(getMethodLines(method))
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
): Pair<Map<String, Pair<Int, Int>>, List<MethodsLineRange>> {
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
        val lines = getMethodLines(m)
        if (lines.isNotEmpty()) {
            allMethods.add(Triple(m.name, m.desc, lines))
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
        .map { (lines, names) -> MethodsLineRange(lines.first, lines.second, names.toSet()) }
    linesToMethodNames.sortedWith { a, b ->  a.firstLine.compareTo(b.firstLine) }

    return methodsToLines to linesToMethodNames
}
