/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc

import org.jetbrains.lincheck.util.isInTraceDebuggerMode
import kotlin.concurrent.thread
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import org.junit.Test


class DataStructuresTests {

    fun incorrectConcurrentLinkedDeque() {
        val deque = ConcurrentLinkedDeque<Int>()
        var r1: Int = -1
        var r2: Int = -1
        deque.addLast(1)
        val t1 = thread {
            r1 = deque.pollFirst()
        }
        val t2 = thread {
            deque.addFirst(0)
            r2 = deque.peekLast()
        }
        t1.join()
        t2.join()
        check(!(r1 == 1 && r2 == 1))
    }

    @Test(timeout = TIMEOUT)
    fun incorrectConcurrentLinkedDequeTest() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::incorrectConcurrentLinkedDeque,
        expectedExceptions = setOf(IllegalStateException::class),
        invocations = 1_000,
        stdLibAnalysis = true,
    )

    fun incorrectHashMap() {
        val hashMap = HashMap<Int, Int>()
        var r1: Int? = null
        var r2: Int? = null
        val t1 = thread {
            r1 = hashMap.put(0, 1)
        }
        val t2 = thread {
            r2 = hashMap.put(0, 1)
        }
        t1.join()
        t2.join()
        check(!(r1 == null && r2 == null))
    }

    @Test(timeout = TIMEOUT)
    fun incorrectHashMapTest() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::incorrectHashMap,
        expectedExceptions = setOf(IllegalStateException::class),
        invocations = 1_000,
        stdLibAnalysis = true,
    )

    fun correctConcurrentHashMap() {
        val concurrentHashMap = ConcurrentHashMap<Int, Int>()
        var r1: Int? = -1
        var r2: Int? = -1
        val t1 = thread {
            r1 = concurrentHashMap.put(0, 1)
        }
        val t2 = thread {
            r2 = concurrentHashMap.put(0, 1)
        }
        t1.join()
        t2.join()
        check(!(r1 == null && r2 == null))
    }

    @Test(timeout = TIMEOUT)
    fun correctConcurrentHashMapTest() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::correctConcurrentHashMap,
        expectedOutcomes = setOf(),
        invocations = 1_000,
        stdLibAnalysis = true,
    )

    fun correctConcurrentSkipListMap() {
        val concurrentSkipListMap = ConcurrentSkipListMap<Int, Int>()
        var r1: Int? = -1
        var r2: Int? = -1
        val t1 = thread {
            r1 = concurrentSkipListMap.put(0, 1)
        }
        val t2 = thread {
            r2 = concurrentSkipListMap.put(0, 1)
        }
        t1.join()
        t2.join()
        check(!(r1 == null && r2 == null))
    }

    @Test(timeout = TIMEOUT)
    fun correctConcurrentSkipListMapTest() = modelCheckerTest(
        testClass = this::class,
        testOperation = this::correctConcurrentSkipListMap,
        expectedOutcomes = setOf(),
        invocations = if (isInTraceDebuggerMode) 1 else 1_000,
        stdLibAnalysis = true,
    )

}