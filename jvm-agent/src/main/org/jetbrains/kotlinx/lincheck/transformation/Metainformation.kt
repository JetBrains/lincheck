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

import org.jetbrains.kotlinx.lincheck.SMAPInfo

internal data class ClassMetaInfo (
    val smap: SMAPInfo?,
    val locals: Map<String, MethodVariables>,
    val labels: Map<String, MethodLabels>
) {
    fun methodMetaInfo(methodName: String, methodDesc: String): MethodMetaInfo =
        MethodMetaInfo(
            smap = smap,
            locals = locals[methodName + methodDesc] ?: MethodVariables.EMPTY,
            labels = labels[methodName + methodDesc] ?: MethodLabels.EMPTY
        )
}

internal data class MethodMetaInfo(
    val smap: SMAPInfo?,
    val locals: MethodVariables,
    val labels: MethodLabels
)
