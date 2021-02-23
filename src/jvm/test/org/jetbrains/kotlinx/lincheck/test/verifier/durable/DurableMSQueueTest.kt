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
package org.jetbrains.kotlinx.lincheck.test.verifier.durable

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LoggingLevel
import org.jetbrains.kotlinx.lincheck.annotations.DurableRecoverPerThread
import org.jetbrains.kotlinx.lincheck.annotations.LogLevel
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.nvm.NonVolatileRef
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.nonVolatile
import org.jetbrains.kotlinx.lincheck.paramgen.ThreadIdGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.test.verifier.linearizability.SequentialQueue
import org.junit.Test

private const val THREADS_NUMBER = 3

/**
 * @see  <a href="http://www.cs.technion.ac.il/~erez/Papers/nvm-queue-full.pdf">A Persistent Lock-Free Queue for Non-Volatile Memory</a>
 */
@StressCTest(
    sequentialSpecification = SequentialQueue::class,
    threads = THREADS_NUMBER,
    recover = Recover.DURABLE
)
@LogLevel(LoggingLevel.INFO)
class DurableMSQueueTest {
    private val q = DurableMSQueue<Int>()

    @Operation
    fun push(value: Int) = q.push(value)

    @Operation
    fun pop(@Param(gen = ThreadIdGen::class) threadId: Int) = q.pop(threadId)

    @DurableRecoverPerThread
    fun recover() = q.recover()

    @Test
    fun test() = LinChecker.check(this::class.java)
}

internal const val DEFAULT_DELETER = -1

internal class QueueNode<T>(
    val next: NonVolatileRef<QueueNode<T>?> = nonVolatile(null),
    val v: T
) {
    val deleter = nonVolatile(DEFAULT_DELETER)
}

private class DurableMSQueue<T> {
    private val head: NonVolatileRef<QueueNode<T?>>
    private val tail: NonVolatileRef<QueueNode<T?>>

    init {
        val dummy = QueueNode<T?>(v = null)
        head = nonVolatile(dummy)
        tail = nonVolatile(dummy)
    }

    fun push(value: T) {
        val newNode = QueueNode<T?>(v = value)
        while (true) {
            val last: QueueNode<T?> = tail.value
            val nextNode: QueueNode<T?>? = last.next.value
            if (last !== tail.value) continue
            if (nextNode === null) {
                if (last.next.compareAndSet(null, newNode)) {
                    last.next.flush()
                    tail.compareAndSet(last, newNode)
                    return
                }
            } else {
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            }
        }
    }

    fun pop(p: Int): T? {
        while (true) {
            val first: QueueNode<T?> = head.value
            val last: QueueNode<T?> = tail.value
            val nextNode: QueueNode<T?>? = first.next.value
            if (first !== head.value) continue
            if (first === last) {
                if (nextNode === null) {
                    return null
                }
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            } else {
                checkNotNull(nextNode)
                val currentValue: T = nextNode.v!!
                if (nextNode.deleter.compareAndSet(DEFAULT_DELETER, p)) {
                    nextNode.deleter.flush()
                    head.compareAndSet(first, nextNode)
                    return currentValue
                } else {
                    if (head.value === first) {
                        nextNode.deleter.flush()
                        head.compareAndSet(first, nextNode)
                    }
                }
            }
        }
    }

    fun recover() {
        while (true) {
            val h = head.value
            val next = h.next.value ?: break
            if (next.deleter.value == DEFAULT_DELETER) break
            next.deleter.flush()
            head.compareAndSet(h, next)
        }
        while (true) {
            val t = tail.value
            val next = t.next.value ?: break
            t.next.flush()
            tail.compareAndSet(t, next)
        }
        check(reachableFrom(head.value, tail.value))
    }

    private fun reachableFrom(start: QueueNode<T?>, node: QueueNode<T?>): Boolean {
        var c = start
        while (true) {
            if (c === node) return true
            c = c.next.value ?: return false
        }
    }
}

@StressCTest(
    sequentialSpecification = SequentialQueue::class,
    threads = THREADS_NUMBER,
    recover = Recover.DURABLE
)
class DurableMSQueueFailingTest {
    private val q = DurableMSQueue<Int>()

    @Operation
    fun push(value: Int) = q.push(value)

    @Operation
    fun pop(@Param(gen = ThreadIdGen::class) threadId: Int) = q.pop(threadId)

    /** This test fails as no recovery is provided. */
    @Test(expected = Throwable::class)
    fun testFails() {
        LinChecker.check(this::class.java)
    }
}

