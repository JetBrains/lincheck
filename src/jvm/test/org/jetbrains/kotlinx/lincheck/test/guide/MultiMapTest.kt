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

package org.jetbrains.kotlinx.lincheck.test.guide

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.forClasses
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.set

class MultiMap<K, V> {
    private val map = ConcurrentHashMap<K, List<V>>()

    // Maintains a list of values associated with the specified key.
    // Contains the race :(
    fun add(key: K, value: V) {
        val list = map[key]
        if (list == null) {
            map[key] = listOf(value)
        } else {
            map[key] = list + value
        }
    }

    // Correct implementation.
    fun addCorrect(key: K, value: V) {
        map.compute(key) { _, list ->
            if (list == null) listOf(value) else list + value
        }
    }

    fun get(key: K): List<V> = map[key] ?: emptyList()
}

@Param(name = "key", gen = IntGen::class, conf = "1:1")
class MultiMapTest {
    private val map = MultiMap<Int, Int>()

    @Operation
    fun add(@Param(name = "key") key: Int, value: Int) = map.add(key, value) // correct add implementation

    // @Operation TODO: Please, uncomment me and comment the @Operation annotation above the `add` function to make the test pass
    fun addCorrect(@Param(name = "key") key: Int, value: Int) = map.addCorrect(key, value)

    @Operation
    fun get(@Param(name = "key") key: Int) = map.get(key)

    // @Test TODO: Please, uncomment me and comment the line below to run the test and get the output
    @Test(expected = AssertionError::class)
    fun stressTest() = StressOptions().check(this::class)

    // @Test TODO: Please, uncomment me and comment the line below to run the test and get the output
    @Test(expected = AssertionError::class)
    fun modelCheckingTest() = ModelCheckingOptions().check(this::class)

    // @Test TODO: Please, uncomment me and comment the line below to run the test and get the output
    @Ignore
    @Test(expected = AssertionError::class)
    fun modularTest() = ModelCheckingOptions()
        .addGuarantee(forClasses(ConcurrentHashMap::class).allMethods().treatAsAtomic())
        // Note that with the atomicity guarantees set, Lincheck can examine all possible interleavings,
        // so the test successfully passes when the number of invocations is set to `Int.MAX_VALUE`
        // If you comment the line above, the test takes a lot of time and likely fails with `OutOfMemoryError`.
        .invocationsPerIteration(Int.MAX_VALUE)
        .check(this::class)
}