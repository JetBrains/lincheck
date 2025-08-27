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

/**
 *  This class consolidates all information about class extracted after
 * class reading and pre-processed to be useful for method transformers.
 *
 *  Now it contains:
 *
 *   - [smap] - SMAP read from the class file or empty mapper.
 *   - [locals] - Locals of all methods, indexed by `"$methodName$methodDesc"`.
 *   - [labels] - Comparator for all methods' labels, indexed by `"$methodName$methodDesc"`.
 */
internal data class ClassInformation(
    val smap: SMAPInfo,
    val locals: Map<String, MethodVariables>,
    val labels: Map<String, MethodLabels>
) {
    /**
     * Returns [MethodInformation] for given method.
     */
    fun methodInformation(methodName: String, methodDesc: String): MethodInformation =
        MethodInformation(
            smap = smap,
            locals = locals[methodName + methodDesc] ?: MethodVariables.EMPTY,
            labels = labels[methodName + methodDesc] ?: MethodLabels.EMPTY
        )
}

/**
 *  This class consolidates all information about one method extracted after
 * class reading and pre-processed to be useful for method transformers.
 *
 *  Now it contains:
 *
 *   - [smap] - SMAP read from the class file or empty mapper. It is shared among all methods.
 *   - [locals] - Locals of this method.
 *   - [labels] - Comparator for all method's labels.
 */
internal data class MethodInformation(
    val smap: SMAPInfo,
    val locals: MethodVariables,
    val labels: MethodLabels
)
