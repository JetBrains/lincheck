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

class ForLoopWithMethodCallRepresentationTest : BaseTraceRepresentationTest(
    outputFileName = "loops/for_loop_with_method_call_representation"
) {
    var escape: Any? = null
    override fun operation() {
        escape = "START"
        for (i in 1..2) {
            val a: Any = i
            method(a)
        }
        escape = "END"
    }

    private fun method(a: Any) {
        escape = a.toString()
    }
}
