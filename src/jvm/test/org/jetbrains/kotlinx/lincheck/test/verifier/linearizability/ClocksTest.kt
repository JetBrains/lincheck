/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.test.*
import kotlin.reflect.jvm.*

class ClocksTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    @Volatile
    private var bStarted = false

    @Operation
    fun a() {
        // do nothing
    }

    @Operation
    fun b() {
        bStarted = true
    }

    @Operation
    fun c() {
        while (!bStarted) {} // wait until `a()` is completed
    }

    @Operation
    fun d(): Int {
        return 0 // cannot return 0, should fail
    }

    override fun <O : Options<O, *>> O.customize() {
        executionGenerator(ClocksTestScenarioGenerator::class.java)
        iterations(1)
        sequentialSpecification(ClocksTestSequential::class.java)
        minimizeFailedScenario(false)
    }
}

class ClocksTestScenarioGenerator(
    testCfg: CTestConfiguration,
    testStructure: CTestStructure,
    randomProvider: RandomProvider
) : ExecutionGenerator(testCfg, testStructure) {
    override fun nextExecution() = ExecutionScenario(
        emptyList(),
        listOf(
            listOf(
                Actor(method = ClocksTest::a.javaMethod!!, arguments = emptyList()),
                Actor(method = ClocksTest::b.javaMethod!!, arguments = emptyList())
            ),
            listOf(
                Actor(method = ClocksTest::c.javaMethod!!, arguments = emptyList()),
                Actor(method = ClocksTest::d.javaMethod!!, arguments = emptyList())
            )
        ),
        emptyList()
    )

}

class ClocksTestSequential {
    private var x = 0

    fun a() {
        x = 1
    }

    fun b() {}
    fun c() {}

    fun d(): Int = x
}
