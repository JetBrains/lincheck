/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.test

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.runner.DeadlockInvocationResult
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

class HangingTest : AbstractLincheckTest(DeadlockWithDumpFailure::class) {
    @Operation
    fun badOperation() {
        while (true) {}
    }

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        actorsAfter(0)
        requireStateEquivalenceImplCheck(false)
        minimizeFailedScenario(false)
        invocationTimeout(100)
    }

}

/* This test checks that strategy correctly aborts hanging invocations so that
 * these invocations do not interfere with subsequent invocations (e.g. by writing to global shared memory).
 */
class HangingAbortTest {

    companion object {
        private var global: Int = 0
    }

    private val counter = AtomicInteger(0)

    @Operation
    fun badOperation() {
        check(global == 0)
        // some dummy code with shared memory accesses to create switch points
        repeat(10) { counter.incrementAndGet() }
        // TODO: model checking strategy may eliminate `sleep()` calls,
        //   we need a more robust approach to enforce the behavior that we expect here.
        Thread.sleep(200)
        global = 1
    }

    private val testScenario = scenario {
        parallel {
            thread {
                actor(HangingAbortTest::badOperation)
            }
            thread {
                actor(HangingAbortTest::badOperation)
            }
        }
    }

    /* TODO: currently we can only guarantee the abort behavior from the model checking strategy,
     *   because this strategy can implement necessary checks before all potentially dangerous interfering operations
     *   (e.g. accesses to global shared memory).
     *   Because stress strategy should use more lightweight instrumentation we cannot afford (?)
     *   to perform similar checks before every interesting operation (e.g. before every access to global shared memory).
     *   We need to think about potential solutions for this problem that would work for stress strategy.
     */
    @Test
    fun modelCheckingTest() {
        // repeat the test several times in order to try to check that aborted invocation
        // does not interfere with the subsequent invocations
        val strategy = createStrategy()
        repeat(10) { i ->
            val (result, _) = strategy.runNextExploration()
                ?: return
            println("Iteration $i:")
            assert(result is DeadlockInvocationResult)
        }
    }

    fun EventStructureOptions.customize() {
        // invocationsPerIteration(10)
        requireStateEquivalenceImplCheck(false)
        minimizeFailedScenario(false)
        invocationTimeout(100)
    }

    private fun createConfiguration() =
        EventStructureOptions()
            .apply { customize() }
            .createTestConfigurations(this::class.java)

    private fun createStrategy(): EventStructureStrategy {
        return createConfiguration()
            .createStrategy(
                testClass = this::class.java,
                scenario = testScenario,
                verifier = EpsilonVerifier(this::class.java),
                validationFunctions = listOf(),
                stateRepresentationMethod = null,
            )
    }

}
