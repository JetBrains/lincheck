/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.junit.*
import kotlin.reflect.jvm.*


@StressCTest(generator = ClocksTestScenarioGenerator::class, iterations = 1,
             actorsBefore = 0, threads = 2, actorsPerThread = 1, actorsAfter = 0,
             sequentialSpecification = ClocksTestSequential::class,
             requireStateEquivalenceImplCheck = false, minimizeFailedScenario = false)
class ClocksTest {
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

    fun d(): Int {
        return 0 // cannot return 0, should fail
    }

    // the execution is sequential consistent but not linearizable
    @Test
    fun test() = try {
        LinChecker.check(this::class.java)
        throw AssertionError("Should fail since the execution with clocks is " +
                             "sequential consistent but not linearizable")
    } catch (e: LincheckAssertionError) {
        assert(e.message!!.contains("Invalid execution results")) {
            "Should fail because of the invalid interleaving, but the following exception was thrown:\n" + e.message
        }
    }
}

class ClocksTestScenarioGenerator(testCfg: CTestConfiguration, testStructure: CTestStructure)
    : ExecutionGenerator(testCfg, testStructure)
{
    override fun nextExecution() = ExecutionScenario(
        emptyList(),
        listOf(
            listOf(
                Actor(ClocksTest::a.javaMethod!!, emptyList(), emptyList(), false),
                Actor(ClocksTest::b.javaMethod!!, emptyList(), emptyList(), false)
            ),
            listOf(
                Actor(ClocksTest::c.javaMethod!!, emptyList(), emptyList(), false),
                Actor(ClocksTest::d.javaMethod!!, emptyList(), emptyList(), false)
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
