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

import kotlinx.atomicfu.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.junit.*

/**
 * This test checks that all switches that are the first events in methods are lifted out of the methods in the trace.
 * E.g, instead of
 * ```
 * actor()
 *   method()
 *      switch
 *      READ
 *      ...
 * ```
 * should be
 * ```
 * actor()
 *   switch
 *   method()
 *      ...
 * ```
 */
class SwitchAsFirstMethodEventTest {
    private val counter = atomic(0)

    @Operation
    fun incTwiceAndGet(): Int {
        incAndGet()
        return incAndGet()
    }

    private fun incAndGet(): Int = incAndGetImpl()

    private fun incAndGetImpl() = counter.incrementAndGet()

    @Test
    fun test() = LincheckOptions {
        this as LincheckOptionsImpl
        addCustomScenario {
            parallel {
                thread {
                    actor(::incTwiceAndGet)
                }
                thread {
                    actor(::incTwiceAndGet)
                }
            }
        }
        mode = LincheckMode.ModelChecking
        generateRandomScenarios = false
    }
        .checkImpl(this::class.java)
        .checkLincheckOutput("switch_as_first_method_event.txt")
}