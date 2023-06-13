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
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.runModelCheckingTestAndCheckOutput
import org.junit.Test

@Suppress("UNUSED_PARAMETER", "UNUSED")
class CustomScenarioTestSuggestionInOutputKotlinTest {

    private var canEnterForbiddenSection = false

    @Operation
    fun operation1(
        byteValue: Byte,
        stringValue: String,
        shortValue: Short
    ): Int {
        canEnterForbiddenSection = true
        canEnterForbiddenSection = false
        return 1
    }

    @Operation(handleExceptionsAsResult = [IllegalStateException::class])
    fun operation2(
        intValue: Int,
        doubleValue: Double,
        floatValue: Float,
        booleanValue: Boolean,
        longValue: Long
    ) {
        check(!canEnterForbiddenSection)
    }

    @Operation
    fun operationNoArguments() = Unit

    @Test
    fun test() = runModelCheckingTestAndCheckOutput( "suggested_custom_scenario_in_kotlin.txt") {
        iterations(30)
        actorsBefore(2)
        threads(3)
        actorsPerThread(2)
        actorsAfter(2)
        minimizeFailedScenario(false)
        withReproduceSettings("eyJyYW5kb21TZWVkR2VuZXJhdG9yU2VlZCI6ODU4NzkxOTgzNjI3OTU3Nzk1NH0=")
    }

}
