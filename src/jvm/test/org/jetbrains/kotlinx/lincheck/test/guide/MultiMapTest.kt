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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.paramgen.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.forClasses
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.junit.*
import java.util.concurrent.*

class MultiMap<K, V> {
    private val map = ConcurrentHashMap<K, List<V>>()

    // adds the value to the list by the given key
    // contains the race :(
    fun addBroken(key: K, value: V) {
        val list = map[key]
        if (list == null) {
            map[key] = listOf(value)
        } else {
            map[key] = list + value
        }
    }

    // correct implementation
    fun add(key: K, value: V) {
        map.compute(key) { _, list ->
            if (list == null) listOf(value) else list + value
        }
    }

    fun get(key: K): List<V> = map[key] ?: emptyList()
}

@Param(name = "key", gen = IntGen::class, conf = "1:1")
class MultiMapTest {
    private val map = MultiMap<Int, Int>()

    //@Operation
    fun add(@Param(name = "key") key: Int, value: Int) = map.add(key, value) // correct implementation of add

    @Operation
    fun addBroken(@Param(name = "key") key: Int, value: Int) = map.addBroken(key, value)

    @Operation
    fun get(@Param(name = "key") key: Int) = map.get(key)

    @Test
    fun stressTest() {
        StressOptions().checkImpl(this::class.java).also {
            assert(it is IncorrectResultsFailure)
        }
    }

    @Test
    fun modelCheckingTest() {
        ModelCheckingOptions()
            .addGuarantee(forClasses(ConcurrentHashMap::class.qualifiedName!!).allMethods().treatAsAtomic())
            // Note, that with atomicity guarantees set, all possible interleaving in the MultiMap can be examined,
            // you can ensure the test to pass when the number of invocations is set to the max value.
            // Otherwise, if you try to examine all interleaving without atomic guarantees for ConcurrentHashMap,
            // the test will most probably fail with the lack of memory
            // because of the huge amount of possible context switches to be checked.
            .invocationsPerIteration(Int.MAX_VALUE)
            .checkImpl(this::class.java).also {
                assert(it is IncorrectResultsFailure)
            }
    }
}