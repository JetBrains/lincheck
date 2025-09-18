/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.representation.loops

import org.jetbrains.kotlinx.lincheck_test.representation.*

class ContinuedForLoopRepresentationTest : BaseTraceRepresentationTest(
    outputFileName = "loops/continued_for_representation"
) {
    var escape: Any? = null
    override fun operation() {
        escape = "START"
        for (i in 1..5) {
            val a: Any = i
            escape = a.toString()
            if (i % 2 == 0) {
                continue
            }
            escape = "${a} is odd"
        }
        escape = "END"
    }
}
