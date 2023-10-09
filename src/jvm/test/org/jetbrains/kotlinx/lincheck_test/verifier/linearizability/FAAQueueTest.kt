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

import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray

open class FAAQueue<T> {
    internal val head: AtomicReference<Segment> // Head pointer, similarly to the Michael-Scott queue (but the first node is _not_ sentinel)
    internal val tail: AtomicReference<Segment> // Tail pointer, similarly to the Michael-Scott queue

    init {
        val firstNode = Segment()
        head = AtomicReference(firstNode)
        tail = AtomicReference(firstNode)
    }


    /**
     * Adds the specified element [x] to the queue.
     */
    open fun enqueue(x: T) {
        while (true) {
            var tail = tail.get()
            val tNext = tail.next.get()
            if (tNext != null) {
                this.tail.compareAndSet(tail, tNext)
                continue
            }
            val enqueueIndex = tail.enqIdx.getAndIncrement()
            if (enqueueIndex >= SEGMENT_SIZE) {
                val nextTail = Segment(x)
                tail = this.tail.get()
                val nextTailLink = tail.next.get()
                if (nextTailLink == null) {
                    if (this.tail.get().next.compareAndSet(null, nextTail)) {
                        return
                    }
                } else {
                    this.tail.compareAndSet(tail, nextTailLink)
                }
            } else {
                if (tail.elements.compareAndSet(enqueueIndex, null, x)) {
                    return
                }
            }
        }
    }

    /**
     * Retrieves the first element from the queue
     * and returns it; returns `null` if the queue
     * is empty.
     */
    fun dequeue(): T? {
        while (true) {
            val head = head.get()
            if (head.deqIdx.value >= SEGMENT_SIZE) {
                val next = head.next.get()
                if (next != null) {
                    this.head.compareAndSet(head, next);
                } else {
                    return null
                }
            } else {
                val dequeIndex = head.deqIdx.getAndIncrement();
                if (dequeIndex >= SEGMENT_SIZE) {
                    continue
                }
                return head.elements.getAndSet(dequeIndex, DONE) as T? ?: continue;
            }
        }
    }

    /**
     * Returns `true` if this queue is empty;
     * `false` otherwise.
     */
    val isEmpty: Boolean
        get() {
            while (true) {
                val head = head.get()
                if (head.deqIdx.value >= SEGMENT_SIZE) {
                    if (head.next.get() == null) {
                        return true
                    } else {
                        this.head.compareAndSet(head, head.next.get())
                    }
                } else {
                    return false
                }
            }
        }
}

internal class Segment {
    val next: AtomicReference<Segment?> = AtomicReference(null)
    val enqIdx = atomic(0)// index for the next enqueue operation
    val deqIdx = atomic(0) // index for the next dequeue operation
    val elements: AtomicReferenceArray<Any?> = AtomicReferenceArray(SEGMENT_SIZE)

    constructor() // for the first segment creation

    constructor(x: Any?) { // each next new segment should be constructed with an element
        enqIdx.value = 1
        elements.set(0, x)
    }
}


private val DONE = Any() // Marker for the "DONE" slot state; to avoid memory leaks
const val SEGMENT_SIZE = 2 // DO NOT CHANGE, IMPORTANT FOR TESTS

internal class FAAQueueTest : AbstractLincheckTest() {
    private val faaQueue = FAAQueue<Int>()

    @Operation
    fun dequeue() = faaQueue.dequeue()

    @Operation
    fun enqueue(x: Int) = faaQueue.enqueue(x)
}

internal class FAAQueueFailingTest : AbstractLincheckTest(IncorrectResultsFailure::class) {
    private val faaQueue = object : FAAQueue<Int>() {
        override fun enqueue(x: Int) {
            while (true) {
                var tail = tail.get()
                val tNext = tail.next.get()
                // ! BUG
//                if (tNext != null) {
//                    this.tail.compareAndSet(tail, tNext)
//                    continue
//                }
                val enqueueIndex = tail.enqIdx.getAndIncrement()
                if (enqueueIndex >= SEGMENT_SIZE) {
                    val nextTail = Segment(x)
                    tail = this.tail.get()
                    val nextTailLink = tail.next.get()
                    if (nextTailLink == null) {
                        if (this.tail.get().next.compareAndSet(null, nextTail)) {
                            return
                        }
                    } else {
                        this.tail.compareAndSet(tail, nextTailLink)
                    }
                } else {
                    if (tail.elements.compareAndSet(enqueueIndex, null, x)) {
                        return
                    }
                }
            }
        }
    }

    @Operation
    fun dequeue() = faaQueue.dequeue()

    @Operation
    fun enqueue(x: Int) = faaQueue.enqueue(x)

    override fun <O : Options<O, *>> O.customize() {
        iterations(100)
    }
}
