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
import org.jetbrains.kotlinx.lincheck_test.util.checkFailsWithException
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Test

class IncorrectArgumentsCountInCustomScenarioActorTest {

    @Operation
    fun operation(value: Int) = Unit

    @Test
    fun testTooFew() {
        val exception = Assert.assertThrows(IllegalArgumentException::class.java) {
            ModelCheckingOptions()
                .addCustomScenario {
                    parallel { thread { actor(::operation) } }
                }
        }
        assertEquals("The count of the supplied parameters for the operation method is incorrect: 1 arguments expected, 0 supplied.", exception.message)
    }

    @Test
    fun testTooMany() {
        val exception = Assert.assertThrows(IllegalArgumentException::class.java) {
            ModelCheckingOptions()
                .addCustomScenario {
                    parallel { thread { actor(::operation, 1, 2) } }
                }
        }
        assertEquals("The count of the supplied parameters for the operation method is incorrect: 1 arguments expected, 2 supplied.", exception.message)
    }

}
