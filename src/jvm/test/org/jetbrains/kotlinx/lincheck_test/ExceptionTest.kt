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

class ExceptionInParallelPartTest : AbstractLincheckTest(IncorrectResultsFailure::class) {

    @Operation
    fun exception() {
        throw IllegalStateException()
    }

    override fun LincheckOptionsImpl.customize() {
        addCustomScenario {
            parallel {
                thread {
                    actor(::exception)
                }
                thread {
                    actor(::exception)
                }
            }
        }
        generateRandomScenarios = false
        sequentialImplementation = ExceptionTestSequentialImplementation::class.java
    }

}

class ExceptionInInitPartTest : AbstractLincheckTest(IncorrectResultsFailure::class) {

    @Operation
    fun exception() {
        throw IllegalStateException()
    }

    @Operation
    fun idle() {}

    override fun LincheckOptionsImpl.customize() {
        addCustomScenario {
            initial {
                actor(ExceptionInInitPartTest::exception)
            }
            parallel {
                thread {
                    actor(ExceptionInInitPartTest::idle)
                }
            }
        }
        generateRandomScenarios = false
        sequentialImplementation = ExceptionTestSequentialImplementation::class.java
    }

}

class ExceptionInPostPartTest : AbstractLincheckTest(IncorrectResultsFailure::class) {

    @Operation
    fun exception() {
        throw IllegalStateException()
    }

    @Operation
    fun idle() {}

    override fun LincheckOptionsImpl.customize() {
        addCustomScenario {
            parallel {
                thread {
                    actor(ExceptionInPostPartTest::idle)
                }
            }
            post {
                actor(ExceptionInPostPartTest::exception)
            }
        }
        generateRandomScenarios = false
        sequentialImplementation = ExceptionTestSequentialImplementation::class.java
    }

}

class ExceptionTestSequentialImplementation {
    fun exception() {}
    fun idle() {}
}