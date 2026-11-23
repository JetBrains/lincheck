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
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.*
import kotlinx.atomicfu.atomic
import org.jetbrains.lincheck.datastructures.Operation
import org.junit.*

/** Checks switch placement within nested calls using atomicfu instrumentation. */
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
    fun test() = ModelCheckingOptions().apply {
        actorsPerThread(1)
        actorsBefore(0)
        actorsAfter(0)
    }
        .detectFailure(this::class.java)
        .checkLincheckOutput("switch_as_first_method_event")
}

/** Checks that a switch before the first atomic access retains the enclosing method calls. */
class SwitchAsFirstNestedMethodEventTest {
    private val counter = java.util.concurrent.atomic.AtomicInteger()

    @Operation
    fun incTwiceAndGet(): Int {
        incAndGet()
        return incAndGet()
    }

    private fun incAndGet(): Int = incAndGetImpl()

    private fun incAndGetImpl(): Int = counter.incrementAndGet()

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(::incTwiceAndGet) }
                thread { actor(::incTwiceAndGet) }
            }
        }
        .iterations(0)
        .detectFailure(this::class.java)
        .checkLincheckOutput("switch_as_first_nested_method_event")
}
