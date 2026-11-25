/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.representation

import org.jetbrains.lincheck_test.util.checkLincheckOutput
import org.jetbrains.lincheck.Lincheck
import org.jetbrains.lincheck.LincheckAssertionError
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.LincheckSettings.MemoryModel
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy.ModelChecking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class EventStructureLambdaFailureRepresentationTest(private val memoryModel: MemoryModel) {
    @Test
    fun exceptionWithoutVisibleEvents() {
        val settings = LincheckSettings().strategy(
            ModelChecking().algorithm(ModelChecking.Algorithm.EventStructure).memoryModel(memoryModel)
        )
        // The trace contains only Throwable internals, which the printer filters out.
        val error = assertThrows(LincheckAssertionError::class.java) {
            Lincheck.runConcurrentTest(settings) { error("lambda failure") }
        }
        error.failure.checkLincheckOutput("event_structure_empty_lambda")
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun memoryModels() = MemoryModel.entries
    }
}
