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

import org.jetbrains.kotlinx.lincheck.test_utils.*
import org.jetbrains.kotlinx.lincheck_test.representation.*

class BreakedForLoopRepresentationTest : BaseTraceRepresentationTest(
    outputFileName = "loops/breaked_for_representation"
) {
    var escape: Any? = null
    override fun operation() {
        escape = "START"
        for (i in 1..5) {
            loopIterationStart(1)
            val a: Any = i
            escape = a.toString()
            if (i > 3) {
                // do not call here to avoid multiple calls
                // loopEnd(1)
                break
            }
            escape = "${a} is saved"
        }
        loopEnd(1)
        escape = "END"
    }
}
