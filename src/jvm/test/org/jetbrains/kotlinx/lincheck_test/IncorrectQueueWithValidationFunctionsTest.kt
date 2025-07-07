@file:Suppress("unused")
/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */


package org.jetbrains.kotlinx.lincheck_test

import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Validate
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This test is created to verify this [bug](https://github.com/JetBrains/lincheck/issues/206) is resolved.
 * The problem was that in model checking mode we ran into instrumented validation function and collected trace inside it,
 * what is undesirable and leads to exceptions during trace representation construction.
 */
class IncorrectQueueWithValidationFunctionsTest {

    private val queue = MSQueueWithLinearTimeRemove<Int>()

    @Operation
    fun remove(element: Int) = queue.remove(element)

    @Validate
    fun checkNoRemovedElements() = queue.checkNoRemovedElements()

    @Operation
    fun enqueue(element: Int) = queue.enqueue(element)

    @Operation
    fun dequeue() = queue.dequeue()

    @Test
    fun modelCheckingTest() {
        val failure = ModelCheckingOptions()
            .addCustomScenario {
                parallel {
                    thread { }
                    thread {
                        actor(IncorrectQueueWithValidationFunctionsTest::enqueue, 0)
                    }
                }
                post {
                    actor(IncorrectQueueWithValidationFunctionsTest::remove, 0)
                    actor(IncorrectQueueWithValidationFunctionsTest::enqueue, 2)
                }
            }
            .iterations(50)
            .invocationsPerIteration(1_000)
            .checkObstructionFreedom(true)
            .sequentialSpecification(IntQueueSequential::class.java)
            .checkImpl(this::class.java)

        assertTrue(failure is IncorrectResultsFailure)
    }
}

class IntQueueSequential {
    private val q = ArrayList<Int>()

    fun enqueue(element: Int) {
        q.add(element)
    }

    fun dequeue() = q.removeFirstOrNull()
    fun remove(element: Int) = q.remove(element)
}


class MSQueueWithLinearTimeRemove<E> {
    private val head: AtomicRef<Node>
    private val tail: AtomicRef<Node>

    init {
        val dummy = Node(null)
        head = atomic(dummy)
        tail = atomic(dummy)
    }

    fun enqueue(element: E) {
        while (true) {
            val node = Node(element)
            val curTail = tail.value
            if (curTail.extractedOrRemoved) {
                if (curTail.referrer().next.compareAndSet(curTail, node)) {
                    tail.compareAndSet(curTail, node)
                    return
                }
            } else if (curTail.next.compareAndSet(null, node)) {
                tail.compareAndSet(curTail, node)
                return
            } else {
                tail.compareAndSet(curTail, curTail.next.value!!)
            }
        }
    }

    private fun Node.referrer(): Node {
        var x: Node? = head.value
        while (x != null && x.next.value != this) {
            x = x.next.value
        }
        return x!!
    }

    fun dequeue(): E? {
        while (true) {
            val curHead = head.value
            val curHeadNext = curHead.next.value ?: return null
            if (head.compareAndSet(curHead, curHeadNext) && curHeadNext.markExtractedOrRemoved())
                return curHeadNext.element
        }
    }

    fun remove(element: E): Boolean {
        // Traverse the linked list, searching the specified
        // element. Try to remove the corresponding node if found.
        // DO NOT CHANGE THIS CODE.
        var node = head.value
        while (true) {
            val next = node.next.value
            if (next == null) return false
            node = next
            if (node.element == element && node.remove()) return true
        }
    }

    /**
     * This is an internal function for tests.
     * DO NOT CHANGE THIS CODE.
     */
    fun checkNoRemovedElements() {
        check(tail.value.next.value == null) {
            "tail.next must be null"
        }
        var node = head.value
        // Traverse the linked list
        while (true) {
            if (node !== head.value && node !== tail.value) {
                check(!node.extractedOrRemoved) {
                    "Removed node with element ${node.element} found in the middle of this queue"
                }
            }
            node = node.next.value ?: break
        }
    }

    private inner class Node(
        var element: E?
    ) {
        val next = atomic<Node?>(null)

        private val _extractedOrRemoved = atomic(false)
        val extractedOrRemoved get() = _extractedOrRemoved.value

        fun markExtractedOrRemoved(): Boolean = _extractedOrRemoved.compareAndSet(false, true)

        /**
         * Removes this node from the queue structure.
         * Returns `true` if this node was successfully
         * removed, or `false` if it has already been
         * removed by [remove] or extracted by [dequeue].
         */
        fun remove(): Boolean {
            val result = markExtractedOrRemoved()
            val prevNext = referrer().next.value
            val myNext = next.value
            referrer().next.compareAndSet(prevNext, myNext)
            return result
        }
    }
}
