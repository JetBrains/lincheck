/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.junit.*

/**
 * This test checks basic interleaving reporting features,
 * including reporting of lock acquiring/releasing, reads/writes with parameter/result capturing.
 */
class TraceReportingTest {
    @Volatile
    var a = 0
    @Volatile
    var b = 0
    @Volatile
    var canEnterForbiddenSection = false

    @Operation
    fun foo(): Int {
        if (canEnterForbiddenSection) {
            return 1
        }
        return 0
    }

    @Operation
    fun bar() {
        repeat(2) {
            a++
        }
        uselessIncrements(2)
        intermediateMethod()
    }

    private fun intermediateMethod() {
        resetFlag()
    }

    @Synchronized
    private fun resetFlag() {
        canEnterForbiddenSection = true
        canEnterForbiddenSection = false
    }

    private fun uselessIncrements(count: Int): Boolean {
        repeat(count) {
            b++
        }
        return false
    }

    @Test
    fun test() {
        val failure = ModelCheckingOptions().apply {
            iterations(0)
            addCustomScenario {
                parallel {
                    thread {
                        actor(::foo)
                    }
                    thread {
                        actor(::bar)
                    }
                }
            }
        }.checkImpl(this::class.java)
        failure.checkLincheckOutput("trace_reporting")
        checkTraceHasNoLincheckEvents(failure.toString())
    }

    var init = 0
    var post = 0

    @Operation
    fun enterInit() {
        init = 1
    }

    @Operation
    fun enterPost() {
        post = 1
    }

    @Test
    fun testInitPostParts() {
        val failure = ModelCheckingOptions()
            .iterations(0)
            .addCustomScenario {
                initial {
                    actor(::enterInit)
                }
                parallel {
                    thread {
                        actor(::foo)
                    }
                    thread {
                        actor(::bar)
                    }
                }
                post {
                    actor(::enterPost)
                }
            }
            .checkImpl(this::class.java)
        failure.checkLincheckOutput("trace_reporting_init_post_parts")
        checkTraceHasNoLincheckEvents(failure.toString())
    }

    @Operation
    fun notImplemented() {
        TODO()
    }

    @Test
    fun testEmptyTrace() {
        ModelCheckingOptions()
            .iterations(0)
            .addCustomScenario {
                parallel {
                    thread {
                        actor(::notImplemented)
                    }
                }
            }
            .sequentialSpecification(EmptySequentialImplementation::class.java)
            .checkImpl(this::class.java) { failure ->
                failure.checkLincheckOutput("trace_reporting_empty")
            }
    }

    class EmptySequentialImplementation {
        fun notImplemented() {}
    }

}
