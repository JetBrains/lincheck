/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.strategy.modelchecking.snapshot

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.lincheck.datastructures.ManagedOptions
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import java.util.PriorityQueue
import java.util.Queue
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random


abstract class CollectionSnapshotTest : AbstractSnapshotTest() {

    override fun testModelChecking() = ModelCheckingOptions()
        .actorsBefore(0)
        .actorsAfter(0)
        .iterations(100)
        .invocationsPerIteration(1)
        .threads(1)
        .actorsPerThread(10)
        .analyzeStdLib(true)
        .apply { customize() }
        .check(this::class)

    @Operation
    open fun addElement() {}

    @Operation
    open fun removeElement() {}

    @Operation
    open fun updateElement() {}

    @Operation
    open fun clear() {}

    @Operation
    open fun reassign() {}
}

class SetSnapshotTest : CollectionSnapshotTest() {
    companion object {
        private class Wrapper(var value: Int)
        private var staticSet = mutableSetOf<Wrapper>()

        // remember values for restoring
        private val ref = staticSet
        private val a = Wrapper(1)
        private val b = Wrapper(2)
        private val c = Wrapper(3)
        init {
            staticSet.addAll(listOf(a, b, c))
        }
    }

    class SetVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)
            check(staticSet == ref)
            check(staticSet.size == 3 && staticSet.containsAll(listOf(a, b, c)))
            check(a.value == 1 && b.value == 2 && c.value == 3)
            return true
        }
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        verifier(SetVerifier::class.java)
    }

    @Operation
    override fun addElement() {
        staticSet.add(Wrapper(Random.nextInt()))
    }

    @Operation
    override fun removeElement() {
        staticSet.remove(staticSet.randomOrNull() ?: return)
    }

    @Operation
    override fun updateElement() {
        staticSet.randomOrNull()?.value = Random.nextInt()
    }

    @Operation
    override fun clear() {
        staticSet.clear()
    }

    @Operation
    override fun reassign() {
        staticSet = mutableSetOf<Wrapper>()
    }
}

class MapSnapshotTest : CollectionSnapshotTest() {
    companion object {
        private class Wrapper(var value: Int)
        private var staticMap = mutableMapOf<Int, Wrapper>()

        // remember values for restoring
        private val ref = staticMap
        private val a = Wrapper(1)
        private val b = Wrapper(2)
        private val c = Wrapper(3)
        init {
            staticMap.put(1, a)
            staticMap.put(2, b)
            staticMap.put(3, c)
        }
    }

    class MapVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)
            check(staticMap == ref)
            check(staticMap.size == 3 && staticMap[1] == a && staticMap[2] == b && staticMap[3] == c)
            check(a.value == 1 && b.value == 2 && c.value == 3)
            return true
        }
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        verifier(MapVerifier::class.java)
    }

    @Operation
    override fun addElement() {
        staticMap.put(Random.nextInt(), Wrapper(Random.nextInt()))
    }

    @Operation
    override fun removeElement() {
        staticMap.remove(staticMap.keys.randomOrNull() ?: return)
    }

    @Operation
    override fun updateElement() {
        staticMap.entries.randomOrNull()?.value?.value = Random.nextInt()
    }

    @Operation
    override fun clear() {
        staticMap.clear()
    }

    @Operation
    override fun reassign() {
        staticMap = mutableMapOf<Int, Wrapper>()
    }
}

class ListSnapshotTest : CollectionSnapshotTest() {
    companion object {
        private class Wrapper(var value: Int)
        private var staticList = mutableListOf<Wrapper>()

        // remember values for restoring
        private val ref = staticList
        private val a = Wrapper(1)
        private val b = Wrapper(2)
        private val c = Wrapper(3)
        init {
            staticList.addAll(listOf(a, b, c))
        }
    }

    class ListVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)
            check(staticList == ref)
            check(staticList.size == 3 && staticList[0] == a && staticList[1] == b && staticList[2] == c)
            check(a.value == 1 && b.value == 2 && c.value == 3)
            return true
        }
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        verifier(ListVerifier::class.java)
    }

    @Operation
    override fun addElement() {
        staticList.add(Wrapper(Random.nextInt()))
    }

    @Operation
    override fun removeElement() {
        staticList.remove(staticList.randomOrNull() ?: return)
    }

    @Operation
    override fun updateElement() {
        staticList.randomOrNull()?.value = Random.nextInt()
    }

    @Operation
    override fun clear() {
        staticList.clear()
    }

    @Operation
    override fun reassign() {
        staticList = mutableListOf()
    }
}

class PriorityQueueSnapshotTest : CollectionSnapshotTest() {
    companion object {
        private var staticQueue: Queue<Int> = PriorityQueue()

        // remember values for restoring
        private val ref = staticQueue
        init {
            staticQueue.addAll(listOf(3, 1, 2))
        }
    }

    class PriorityQueueVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)
            check(staticQueue == ref)
            check(staticQueue.size == 3 && staticQueue.containsAll(listOf(1, 2, 3)))
            var i = 1
            while (staticQueue.isNotEmpty()) {
                check(staticQueue.remove() == i++)
            }
            staticQueue.addAll(listOf(3, 1, 2))
            return true
        }
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        verifier(PriorityQueueVerifier::class.java)
    }

    @Operation
    override fun addElement() {
        staticQueue.add(Random.nextInt())
    }

    @Operation
    override fun removeElement() {
        staticQueue.remove(staticQueue.randomOrNull() ?: return)
    }

    @Operation
    override fun clear() {
        staticQueue.clear()
    }

    @Operation
    override fun reassign() {
        staticQueue = PriorityQueue<Int>()
    }
}

class ConcurrentMapSnapshotTest : CollectionSnapshotTest() {
    companion object {
        private class Wrapper(var value: Int)
        private var staticCMap = ConcurrentHashMap<Int, Wrapper>()

        // remember values for restoring
        private val ref = staticCMap
        private val a = Wrapper(1)
        private val b = Wrapper(2)
        private val c = Wrapper(3)
        init {
            staticCMap.putAll(mapOf(
                1 to a,
                2 to b,
                3 to c
            ))
        }
    }

    class ConcurrentMapVerifier(@Suppress("UNUSED_PARAMETER") sequentialSpecification: Class<*>) : SnapshotVerifier() {
        override fun verifyResults(scenario: ExecutionScenario?, results: ExecutionResult?): Boolean {
            checkForExceptions(results)
            check(staticCMap == ref)
            check(staticCMap.size == 3 && staticCMap[1] == a && staticCMap[2] == b && staticCMap[3] == c)
            check(a.value == 1 && b.value == 2 && c.value == 3)
            return true
        }
    }

    override fun <O : ManagedOptions<O, *>> O.customize() {
        verifier(ConcurrentMapVerifier::class.java)
    }

    @Operation
    override fun addElement() {
        staticCMap.put(Random.nextInt(), Wrapper(Random.nextInt()))
    }

    @Operation
    override fun updateElement() {
        staticCMap.values.randomOrNull()?.value = Random.nextInt()
    }

    @Operation
    override fun removeElement() {
        staticCMap.remove(staticCMap.keys.randomOrNull() ?: return)
    }

    @Operation
    override fun clear() {
        staticCMap.clear()
    }

    @Operation
    override fun reassign() {
        staticCMap = ConcurrentHashMap<Int, Wrapper>()
    }
}