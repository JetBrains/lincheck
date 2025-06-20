/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test

import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Options
import org.jetbrains.lincheck.datastructures.scenario

class HangingInParallelPartIsolatedTest : AbstractLincheckTest(TimeoutFailure::class) {

    @Operation
    fun hang() {
        while (true) {}
    }

    val scenario = scenario {
        parallel {
            thread {
                actor(::hang)
            }
            thread {
                actor(::hang)
            }
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        addCustomScenario(scenario)
        iterations(0)
        minimizeFailedScenario(false)
        invocationTimeout(100)
    }

}

class HangingInInitPartIsolatedTest : AbstractLincheckTest(TimeoutFailure::class) {

    @Operation
    fun hang() {
        while (true) {}
    }

    @Operation
    fun idle() {}

    val scenario = scenario {
        initial {
            actor(::hang)
        }
        parallel {
            thread {
                actor(::idle)
            }
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        addCustomScenario(scenario)
        iterations(0)
        minimizeFailedScenario(false)
        invocationTimeout(100)
    }

}

class HangingInPostPartIsolatedTest : AbstractLincheckTest(TimeoutFailure::class) {

    @Operation
    fun hang() {
        while (true) {}
    }

    @Operation
    fun idle() {}

    val scenario = scenario {
        parallel {
            thread {
                actor(::idle)
            }
        }
        post {
            actor(::hang)
        }
    }

    override fun <O : Options<O, *>> O.customize() {
        addCustomScenario(scenario)
        iterations(0)
        minimizeFailedScenario(false)
        invocationTimeout(100)
    }

}