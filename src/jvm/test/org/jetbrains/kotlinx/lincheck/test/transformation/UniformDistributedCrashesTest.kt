/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.test.transformation

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Recoverable
import org.jetbrains.kotlinx.lincheck.createFromTestClassAnnotations
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.runner.CompletedInvocationResult
import org.jetbrains.kotlinx.lincheck.runner.Runner
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTestConfiguration
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressStrategy
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random


@StressCTest.StressCTests(
    StressCTest(recover = Recover.DURABLE),
    StressCTest(recover = Recover.NRL)
)
class SequentialCodeTest : VerifierState() {
    val s = SequentialCodeClass()

    @Operation
    fun foo() = s.foo()
    override fun extractState() = s.x
}

class SequentialCodeClass {
    val x = nonVolatile(0)

    @Recoverable
    fun foo() {
        x.flush()
        x.flush()
        x.flush()
        x.flush()
        x.flush()
        x.flush()
        x.flush()
        x.flush()
        x.flush()
        x.flush()
    }
}


class UniformDistributedCrashesTest {
    @Ignore("To be fixed")
    @Test
    fun integrationTestCrashesAreUniformlyDistributed() {
        val k = 10
        val n = 1000000
        val testClass = SequentialCodeTest::class.java
        val actor = Actor(testClass.getMethod("foo"), mutableListOf())
        val scenario = ExecutionScenario(emptyList(), listOf(listOf(actor)), emptyList())
        for (testConfiguration in createFromTestClassAnnotations(testClass)) {
            println((testConfiguration as StressCTestConfiguration).recoverabilityModel)
            val strategy = testConfiguration
                .createStrategy(testClass, scenario, emptyList(), null, EpsilonVerifier(testClass))
            val runner = StressStrategy::class.java
                .getDeclaredField("runner")
                .also { it.isAccessible = true }
                .get(strategy) as Runner
            val crashLines = hashMapOf<Int, Int>()
            repeat(n) {
                val res = runner.run()
                check(res is CompletedInvocationResult)
                res.results.crashes.flatten().forEach { e ->
                    val line = e.crashStackTrace[0].lineNumber
                    crashLines[line] = (crashLines[line] ?: 0) + 1
                }
            }
            val expected = crashLines.values.sum() / k.toDouble()
            assertEquals(k, crashLines.size)
            crashLines.toSortedMap().forEach { (k, v) -> println("$k -- $v, dev=${(v - expected) / expected}") }
            crashLines.toSortedMap().values.forEach { c ->
                val deviation = (c - expected) / expected
                assertTrue("Deviation is $deviation: expected $expected crashes but $c found", abs(deviation) < 0.05)
            }
        }
    }

    @Test
    fun testUniformDistribution() {
        val random = Random(42)
        repeat(100) {
            val n = random.nextInt(1, 10)
            val actors = List(n) { random.nextInt(1, 20) }
            val recovery = List(n) { random.nextInt(0, 20) }
            val maxE = (actors.sum() + recovery.sum()) / actors.maxOrNull()!!.toDouble()
            val expected = random.nextDouble(0.99, maxE)
            testScenarioUniformDistribution(expected, actors, recovery, random)
        }
    }

    private fun testScenarioUniformDistribution(
        expectedCrashes: Double, actorLengths: List<Int>,
        recoveryLengths: List<Int>, random: Random
    ) {
        val c = expectedCrashes / (actorLengths.sum() + recoveryLengths.sum())
        val n = actorLengths.size + recoveryLengths.size
        val iterations = 1_000_000
        val total = actorLengths.sum() + recoveryLengths.sum()
        val results = IntArray(total)
        val crashes = mutableListOf<Int>()
        repeat(iterations) {
            var i = 0
            var j = 0
            var passed = 0
            var ic = 0
            while (i < n) {
                val inActor = i and 1 == 0
                if (inActor) {
                    if (j == actorLengths[i / 2]) {
                        passed += recoveryLengths[i / 2]
                        i += 2
                        j = 0
                        continue
                    }
                } else {
                    if (j == recoveryLengths[i / 2]) {
                        i++
                        j = 0
                        continue
                    }
                }
                val f = if (inActor) c else 1.0 / (actorLengths[i / 2] + recoveryLengths[i / 2])
                val crash = random.nextDouble() < f / (1 - j * f)
                if (crash) {
                    results[passed]++
                    if (inActor) {
                        passed += actorLengths[i / 2] - j
                        i++
                    } else {
                        passed -= j
                    }
                    j = 0
                    ic++
                } else {
                    j++
                    passed++
                }
            }
            crashes.add(ic)
        }
        val averageCrashesPerScenario = crashes.average()
        val d = crashes.sumByDouble { x -> (x - averageCrashesPerScenario).pow(2) } / (iterations - 1)
        println("sqrt(D) / E = ${d / expectedCrashes}")
        assertTrue(abs(averageCrashesPerScenario - expectedCrashes) / expectedCrashes < 0.05)
        val expected = results.sum() / total.toDouble()
        results.forEach { cr ->
            val deviation = (cr - expected) / expected
            assertTrue(abs(deviation) < 0.05)
        }
    }
}
