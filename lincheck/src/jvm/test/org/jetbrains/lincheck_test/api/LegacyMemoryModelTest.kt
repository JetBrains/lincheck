/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.lincheck_test.api

import org.jetbrains.lincheck.ValidationFailure
import org.jetbrains.kotlinx.lincheck.detectFailure
import org.jetbrains.lincheck.LincheckSettings.MemoryModel
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Validate
import org.jetbrains.lincheck.datastructures.Operation
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyMemoryModelTest {
    @Test
    fun memoryModelSelectionWorksWithoutPartialOrderReduction() {
        for (model in MemoryModel.entries) {
            checkStoreBuffering(ModelCheckingOptions().memoryModel(model), model)
        }
    }

    @Test
    fun memoryModelSelectionSurvivesTogglingPartialOrderReduction() {
        for (model in MemoryModel.entries) {
            for (enabled in listOf(true, false)) {
                val options = ModelCheckingOptions().enablePartialOrderReduction().memoryModel(model)
                    .enablePartialOrderReduction(enabled)
                checkStoreBuffering(options, model)
            }
        }
    }

    private fun checkStoreBuffering(options: ModelCheckingOptions, model: MemoryModel) {
        val failure = options.iterations(0).addCustomScenario {
            parallel {
                thread { actor(StoreBuffering::writeXReadY) }
                thread { actor(StoreBuffering::writeYReadX) }
            }
        }.detectFailure(StoreBuffering::class.java)
        if (model == MemoryModel.SequentialConsistency) {
            assertNull(failure)
        } else {
            // Both reads observing zero is forbidden by sequential consistency.
            assertTrue("$model: $failure", failure is ValidationFailure)
        }
    }

    class StoreBuffering {
        private var x = 0
        private var y = 0
        private var readX = -1
        private var readY = -1

        @Operation
        fun writeXReadY() {
            x = 1
            readY = y
        }

        @Operation
        fun writeYReadX() {
            y = 1
            readX = x
        }

        @Validate
        fun noStoreBuffering() {
            check(readX != 0 || readY != 0)
        }
    }
}
