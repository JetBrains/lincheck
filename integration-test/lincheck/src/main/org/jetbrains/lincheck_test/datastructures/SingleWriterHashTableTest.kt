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

import org.jetbrains.kotlinx.lincheck_test.TIMEOUT
import org.jetbrains.lincheck.datastructures.*
import kotlin.reflect.KClass
import org.junit.Test

class SingleWriterHashTableTest() {
    val scenarios: Int = 100
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
    fun modelCheckingTest() = ModelCheckingOptions()
        .iterations(scenarios)
        .invocationsPerIteration(10_000)
        .actorsBefore(actorsBefore)
        .threads(threads)
        .actorsPerThread(2)
        .actorsAfter(0)
        .checkObstructionFreedom(checkObstructionFreedom)
        .sequentialSpecification(sequentialSpecification.java)
        .check(this::class.java)

    @Test(timeout = TIMEOUT, expected = AssertionError::class)
    fun stressTest() = StressOptions()
        .iterations(scenarios)
        .invocationsPerIteration(25_000)
        .actorsBefore(actorsBefore)
        .threads(threads)
        .actorsPerThread(2)
        .actorsAfter(0)
        .sequentialSpecification(sequentialSpecification.java)
        .check(this::class.java)
}

internal class SequentialHashTableIntInt {
    private val map = HashMap<Int, Int>()

    fun put(key: Int, value: Int): Int? = map.put(key, value)

    fun get(key: Int): Int? = map.get(key)

    fun remove(key: Int): Int? = map.remove(key)
}