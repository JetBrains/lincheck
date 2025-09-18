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

class SimpleWhileLoopRepresentationTest : BaseTraceRepresentationTest(
    outputFileName = "loops/simple_while_representation"
) {
    var escape: Any? = null
    override fun operation() {
        escape = "START"
        var i = 1
        while (i < 4) {
            loopIterationStart(1)
            val a: Any = i
            escape = a.toString()
            i++
        }
        loopEnd(1)
        escape = "END"
    }
}
