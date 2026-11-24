/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck_test.datastructures

import org.jetbrains.lincheck.Lincheck
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.LincheckSettings.TestingStrategy
import org.jetbrains.kotlinx.lincheck_test.TIMEOUT
import org.jetbrains.lincheck.datastructures.*
import kotlin.reflect.KClass
import org.junit.Test

class SingleWriterHashTableTest() {
    val scenarios: Int = 300
    val threads: Int = 3
    val actorsBefore: Int = 1

    val checkObstructionFreedom: Boolean = true
    val sequentialSpecification: KClass<*> = SequentialHashTableIntInt::class

    private val hashTable = SingleWriterHashTable<Int, Int>(initialCapacity = 30)

    @Operation
    fun put(key: Int, value: Int): Int? = hashTable.put(key, value)

    @Operation
    fun get(key: Int): Int? = hashTable.get(key)

    @Operation
    fun remove(key: Int): Int? = hashTable.remove(key)

    @Test(timeout = TIMEOUT, expected = AssertionError::class)
    fun modelCheckingTest() {
        val settings = commonSettings(10_000).strategy(
            TestingStrategy.ModelChecking().checkObstructionFreedom(checkObstructionFreedom)
        )
        Lincheck.runLincheckTest(settings, this::class.java)
    }

    @Test(timeout = TIMEOUT, expected = AssertionError::class)
    fun eventStructureModelCheckingTest() {
        val settings = commonSettings(10_000).strategy(
            TestingStrategy.ModelChecking()
                .checkObstructionFreedom(checkObstructionFreedom)
                .enablePartialOrderReduction()
        )
        Lincheck.runLincheckTest(settings, this::class.java)
    }

    @Test(timeout = TIMEOUT, expected = AssertionError::class)
    fun stressTest() {
        val settings = commonSettings(25_000).strategy(TestingStrategy.Stress())
        Lincheck.runLincheckTest(settings, this::class.java)
    }

    private fun commonSettings(invocations: Int) = LincheckSettings {
        scenarioGeneration.apply {
            invocationsPerIteration = invocations
            sequentialSpecification = this@SingleWriterHashTableTest.sequentialSpecification.java
            iterations = scenarios
            actorsBefore = this@SingleWriterHashTableTest.actorsBefore
            threads = this@SingleWriterHashTableTest.threads
            actorsPerThread = 2
            actorsAfter = 0
        }
    }

}

internal class SequentialHashTableIntInt {
    private val map = HashMap<Int, Int>()

    fun put(key: Int, value: Int): Int? = map.put(key, value)

    fun get(key: Int): Int? = map.get(key)

    fun remove(key: Int): Int? = map.remove(key)
}
