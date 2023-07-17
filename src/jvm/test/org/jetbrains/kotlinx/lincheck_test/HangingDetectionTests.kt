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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier

class HangingInParallelPartTest : AbstractLincheckTest(DeadlockWithDumpFailure::class) {

    @Operation
    fun hang() {
        while (true) {}
    }

    override fun LincheckOptionsImpl.customize() {
        addCustomScenario {
            parallel {
                thread {
                    actor(::hang)
                }
                thread {
                    actor(::hang)
                }
            }
        }
        generateRandomScenarios = false
        invocationTimeoutMs = 100
    }

}

class HangingInInitPartTest : AbstractLincheckTest(DeadlockWithDumpFailure::class) {

    @Operation
    fun hang() {
        while (true) {}
    }

    @Operation
    fun idle() {}

    override fun LincheckOptionsImpl.customize() {
        addCustomScenario {
            initial {
                actor(::hang)
            }
            parallel {
                thread {
                    actor(::idle)
                }
            }
        }
        generateRandomScenarios = false
        invocationTimeoutMs = 100
    }

}

class HangingInPostPartTest : AbstractLincheckTest(DeadlockWithDumpFailure::class) {

    @Operation
    fun hang() {
        while (true) {}
    }

    @Operation
    fun idle() {}

    override fun LincheckOptionsImpl.customize() {
        addCustomScenario {
            parallel {
                thread {
                    actor(::idle)
                }
            }
            post {
                actor(::hang)
            }
        }
        generateRandomScenarios = false
        invocationTimeoutMs = 100
    }

    override fun extractState(): Any = System.identityHashCode(this)

}