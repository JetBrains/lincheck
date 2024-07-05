package org.jetbrains.ecoop24

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray


class FAAQueue<T> {
    private val head: AtomicReference<Segment> // Head pointer, similarly to the Michael-Scott queue (but the first node is _not_ sentinel)
    private val tail: AtomicReference<Segment> // Tail pointer, similarly to the Michael-Scott queue

    init {
        val firstNode = Segment()
        head = AtomicReference(firstNode)
        tail = AtomicReference(firstNode)
    }


    /**
     * Adds the specified element [x] to the queue.
     */
    fun enqueue(x: T) {
        while (true) {
            var tail = tail.get()
            val tNext = tail.next.get()
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
            if (head.deqIdx.get() >= SEGMENT_SIZE) {
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
                if (head.deqIdx.get() >= SEGMENT_SIZE) {
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

private class Segment {
    var next: AtomicReference<Segment?> = AtomicReference(null)
    var enqIdx = AtomicInteger(0)// index for the next enqueue operation
    var deqIdx = AtomicInteger(0) // index for the next dequeue operation
    val elements: AtomicReferenceArray<Any?> = AtomicReferenceArray(SEGMENT_SIZE)

    constructor() // for the first segment creation

    constructor(x: Any?) { // each next new segment should be constructed with an element
        enqIdx.set(1)
        elements.set(0, x)
    }
}


private val DONE = Any() // Marker for the "DONE" slot state; to avoid memory leaks
const val SEGMENT_SIZE = 2