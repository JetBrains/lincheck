/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.verifier.linearizability

import kotlinx.atomicfu.*
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.datastructures.scenario
import org.junit.*

class LockFreeSetTest {

    // TODO: investigate if it is possible to make this test more stable
    //   https://github.com/JetBrains/lincheck/issues/607
    @Ignore("This test is flaky")
    @Test(expected = AssertionError::class)
    fun test() {
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
            .invocationsPerIteration(20_000_000)
            .iterations(0)
            .check(LockFreeSet::class)
    }
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