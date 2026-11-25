package org.lincheck.docs

import org.jetbrains.lincheck.Lincheck
import org.jetbrains.lincheck.LincheckSettings
import org.jetbrains.lincheck.datastructures.Operation
import kotlin.test.Test
import java.util.concurrent.atomic.AtomicReference

class SpscLinkedQueue<T> {
    private class Node<T>(val value: T? = null) {
        val next = AtomicReference<Node<T>?>(null)
    }

    private val sentinel = Node<T>()
    private var producerNode = sentinel
    private val consumerNode = AtomicReference(sentinel)

    fun offer(value: T): Boolean {
        val newNode = Node(value)
        producerNode.next.lazySet(newNode)
        producerNode = newNode
        return true
    }

    fun poll(): T? {
        val head = consumerNode.get()
        val nextNode = head.next.get() ?: return null
        consumerNode.lazySet(nextNode)
        return nextNode.value
    }

    fun peek(): T? {
        val head = consumerNode.get()
        return head.next.get()?.value
    }

    fun isEmpty(): Boolean {
        val head = consumerNode.get()
        return head.next.get() == null
    }
}

class NonParallelGroupTest {
    private val queue = SpscLinkedQueue<Int>()

    @Operation(nonParallelGroup = "consumers")
    fun poll(): Int? = queue.poll()

    @Operation(nonParallelGroup = "consumers")
    fun peek(): Int? = queue.peek()

    @Operation(nonParallelGroup = "producer")
    fun offer(x: Int) = queue.offer(x)

    @Operation
    fun isEmpty(): Boolean = queue.isEmpty()

    @Test
    fun modelCheckingTest() = Lincheck.runConcurrentTest(LincheckSettings { modelChecking() }, this::class)
}