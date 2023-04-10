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

package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import kotlinx.atomicfu.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.stress.*
import org.junit.*

class LockFreeSetTest {
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
            .invocationsPerIteration(1000000)
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