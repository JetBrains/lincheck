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

package org.jetbrains.kotlinx.lincheck.test.transformation.crash.distribution

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
}
