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

/**
 *  This class consolidates all information about class extracted after
 * class reading and pre-processed to be useful for method transformers.
 *
 *  Now it contains:
 *
 *   - [smap] - SMAP read from the class file or empty mapper.
 *   - [locals] - Locals of all methods, indexed by `"$methodName$methodDesc"`.
 *   - [labels] - Comparator for all methods' labels, indexed by `"$methodName$methodDesc"`.
 *   - [methodsToLineRanges] - Pair of first and last non-zero LINENUMBER in method. Not min and max, as max can be mapped later
 *     to other place.
 *   - [linesToMethodNames] - Sorted list of all known line numbers ranges and method names (without `desc`) for these ranges.
 */
internal data class ClassInformation(
    private val smap: SMAPInfo,
    private val locals: Map<String, MethodVariables>,
    private val labels: Map<String, MethodLabels>,
    private val methodsToLineRanges: Map<String, Pair<Int, Int>>,
    private val linesToMethodNames: List<Triple<Int, Int, Set<String>>>
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
            linesToMethodNames = linesToMethodNames
        )
}


internal const val UNKNOWN_METHOD_MARKER = "<unknown method>"

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
 */
internal data class MethodInformation(
    val smap: SMAPInfo,
    val locals: MethodVariables,
    val labels: MethodLabels,
    val lineRange: Pair<Int, Int>,
    private val linesToMethodNames: List<Triple<Int, Int, Set<String>>>
) {
    fun findMethodByLine(line: Int, currentMethodName: String): String {
        val idx = linesToMethodNames.binarySearch {
            val (from, to, _) = it
            when {
                line < from -> +1
                line > to -> -1
                else -> 0
            }
        }
        if (idx < 0) return UNKNOWN_METHOD_MARKER
        val allNames = linesToMethodNames[idx].third
        if (allNames.size == 1) return allNames.first()
        if (allNames.contains(currentMethodName)) return currentMethodName
        return UNKNOWN_METHOD_MARKER
    }
}

