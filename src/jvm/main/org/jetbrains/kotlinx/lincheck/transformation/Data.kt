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

internal fun List<LocalVariableInfo>.isUniqueVariable(): Boolean {
    val name = first().name
    val type = first().type
    return all { it.name == name && it.type == type }
}

internal data class LocalVariableInfo(val name: String, val labelIndexRange: Pair<Label, Label>, val type: Type) {
    val isInlineCallMarker = name.startsWith("\$i\$f\$")
    val inlineMethodName = if (isInlineCallMarker) name.substring(5) else name
}

internal data class MethodVariables(
    val variables: Map<Int, List<LocalVariableInfo>>,
    val inlineStartMarkers: Map<Label, List<LocalVariableInfo>>,
    val inlineEndMarkers: Map<Label, List<LocalVariableInfo>>
) {
    constructor(variables: Map<Int, List<LocalVariableInfo>>) : this(variables, groupMarkersByStart(variables), groupMarkersByEnd(variables))
    constructor() : this(emptyMap(), emptyMap(), emptyMap())

    val hasInlines = inlineStartMarkers.isNotEmpty() && inlineEndMarkers.isNotEmpty()
}

private fun groupMarkersByStart(variables: Map<Int, List<LocalVariableInfo>>) = groupMarkersBy(variables) { it.labelIndexRange.first }

private fun groupMarkersByEnd(variables: Map<Int, List<LocalVariableInfo>>) = groupMarkersBy(variables) { it.labelIndexRange.second }

private fun groupMarkersBy(variables: Map<Int, List<LocalVariableInfo>>, key: (LocalVariableInfo) -> Label): Map<Label, List<LocalVariableInfo>> {
    val rv = mutableMapOf<Label, MutableList<LocalVariableInfo>>()
    variables.values
        .flatten()
        .filter { it.isInlineCallMarker }
        .forEach {
            rv.getOrPut(key(it)) { mutableListOf() }.add(it)
        }
    return rv
}
