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

class ComplexNestedLoopsRepresentationTest : BaseTraceRepresentationTest(
    outputFileName = "loops/complex_nested_loops_representation"
) {
    var escape: Any? = null
    override fun operation() {
        escape = "START"
        // Outer for loop
        for (i in 1..2) {
            loopIterationStart(1)
            val a: Any = i
            escape = "for-$a"
            
            // Middle while loop
            var j = 1
            while (j <= 2) {
                loopIterationStart(2)
                val b: Any = j
                escape = "for-$a-while-$b"
                
                // Inner do-while loop
                var k = 1
                do {
                    loopIterationStart(3)
                    val c: Any = k
                    escape = "for-$a-while-$b-dowhile-$c"
                    k++
                } while (k <= 1)
                loopEnd(3)

                j++
            }
            loopEnd(2)
        }
        loopEnd(1)
        escape = "END"
    }
}