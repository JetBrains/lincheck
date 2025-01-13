/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.transformation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.junit.*

class BackBranchTest() {
    var escape: Any? = null
    @Operation
    fun operation() {
        runLoop("A")
        runLoop("B")
        runWhile("W")
        runDo("D")
    }


    fun runLoop(prfx: String) {
        escape = "$prfx-START"
        for (i in 1..2) {
            val a: Any = i
            for (j in 1..3) {
                escape = prfx + a.toString() + "." + j.toString()
            }
        }
        escape = "$prfx-END"
    }

    fun runWhile(prfx: String) {
        escape = "$prfx-WHILE"
        var i = 0
        while (i < 2) {
            escape = prfx + i
            i++
        }
        escape = "$prfx-ELIHW"
    }

    fun runDo(prfx: String) {
        escape = "$prfx-DO"
        var i = 0
        do {
            escape = prfx + i
            i++
        } while (i < 2)
        escape = "$prfx-WHILE"
    }

    @Test
    fun test() = ModelCheckingOptions().logLevel(LoggingLevel.INFO).check(this::class)
}
