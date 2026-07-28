package org.lincheck.docs

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import kotlin.test.Test
import java.util.concurrent.atomic.AtomicReference

class TreiberStack<E> {
    private val top = AtomicReference<Node<E>?>(null)

    fun push(item: E) {
        val newHead = Node(item)
        var oldHead: Node<E>?

        do {
            oldHead = top.get()
            newHead.next = oldHead
        } while (!top.compareAndSet(oldHead, newHead))
    }

    fun pop(): E? {
        val oldHead = top.get()

        if (oldHead == null) {
            return null
        }

        val newHead = oldHead.next
        top.compareAndSet(oldHead, newHead)

        // Bug: by the time `pop()` finishes execution,
        // another thread might have already popped this item.
        return oldHead.item
    }

    private class Node<E>(
        val item: E,
        var next: Node<E>? = null
    )
}


class TreiberStackCorrect<E> {
    private val top = AtomicReference<Node<E>?>(null)

    fun push(item: E) {
        val newHead = Node(item)
        var oldHead: Node<E>?

        do {
            oldHead = top.get()
            newHead.next = oldHead
        } while (!top.compareAndSet(oldHead, newHead))
    }

    fun pop(): E? {
        var oldHead: Node<E>?
        var newHead: Node<E>?

        do {
            oldHead = top.get()
            if (oldHead == null) return null
            newHead = oldHead.next
        } while (!top.compareAndSet(oldHead, newHead))

        return oldHead.item
     }

    private class Node<E>(
        val item: E,
        var next: Node<E>? = null
    )
}


class TreiberStackTest {
    private val stack = TreiberStack<Int>()

    @Operation
    fun push(value: Int) = stack.push(value)

    @Operation
    fun pop(): Int? = stack.pop()

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .check(this::class)
}