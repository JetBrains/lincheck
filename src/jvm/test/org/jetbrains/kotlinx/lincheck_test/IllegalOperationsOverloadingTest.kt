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

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks that if test class contains many operations with same name and parameters count than execution will be thrown
 */
class IllegalOperationsOverloadingTest {

    @Test
    fun `should throw exception`() {
        val exception = Assert.assertThrows(IllegalArgumentException::class.java) {
            ModelCheckingOptions().check(this::class.java)
        }

        val expectedMessage = "You can't have two operations with the same name and parameters number. " +
                "Please rename one of these operations: operationA(java.lang.Integer) operationA(java.lang.Double)"

        assertEquals(expectedMessage, exception.message)
    }

    @Operation
    @Suppress("UNUSED")
    fun operationA(intParam: Int?) = Unit

    @Operation
    @Suppress("UNUSED")
    fun operationA(doubleParam: Double?) = Unit
}