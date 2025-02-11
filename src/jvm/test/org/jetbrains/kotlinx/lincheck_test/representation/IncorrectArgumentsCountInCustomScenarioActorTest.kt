/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IncorrectArgumentsCountInCustomScenarioActorTest {

    @Operation
    @Suppress("UNUSED_PARAMETER")
    fun operation(value: Int) = Unit

    @Test
    fun testTooFew() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ModelCheckingOptions()
                .addCustomScenario {
                    parallel { thread { actor(::operation) } }
                }
        }
        assertEquals(exception.message, "Invalid number of the operation operation parameters: 1 expected, 0 provided.")
    }

    @Test
    fun testTooMany() {
        val exception = assertFailsWith<IllegalArgumentException> {
            ModelCheckingOptions()
                .addCustomScenario {
                    parallel { thread { actor(::operation, 1, 2) } }
                }
        }
        assertEquals("Invalid number of the operation operation parameters: 1 expected, 2 provided.", exception.message)
    }

}
