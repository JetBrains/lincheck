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

class SimpleDoWhileLoopRepresentationTest : BaseTraceRepresentationTest("loops/simple_dowhile_representation.txt") {
    var escape: Any? = null

    override fun operation() {
        escape = "START"
        var i = 2
        do {
            val a: Any = i
            escape = a.toString()
            i++
        } while(i < 4)
        escape = "END"
    }
}
