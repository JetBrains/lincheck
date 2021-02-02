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
import org.jetbrains.kotlinx.lincheck.annotations.LogLevel
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.annotations.Recoverable
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
    private val q = DurableMSQueue<Int>(2 + THREADS_NUMBER)

    @Operation
    fun push(value: Int) = q.push(value)

    @Operation
    fun pop(@Param(gen = ThreadIdGen::class) threadId: Int) = q.pop(threadId)

    @Recoverable
    fun recover() = q.recover()

    @Test
    fun test() = LinChecker.check(this::class.java)
}

private const val DEFAULT_DELETER = -1

private class Node<T>(
    val next: NonVolatileRef<Node<T>?> = nonVolatile(null),
    val v: T
) {
    val deleter = nonVolatile(DEFAULT_DELETER)
}

class DurableMSQueue<T>(threadsCount: Int) {
    private val dummy = Node<T?>(v = null)
    private val head = nonVolatile(dummy)
    private val tail = nonVolatile(dummy)

    private val unknown = Any()
    private val empty = Any()
    private val response = MutableList(threadsCount) { nonVolatile(unknown) }

    fun push(value: T) {
        val newNode = Node<T?>(v = value)
        while (true) {
            val last: Node<T?> = tail.value
            val nextNode: Node<T?>? = last.next.value
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
        response[p].value = unknown
        response[p].flush()

        while (true) {
            val first: Node<T?> = head.value
            val last: Node<T?> = tail.value
            val nextNode: Node<T?>? = first.next.value
            if (first !== head.value) continue
            if (first === last) {
                if (nextNode === null) {
                    response[p].value = empty
                    response[p].flush()
                    return null
                }
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            } else {
                checkNotNull(nextNode)
                val currentValue: T = nextNode.v!!
                if (nextNode.deleter.compareAndSet(DEFAULT_DELETER, p)) {
                    nextNode.deleter.flush()
                    response[p].value = currentValue!!
                    response[p].flush()
                    head.compareAndSet(first, nextNode)
                    return currentValue
                } else {
                    val d = nextNode.deleter.value
                    if (head.value === first) {
                        nextNode.deleter.flush()
                        response[d].value = currentValue!!
                        response[d].flush()
                        head.compareAndSet(first, nextNode)
                    }
                }
            }
        }
    }

    fun recover() {
        val h = head.value
        val t = tail.value

        val predA = predA()
        if (predA != null) {
            if (reachableFrom(predA, h)) {
                head.compareAndSet(h, predA)
                val a = predA.next.value!!
                a.deleter.flush()
                head.compareAndSet(predA, a)
            }
        }

        val predB = predB()
        if (predB != null) {
            if (reachableFrom(predB, h)) {
                tail.compareAndSet(t, predB)
                tail.value.next.flush()
                tail.compareAndSet(predB, predB.next.value!!)
            } else {
                tail.compareAndSet(t, predB.next.value!!)
            }
        }
    }

    private fun predA(): Node<T?>? {
        var prev = dummy
        var n = prev.next.value ?: return null
        if (n.deleter.value == DEFAULT_DELETER) return null
        while (true) {
            val next = n.next.value ?: return prev
            if (next.deleter.value == DEFAULT_DELETER) return prev
            prev = n
            n = next
        }
    }

    private fun predB(): Node<T?>? {
        var prev = dummy
        var n = prev.next.value ?: return null
        while (true) {
            val next = n.next.value ?: return prev
            prev = n
            n = next
        }
    }

    private fun reachableFrom(node: Node<T?>, h: Node<T?>): Boolean {
        var c = h
        while (true) {
            if (c === node) return true
            c = c.next.value ?: return false
        }
    }
}
