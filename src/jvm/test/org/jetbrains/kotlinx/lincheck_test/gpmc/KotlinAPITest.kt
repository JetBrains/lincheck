/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.gpmc

import org.jetbrains.kotlinx.lincheck.ExperimentalModelCheckingAPI
import org.jetbrains.kotlinx.lincheck.runConcurrentTest
import org.junit.Assert
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@OptIn(ExperimentalModelCheckingAPI::class)
class KotlinAPITest {

    @Test
    fun test() = runConcurrentTest {
        JavaAPITest.testImpl()
    }

    @Test
    fun test2() = runConcurrentTest {
        val deque = ConcurrentLinkedDeque<Int>()
        var r1: Int = -1
        var r2: Int = -1
        deque.addLast(1)

        val t1 = Thread {
            r1 = deque.pollFirst()
        }
        val t2 = Thread {
            deque.addFirst(0)
            r2 = deque.peekLast()
        }

        t1.join()
        t2.join()

        Assert.assertTrue(!(r1 == 1 && r2 == 1))
    }

    @Test
    fun test5() = runConcurrentTest {
        val deque = ConcurrentLinkedDeque<Int>()
        var r1: Int = -1
        var r2: Int = -1
        deque.addLast(1)

        ConcurrentHashMap<String, String>()

        val t1 = thread {
            r1 = deque.pollFirst()
        }
        val t2 = thread {
            deque.addFirst(0)
            r2 = deque.peekLast()
        }

        t1.join()
        t2.join()

        Assert.assertTrue(!(r1 == 1 && r2 == 1))
    }

    @Test
    fun test4() = runConcurrentTest {
        val deque = ConcurrentLinkedDeque<Int>()
        var r1: Int = -1
        var r2: Int = -1
        deque.addLast(1)

        val t1 = Thread {
            r1 = deque.pollFirst()
        }
        val t2 = Thread {
            deque.addFirst(0)
            r2 = deque.peekLast()
        }

        t1.start()
        t2.start()

        t1.join()
        t2.join()

        Assert.assertTrue(!(r1 == 1 && r2 == 1))
    }

    @Test
    fun test3() = runConcurrentTest {
        val deque = ConcurrentLinkedDeque<Int?>()
        val r1 = AtomicInteger(-1)
        val r2 = AtomicInteger(-1)

        deque.addLast(1)

        val t1 = Thread {
            r1.set(deque.pollFirst()!!)
        }
        val t2 = Thread {
            deque.addFirst(0)
            r2.set(deque.peekLast()!!)
        }

        t1.start()
        t2.start()

        try {
            t1.join()
            t2.join()
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }
        Assert.assertFalse(r1.get() == 1 && r2.get() == 1)
    }

    fun testImpl() {
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
        Assert.assertTrue(!(r1 == 1 && r2 == 1))
    }
}
