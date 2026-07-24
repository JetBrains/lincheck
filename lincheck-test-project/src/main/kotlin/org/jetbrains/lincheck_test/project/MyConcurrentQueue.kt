package org.jetbrains.lincheck_test.project

import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic

class MyConcurrentQueue<E> {
    private val head: AtomicRef<Node<E>>
    private val tail: AtomicRef<Node<E>>

    init {
        val dummyNode = Node<E>(null)
        head = atomic(dummyNode)
        tail = atomic(dummyNode)
    }

    fun enqueue(element: E) {
        val newTail = Node(element)
        while (true) {
            val curTail = tail.value
            if (curTail.next.value == null && curTail.next.compareAndSet(null, newTail)) {
                tail.compareAndSet(curTail, newTail)
                return
            } else {
                tail.compareAndSet(curTail, curTail.next.value!!)
            }
        }
    }

    fun dequeue(): E? {
        while (true) {
            val curTail = tail.value
            val curHead = head.value
            if (curHead == curTail) return null
            val curHeadNext = curHead.next.value!!
            if (head.compareAndSet(curHead, curHeadNext)) {
                return curHeadNext.element.also {
                    curHeadNext.element = null
                }
            }
        }
    }
}

private class Node<E>(
    var element: E?
) {
    val next = atomic<Node<E>?>(null)
}