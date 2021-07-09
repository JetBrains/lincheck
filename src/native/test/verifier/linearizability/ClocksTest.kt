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
package verifier.linearizability

import AbstractLincheckStressTest
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.strategy.*

interface Clocks {
    fun a()
    fun b()
    fun c()
    fun d(): Int
}

class ClocksTest : AbstractLincheckStressTest<Clocks>(IncorrectResultsFailure::class), Clocks {
    private var bStarted = false

    override fun a() {
        // do nothing
    }

    override fun b() {
        bStarted = true
    }

    override fun c() {
        while (!bStarted) {
        } // wait until `a()` is completed
    }

    override fun d(): Int {
        return 0 // cannot return 0, should fail
    }

    override fun <T : LincheckStressConfiguration<Clocks>> T.customize() {
        executionGenerator { c: CTestConfiguration, s: CTestStructure -> ClocksTestScenarioGenerator(c, s) }
        iterations(1)
        invocationsPerIteration(500)
        sequentialSpecification(SequentialSpecification<Clocks> { ClocksTestSequential() })
        requireStateEquivalenceImplCheck(false)
        minimizeFailedScenario(false)

        initialState { ClocksTest() }

        operation(Clocks::a)
        operation(Clocks::b)
        operation(Clocks::c)
        operation(Clocks::d)
    }
}

class ClocksTestScenarioGenerator(testCfg: CTestConfiguration, testStructure: CTestStructure)
    : ExecutionGenerator(testCfg, testStructure) {
    override fun nextExecution() = ExecutionScenario(
        emptyList(),
        listOf(
            listOf(
                Actor(function = {instance, _ -> (instance as Clocks).a()}, arguments = emptyList()),
                Actor(function = {instance, _ -> (instance as Clocks).b()}, arguments = emptyList())
            ),
            listOf(
                Actor(function = {instance, _ -> (instance as Clocks).c()}, arguments = emptyList()),
                Actor(function = {instance, _ -> (instance as Clocks).d()}, arguments = emptyList())
            )
        ),
        emptyList()
    )

}

class ClocksTestSequential : Clocks {
    private var x = 0

    override fun a() {
        x = 1
    }

    override fun b() {}
    override fun c() {}

    override fun d(): Int = x
}
