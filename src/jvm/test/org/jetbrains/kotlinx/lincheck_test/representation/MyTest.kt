/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.TimeTravellingInjections
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MyTest {

    @Volatile
    var counter: Int = 0

    @Operation
    fun inc(a: Int, b: Int): Int {
        return innerMethod()
    }

    private fun innerMethod(): Int {
        useless()
        return counter++
    }

    fun useless() {
        var b = 1
        b = 6
        counter++
        var x = 10
        x = b + 10
    }

    @Test
    fun test() = ModelCheckingOptions()
        .verifier(TimeTravellingInjections.FailingVerifier::class.java)
        .check(this::class)

}