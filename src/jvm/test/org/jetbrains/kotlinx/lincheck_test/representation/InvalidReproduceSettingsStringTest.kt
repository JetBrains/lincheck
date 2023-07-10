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

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Assert.*
import org.junit.Test
import java.lang.IllegalArgumentException

/**
 * Ensures that exception, thrown in case if invalid reproduce settings string is readable and descriptive
 */
class InvalidReproduceSettingsStringTest {

    @Operation
    @Suppress("unused")
    fun stubOp() = Unit

    @Test
    fun `completely incorrect reproduce settings string`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            ModelCheckingOptions()
                .withReproduceSettings("broken string")
                .check(this::class)
        }
        val expectedMessage =
            "Supplied reproduce settings string is not valid or is not supported by this version of Lincheck. Please ensure it is correct"
        assertEquals(expectedMessage, exception.message)
    }
}