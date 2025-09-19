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

class NestedLoopWithExceptionRepresentationTest : BaseTraceRepresentationTest(
    outputFileName = "loops/nested_loop_with_exception_representation"
) {
    var escape: Any? = null
    
    private fun nestedLoopWithException() {
        escape = "METHOD_START"
        for (i in 1..2) {
            loopIterationStart(1)
            val a: Any = i
            for (j in 1..3) {
                loopIterationStart(2)
                escape = "$a.$j"
                if (i == 2 && j == 2) {
                    throw RuntimeException("Exception in nested loop")
                }
            }
            loopEnd(2)
        }
        loopEnd(1)
        escape = "METHOD_END"
    }
    
    override fun operation() {
        escape = "START"
        try {
            nestedLoopWithException()
        } catch (e: RuntimeException) {
            escape = "CAUGHT EXCEPTION"
        }
        escape = "END"
    }
}