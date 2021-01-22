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
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.paramgen.ThreadIdGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.test.verifier.linearizability.SequentialQueue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val THREADS_NUMBER = 3

/**
 * @see  <a href="http://www.cs.technion.ac.il/~erez/Papers/nvm-queue-full.pdf">A Persistent Lock-Free Queue for Non-Volatile Memory</a>
 */
@StressCTest(
    sequentialSpecification = SequentialQueue::class,
    threads = THREADS_NUMBER,
    recover = Recover.DURABLE
)
class DurableMSQueueTest {
    private val q = DurableMSQueue<Int>(2 + THREADS_NUMBER)

    @Operation
    fun push(value: Int) = q.push(value)

    @Operation
    fun pop(@Param(gen = ThreadIdGen::class) threadId: Int) = q.pop(threadId)

    @Test
    fun test() = LinChecker.check(this::class.java)
}


private fun flush(value: Any) {

}

private const val DEFAULT_DELETER = -1

class DurableMSQueue<T>(threadsCount: Int) {
    private class Node<S>(
        val next: AtomicReference<Node<S>> = AtomicReference<Node<S>>(null),
        val value: S,
        val deleter: AtomicInteger = AtomicInteger(DEFAULT_DELETER)
    )

    private val head: AtomicReference<Node<T?>>
    private val tail: AtomicReference<Node<T?>>
    private val response: MutableList<Any>

    private val empty = Any()
    private val unknown = Any()

    init {
        val dummy = Node<T?>(value = null)
        flush(dummy)

        head = AtomicReference(dummy)
        flush(head)


        tail = AtomicReference(dummy)
        flush(tail)

        response = MutableList(threadsCount) { unknown }
        flush(response)
    }

    fun push(value: T) {
        val newNode = Node<T?>(value = value)
        flush(newNode)
        while (true) {
            val tailNode = tail.get()
            if (tailNode.next.compareAndSet(null, newNode)) {
                flush(tailNode.next)
                tail.compareAndSet(tailNode, newNode)
                return
            } else {
                flush(tailNode.next)
                tail.compareAndSet(tailNode, tailNode.next.get())
            }
        }
    }

    fun pop(p: Int): T? {
        response[p] = unknown
        flush(response[p])

        while (true) {
            val h = head.get()
            val next = h.next.get()
            if (next == null) {
                response[p] = empty
                flush(response[p])
                return null
            }
            flush(next)
            tail.compareAndSet(h, next)
            if (next.deleter.compareAndSet(DEFAULT_DELETER, p)) {
                flush(next.deleter.get())
                response[p] = next.value!!
                flush(response[p])
                head.compareAndSet(h, next)
                return next.value
            } else {
                val deleter = next.deleter.get()
                if (head.get() == h) {
                    flush(h.next.get().deleter)
                    response[deleter] = next.value!! // previous operation has completed before this operation
                    flush(response[deleter])
                    head.compareAndSet(h, next)
                }
            }
        }
    }
}
