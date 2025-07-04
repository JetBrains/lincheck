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

import org.jetbrains.kotlinx.lincheck.NO_OPERATION_ERROR_MESSAGE
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * This class is used to test the exception message when no operations are defined in a tested class.
 */
class NoOperationsDefinedTest {

    @Test
    fun test() {
        val exception = assertThrows(IllegalStateException::class.java) {
            ModelCheckingOptions().check(this::class)
        }
        assertEquals(NO_OPERATION_ERROR_MESSAGE, exception.message)
    }

}