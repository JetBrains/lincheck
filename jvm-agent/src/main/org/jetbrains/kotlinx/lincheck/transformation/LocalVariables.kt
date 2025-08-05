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

import org.objectweb.asm.Label
import org.objectweb.asm.Type

typealias StackSlotIndex = Int
typealias LocalVariablesMap = Map<StackSlotIndex, List<LocalVariableInfo>>

private const val INLINE_FUNC_PREFIX = "\$i\$f\$"
private const val INLINE_LAMBDA_PREFIX = "\$i\$a\$"
private val INLINE_LAMBDA_PATTERN = Regex("^[$]i[$]a[$]-(?<inlineName>[^-]+)-(?<lambdaReferenceName>[^-]+)$")

internal fun List<LocalVariableInfo>.isUniqueVariable(): Boolean {
    val name = first().name
    val type = first().type
    return all { it.name == name && it.type == type }
}

data class LocalVariableInfo(val name: String, val index: Int, val labelIndexRange: Pair<Label, Label>, val type: Type) {
    val isInlineCallMarker = name.startsWith(INLINE_FUNC_PREFIX)
    val isInlineLambdaMarker = name.startsWith(INLINE_LAMBDA_PREFIX)

    val inlineMethodName: String?
        get() {
            if (isInlineCallMarker) {
                return name.removePrefix(INLINE_FUNC_PREFIX)
            }
            if (isInlineLambdaMarker) {
                val match = INLINE_LAMBDA_PATTERN.matchEntire(name)
                if (match != null) {
                    val callerInlineName = match.groups["inlineName"]?.value ?: "<unknown inline>"
                    return "$callerInlineName\$Lambda"
                } else {
                    return name.removePrefix(INLINE_LAMBDA_PREFIX)
                }
            }
            return null
        }

    val startLabel = labelIndexRange.first
    val endLabel = labelIndexRange.second
}

data class MethodVariables(val variables: LocalVariablesMap = emptyMap()) {

    private val varsByStartLabel = variables.values.flatten().groupBy { it.labelIndexRange.first }
    private val varsByEndLabel = variables.values.flatten().groupBy { it.labelIndexRange.second }
    private val varsByName = variables.values.flatten().groupBy { it.name }
    private val activeVars: MutableSet<LocalVariableInfo> = mutableSetOf()

    val hasInlines = variables.values.flatten().any { it.isInlineCallMarker }

    fun visitLabel(label: Label?) {
        if (label != null) {
            activeVars.removeAll(varsByEndLabel[label].orEmpty())
            activeVars.addAll(varsByStartLabel[label].orEmpty())
        }
    }

    val activeVariables: Set<LocalVariableInfo> get() = activeVars
    fun variablesStartAt(label: Label): List<LocalVariableInfo> = varsByStartLabel[label].orEmpty()
    fun variablesEndAt(label: Label): List<LocalVariableInfo> = varsByEndLabel[label].orEmpty()

    fun inlinesStartAt(label: Label): List<LocalVariableInfo> =
        varsByStartLabel[label].orEmpty().filter { it.isInlineCallMarker || it.isInlineLambdaMarker }

    fun inlinesEndAt(label: Label): List<LocalVariableInfo> =
        varsByEndLabel[label].orEmpty().filter { it.isInlineCallMarker || it.isInlineLambdaMarker }

    fun getVarByName(name: String): Set<LocalVariableInfo> = varsByName.getOrElse(name, ::emptyList).toSet()
    fun hasVarByName(name: String): Boolean = varsByName.containsKey(name)

    companion object {
        val EMPTY = MethodVariables()
    }
}
