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

class ForWithIfLoopRepresentationTest : BaseTraceRepresentationTest(
    outputFileName = "loops/for_with_if_representation"
) {
    var escape: Any? = null
    override fun operation() {
        escape = "START"
        var i = 0
        var total = 0
        while (true) {
            total++
            if (total > 10) {
                break
            }
            val a: Any = i
            escape = a.toString()
            i = (i + 1) % 3
            if (i == 0) {
                escape = "%3-" + escape
            }
        }
        escape = "END"
    }
}
