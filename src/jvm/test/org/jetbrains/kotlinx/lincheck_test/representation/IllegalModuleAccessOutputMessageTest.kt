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
import org.jetbrains.kotlinx.lincheck.util.InternalLincheckExceptionEmulator.throwException
import org.jetbrains.kotlinx.lincheck_test.util.*
import org.junit.*

/**
 * This test checks that hint about classes not accessible from unnamed modules
 * is present in output when such exception is occurred.
 */
@Suppress("unused")
class IllegalModuleAccessOutputMessageTest {

    private val counter = atomic(0)

    @Operation
    fun incrementTwice() {
        counter.incrementAndGet()
        counter.decrementAndGet()
    }

    @Operation
    fun operation() {
        if (counter.value != 0) {
            throwException { IllegalAccessException("module java.base does not \"opens java.io\" to unnamed module") }
        }
    }

    @Test
    fun test() = ModelCheckingOptions()
        .checkImpl(this::class.java)
        .checkLincheckOutput("illegal_module_access.txt")
}