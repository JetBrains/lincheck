/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package fuzzing

import fuzzing.utils.AbstractFuzzerBenchmarkTest
import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.LoggingLevel
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.scenario
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Test
import kotlin.reflect.jvm.jvmName


class LockFreeSetTest : AbstractFuzzerBenchmarkTest() {
    val set = LockFreeSet()

    @Operation
    fun snapshot() = set.snapshot()

    @Operation
    fun add(key: Int) = set.add(key)

    @Operation
    fun remove(key: Int) = set.remove(key)

    //@Test(expected = AssertionError::class)
    fun failingCustomScenarioTest() {
        val scenario = scenario {
            parallel {
                thread {
                    repeat(3) {
                        actor(LockFreeSet::snapshot)
                    }
                }
                thread {
                    repeat(4) {
                        for (key in 1..2) {
                            actor(LockFreeSet::add, key)
                            actor(LockFreeSet::remove, key)
                        }
                    }
                }
            }
        }

        StressOptions()
            .addCustomScenario(scenario)
            .invocationsPerIteration(1000000)
            .iterations(0)
            .logLevel(LoggingLevel.INFO)
            .check(LockFreeSet::class)
    }

    override fun <O: Options<O, *>> O.customize() {
        threads(2)
        actorsPerThread(13)
        actorsBefore(0)
        actorsAfter(0)
    }

    override fun <O : Options<O, *>> O.customizeModelCheckingCoverage() {
        logLevel(LoggingLevel.INFO)
        coverageConfigurationForModelChecking(
            listOf(this@LockFreeSetTest::class.jvmName),
            emptyList()
        )
    }

    override fun <O : Options<O, *>> O.customizeFuzzingCoverage() {
        coverageConfigurationForFuzzing(
            listOf(this@LockFreeSetTest::class.jvmName),
            emptyList()
        )
    }

//    @Test(expected = AssertionError::class)
//    fun test() {
//        ModelCheckingOptions()
//            .actorsPerThread(16)
//            .actorsBefore(0)
//            .actorsAfter(0)
//            .withCoverage(CoverageOptions(
//                excludePatterns = listOf(this::class.jvmName),
//                fuzz = true
//            ) { pr, res ->
//                println("ModelChecking: line=${res.lineCoverage}/${res.totalLines}, " +
//                        "branch=${res.branchCoverage}/${res.totalBranches}, " +
//                        "edges=${pr.toCoverage().coveredBranchesCount()}")
//            })
//            .check(this::class)
//    }
}

class LockFreeSet {
    private val head = Node(Int.MIN_VALUE, null, true) // dummy node

    fun add(key: Int): Boolean {
        var node = head
        while (true) {
            while (true) {
                node = node.next.value ?: break
                if (node.key == key) {
                    return if (node.isDeleted.value)
                        node.isDeleted.compareAndSet(true, false)
                    else
                        false
                }
            }
            val newNode = Node(key, null, false)
            if (node.next.compareAndSet(null, newNode))
                return true
        }
    }

    fun remove(key: Int): Boolean {
        var node = head
        while (true) {
            node = node.next.value ?: break
            if (node.key == key) {
                return if (node.isDeleted.value)
                    false
                else
                    node.isDeleted.compareAndSet(false, true)
            }
        }
        return false
    }

    /**
     * This snapshot implementation is incorrect,
     * but the minimal concurrent scenario to reproduce
     * the error is quite large.
     */
    fun snapshot(): List<Int> {
        while (true) {
            val firstSnapshot = doSnapshot()
            val secondSnapshot = doSnapshot()
            if (firstSnapshot == secondSnapshot)
                return firstSnapshot
        }
    }

    private fun doSnapshot(): List<Int> {
        val snapshot = mutableListOf<Int>()
        var node = head
        while (true) {
            node = node.next.value ?: break
            if (!node.isDeleted.value)
                snapshot.add(node.key)
        }
        return snapshot
    }

    private inner class Node(val key: Int, next: Node?, initialMark: Boolean) {
        val next = atomic(next)
        val isDeleted = atomic(initialMark)
    }
}