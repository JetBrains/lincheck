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
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.runConcurrentTest
import kotlin.concurrent.thread
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert
import org.junit.Test

@OptIn(ExperimentalModelCheckingAPI::class)
class KotlinAPITest {

    @Test(expected = LincheckAssertionError::class)
    fun `test Kotlin thread`() = runConcurrentTest {
        var r1: Int = -1
        var r2: Int = -1

        val deque = ConcurrentLinkedDeque<Int>()
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

        assert(!(r1 == 1 && r2 == 1))
    }

    @Test(expected = LincheckAssertionError::class)
    fun `test Kotlin thread(start=false)`() = runConcurrentTest {
        var r1: Int = -1
        var r2: Int = -1

        val deque = ConcurrentLinkedDeque<Int>()
        deque.addLast(1)

        val t1 = thread(start = false) {
            r1 = deque.pollFirst()
        }
        val t2 = thread(start = false) {
            deque.addFirst(0)
            r2 = deque.peekLast()
        }

        t1.start()
        t2.start()
        t1.join()
        t2.join()

        assert(!(r1 == 1 && r2 == 1))
    }

    @Test(expected = LincheckAssertionError::class)
    fun `test Java Thread`() = runConcurrentTest {
        var r1: Int = -1
        var r2: Int = -1

        val deque = ConcurrentLinkedDeque<Int>()
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

        assert(!(r1 == 1 && r2 == 1))
    }

    @Test(expected = LincheckAssertionError::class)
    fun `test Java Thread and InterruptedException handling`() = runConcurrentTest {
        var r1: Int = -1
        var r2: Int = -1

        val deque = ConcurrentLinkedDeque<Int>()
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

        try {
            t1.join()
            t2.join()
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }

        assert(!(r1 == 1 && r2 == 1))
    }

    @Test(expected = LincheckAssertionError::class)
    fun `test Java Thread and AtomicInteger`() = runConcurrentTest {
        val r1 = AtomicInteger(-1)
        val r2 = AtomicInteger(-1)

        val deque = ConcurrentLinkedDeque<Int?>()
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
        t1.join()
        t2.join()

        Assert.assertFalse(r1.get() == 1 && r2.get() == 1)
    }

    @Test(expected = LincheckAssertionError::class)
    fun `test method reference`() = runConcurrentTest(block = ::testImpl)

    private fun testImpl() {
        var r1: Int = -1
        var r2: Int = -1

        val deque = ConcurrentLinkedDeque<Int>()
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

        assert(!(r1 == 1 && r2 == 1))
    }

    @Test(expected = LincheckAssertionError::class)
    fun `test Kotlin check`() = runConcurrentTest {
        var r1: Int = -1
        var r2: Int = -1

        val deque = ConcurrentLinkedDeque<Int>()
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

    @Test(expected = LincheckAssertionError::class)
    fun `test JUnit assert`() = runConcurrentTest {
        var r1: Int = -1
        var r2: Int = -1

        val deque = ConcurrentLinkedDeque<Int>()
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

        Assert.assertFalse(r1 == 1 && r2 == 1)
    }
}
