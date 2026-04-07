/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck

internal data class LincheckSettings(
    val analyzeStdLib: Boolean = DEFAULT.analyzeStdLib,
    val loopIterationsBeforeThreadSwitch: Int = DEFAULT.loopIterationsBeforeThreadSwitch,
    val loopBound: Int = DEFAULT.loopBound,
    val recursionBound: Int = DEFAULT.recursionBound,
) {
    companion object {
        val DEFAULT = LincheckSettings(
            analyzeStdLib = true,
            loopIterationsBeforeThreadSwitch = 10,
            loopBound = 50,
            recursionBound = 20,
        )
    }
}