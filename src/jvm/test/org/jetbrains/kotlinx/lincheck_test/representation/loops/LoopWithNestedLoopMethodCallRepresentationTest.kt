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

class LoopWithNestedLoopMethodCallRepresentationTest : BaseTraceRepresentationTest(
    outputFileName = "loops/loop_with_nested_loop_method_call_representation"
) {
    var escape: Any? = null
    override fun operation() {
        escape = "START"
        for (i in 1..2) {
            val a: Any = i
            escape = "outer-$a"
            methodWithLoop(a)
        }
        escape = "END"
    }

    private fun methodWithLoop(a: Any) {
        escape = "method-start-$a"
        for (j in 1..2) {
            val b: Any = j
            escape = "method-inner-$a-$b"
        }
        escape = "method-end-$a"
    }
}
