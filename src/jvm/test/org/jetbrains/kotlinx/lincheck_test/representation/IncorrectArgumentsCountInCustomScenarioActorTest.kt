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

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Test

class IncorrectArgumentsCountInCustomScenarioActorTest {

    @Operation
    @Suppress("UNUSED_PARAMETER")
    fun operation(value: Int) = Unit

    @Test
    fun testTooFew() {
        val exception = Assert.assertThrows(IllegalArgumentException::class.java) {
            ModelCheckingOptions()
                .addCustomScenario {
                    parallel { thread { actor(::operation) } }
                }
        }
        assertEquals("Invalid number of the operation operation parameters: 1 expected, 0 provided.", exception.message)
    }

    @Test
    fun testTooMany() {
        val exception = Assert.assertThrows(IllegalArgumentException::class.java) {
            ModelCheckingOptions()
                .addCustomScenario {
                    parallel { thread { actor(::operation, 1, 2) } }
                }
        }
        assertEquals("Invalid number of the operation operation parameters: 1 expected, 2 provided.", exception.message)
    }

}
