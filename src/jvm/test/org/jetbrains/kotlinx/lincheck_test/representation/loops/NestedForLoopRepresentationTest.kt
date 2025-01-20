/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.representation.loops

import org.jetbrains.kotlinx.lincheck_test.representation.BaseTraceRepresentationTest

class NestedForLoopRepresentationTest : BaseTraceRepresentationTest("loops/nested_for_representation.txt") {
    var escape: Any? = null

    override fun operation() {
        escape = "START"
        for (i in 1..2) {
            val a: Any = i
            for (j in 1 .. 3) {
                escape = "$a.$j"
            }
        }
        escape = "END"
    }
}
