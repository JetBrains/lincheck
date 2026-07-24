package org.jetbrains.lincheck_test.project

import kotlinx.atomicfu.atomic
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.Validate
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test

class ValidationFunctionFailureTest {

    private val queue = CustomConcurrentQueue<Int>()

    @Operation
    fun enqueue(@Param(gen = IntGen::class, conf = "1:3") value: Int) = queue.enqueue(value)

    @Operation
    fun dequeue(): Int? = queue.dequeue()

    @Operation
    fun remove(@Param(gen = IntGen::class, conf = "1:3") element: Int): Boolean = queue.remove(element)

    @Validate
    fun validate() = queue.validate()

    @Test
    fun test() = ModelCheckingOptions()
        .sequentialSpecification(IntQueueSequential::class.java)
        .check(this::class.java)

}

class IntQueueSequential {
    private val q = ArrayList<Int>()

    fun enqueue(element: Int) {
        q.add(element)
    }

    fun dequeue() = q.removeFirstOrNull()
    fun remove(element: Int) = q.remove(element)
}


class CustomConcurrentQueue<E> {
    private val head: AtomicReference<Node>
    private val tail: AtomicReference<Node>
    private val broken = atomic(0)

    init {
        val dummy = Node(null)
        head = AtomicReference(dummy)
        tail = AtomicReference(dummy)
    }

    fun enqueue(element: E) {
        broken.incrementAndGet()
        while (true) {
            val curTail = tail.get()
            val node = Node(element)
            if (curTail.next.compareAndSet(null, node)) {
                tail.compareAndSet(curTail, node)
                if (curTail.extractedOrRemoved) curTail.physicalRemove()
                return
            } else {
                tail.compareAndSet(curTail, curTail.next.get())
            }
        }
    }

    fun dequeue(): E? {
        while (true) {
            val curHead = head.get()
            val curHeadNext = curHead.next.get()
            if (curHeadNext == null) return null
            if (head.compareAndSet(curHead, curHeadNext)) {
                if (curHeadNext.markExtractedOrRemoved()) {
                    val element = curHeadNext.element
                    curHeadNext.element = null
                    return element
                }
            }
        }
    }

    fun remove(element: E): Boolean {
        // Traverse the linked list, searching the specified
        // element. Try to remove the corresponding node if found.
        // DO NOT CHANGE THIS CODE.
        var node = head.get()
        while (true) {
            val next = node.next.get()
            if (next == null) return false
            node = next
            if (node.element == element && node.remove()) return true
        }
    }

    /**
     * This is an internal function for tests.
     * DO NOT CHANGE THIS CODE.
     */
    fun validate() {
        check(broken.value <= 0) { "Validation failed" }
        check(tail.get().next.get() == null) {
            "tail.next must be null"
        }
        var node = head.get()
        // Traverse the linked list
        while (true) {
            if (node !== head.get() && node !== tail.get()) {
                check(!node.extractedOrRemoved) {
                    "Removed node with element ${node.element} found in the middle of this queue"
                }
            }
            node = node.next.get() ?: break
        }
    }

    // TODO: Node is an inner class for accessing `head` in `remove()`
    private inner class Node(
        var element: E?
    ) {
        val next = AtomicReference<Node?>(null)

        private val _extractedOrRemoved = AtomicBoolean(false)
        val extractedOrRemoved
            get() =
                _extractedOrRemoved.get()

        fun markExtractedOrRemoved(): Boolean =
            _extractedOrRemoved.compareAndSet(false, true)

        /**
         * Removes this node from the queue structure.
         * Returns `true` if this node was successfully
         * removed, or `false` if it has already been
         * removed by [remove] or extracted by [dequeue].
         */
        fun remove(): Boolean =
            if (markExtractedOrRemoved()) {
                physicalRemove()
                true
            } else {
                false
            }

        fun physicalRemove() {
            val curNext = next.get() ?: return
            val curPrev = findPrev() ?: return
            curPrev.next.set(curNext)
            if (curNext.extractedOrRemoved) curNext.physicalRemove()
        }

//        TODO: correct version of this function
//        fun findPrev(): Node? {
//            var cur = head.get()
//            while (cur.next.get() !== this) {
//                cur = cur.next.get() ?: return null
//            }
//            return cur
//        }

        // version with bug
        fun findPrev(): Node? {
            var cur = head.get()
            while (cur.next.get()?.next?.get() !== this) {
                cur = cur.next.get() ?: return null
            }
            return cur
        }
    }
}