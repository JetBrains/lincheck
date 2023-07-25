/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

@file:Suppress("unused")

package org.jetbrains.kotlinx.lincheck_test.representation

import kotlinx.atomicfu.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Test

/**
 * This test is created to verify this [bug](https://github.com/JetBrains/lincheck/issues/209) is resolved.
 * The problem was that we ran in infinite spin-lock in trace collection mode due to incorrect Loop detector work.
 */
class BoundedQueueTest {
    private val queue = QueueAdaptor<Int>()

    @Operation
    fun enqueue(element: Int) = queue.enqueue(element)

    @Operation
    fun dequeue() = queue.dequeue() != null

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .iterations(1_000)
        .invocationsPerIteration(2_000)
        .minimizeFailedScenario(true)
        .checkImpl(this::class.java)
        .checkLincheckOutput("bounded_queue_incorrect_results.txt")
}

class QueueAdaptor<T> {
    private class Marked<T>(val element: T) {
        val marked = atomic(false)
    }

    private val queue = BoundedQueue<Marked<T>>()
    private val concurrentEnqueues = atomic(0)

    fun enqueue(element: T) {
        try {
            concurrentEnqueues.getAndIncrement()
            queue.enqueue(Marked(element))
        } finally {
            concurrentEnqueues.getAndDecrement()
        }
    }

    fun dequeue(): T? {
        while (true) {
            val element = queue.dequeue()
            if (element != null) {
                val wasMarked = element.marked.getAndSet(true)
                require(!wasMarked)
                return element.element
            }
            if (concurrentEnqueues.value == 0)
                return null
        }
    }
}


private const val CAPACITY = 10

/**
 * Broken BoundedQueue implementation to check that bug can be found in the model checking mode.
 * Based on https://www.1024cores.net/home/lock-free-algorithms/queues/bounded-mpmc-queue
 */
class BoundedQueue<E> {
    private val buffer = Array(CAPACITY) {
        Cell(it.toLong(), null)
    }
    private val enqueuePos = atomic(0L)
    private val dequeuePos = atomic(0L)

    fun enqueue(element: E) {
        var pos = enqueuePos.value
        while (true) {
            val cell = buffer[(pos % buffer.size).toInt()]
            val seq = cell.sequence.value
            val dif = seq - pos
            if (dif == 0L) {
                if (enqueuePos.compareAndSet(pos, pos + 1)) {
                    cell.data.value = element
                    cell.sequence.value = pos + 1
                    return
                }
            } else {
                pos = enqueuePos.value
            }
        }
    }

    fun dequeue(): E? {
        var pos = dequeuePos.value
        while (true) {
            val cell = buffer[(pos % buffer.size).toInt()]
            val seq = cell.sequence.value
            val dif = seq - (pos + 1)
            if (dif == 0L) {
                if (dequeuePos.compareAndSet(pos, pos + 1)) {
                    val result = cell.data.value!!
                    cell.sequence.value = pos + buffer.size.toLong()
                    return result
                }
            } else if (dif < 0) {
                return null
            } else {
                pos = dequeuePos.value
            }
        }
    }

    private inner class Cell(sequence: Long, data: E?) {
        val sequence = atomic(sequence)
        val data = atomic(data)
    }
}