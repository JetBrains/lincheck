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

import org.jetbrains.kotlinx.lincheck.test_utils.loopEnd
import org.jetbrains.kotlinx.lincheck.test_utils.loopIterationStart
import org.jetbrains.kotlinx.lincheck_test.representation.*

class ForLoopWithTryCatchRepresentationTest : BaseTraceRepresentationTest(
    outputFileName = "loops/for_loop_with_try_catch_representation"
) {
    var escape: Any? = null
    override fun operation() {
        escape = "START"
        for (i in 1..3) {
            loopIterationStart(1)
            val a: Any = i
            try {
                if (i == 2) {
                    throw RuntimeException("Exception in loop")
                }
                escape = "try-$a"
            } catch (e: RuntimeException) {
                escape = "catch-$a"
            }
        }
        loopEnd(1)
        escape = "END"
    }
}
