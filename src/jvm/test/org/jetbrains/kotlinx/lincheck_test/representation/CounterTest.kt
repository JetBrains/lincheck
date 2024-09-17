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

class CounterTest {

    private var counter = 0

    @Operation
    fun inc(): Int {
        useless()
        return internalMethod()
    }

    private fun useless() {

    }

    private fun internalMethod(): Int {
        kek(1, "123")
        return counter++
    }

    fun kek(a: Int, b: String) {
        counter++
    }

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(::inc) }
                thread { actor(::inc) }
            }
        }
        .verifier(TimeTravellingInjections.FailingVerifier::class.java)
        .check(this::class)

}