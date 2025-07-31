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

import org.jetbrains.lincheck.util.*
import org.objectweb.asm.Label
import org.objectweb.asm.Type

typealias StackSlotIndex = Int
typealias LocalVariablesMap = Map<StackSlotIndex, List<LocalVariableInfo>>
typealias LocalVariablesMutableMap = MutableMap<StackSlotIndex, MutableList<LocalVariableInfo>>

fun LocalVariablesMutableMap(): LocalVariablesMutableMap = mutableMapOf()

private const val INLINE_FUNC_PREFIX = "\$i\$f\$"

internal fun List<LocalVariableInfo>.isUniqueVariable(): Boolean {
    val name = first().name
    val type = first().type
    return all { it.name == name && it.type == type }
}

data class LocalVariableInfo(
    val name: String,
    val index: Int,
    val type: Type,
    val labelIndexRange: Pair<Label, Label>
) {
    val isInlineCallMarker = name.startsWith(INLINE_FUNC_PREFIX)
    val inlineMethodName = if (isInlineCallMarker) name.removePrefix(INLINE_FUNC_PREFIX) else null
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
        varsByStartLabel[label].orEmpty().filter { it.isInlineCallMarker }

    fun inlinesEndAt(label: Label): List<LocalVariableInfo> =
        varsByEndLabel[label].orEmpty().filter { it.isInlineCallMarker }

    fun getVarByName(name: String): Set<LocalVariableInfo> = varsByName.getOrElse(name, ::emptyList).toSet()
    fun hasVarByName(name: String): Boolean = varsByName.containsKey(name)

    fun getActiveVar(index: StackSlotIndex): String? =
        activeVars.firstOrNull { it.index == index }?.name

    fun getActiveVars(): List<LocalVariableInfo> =
        activeVariables.toList()
}
