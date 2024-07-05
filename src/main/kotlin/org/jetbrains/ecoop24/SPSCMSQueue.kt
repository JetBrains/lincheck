package org.jetbrains.ecoop24

class SPSCQueue<E> {
    @Volatile var head = Node(null)
    @Volatile var tail = head

    fun enqueue(element: E) {
        val newTail = Node(element)
        val curTail = tail
        tail = newTail
        curTail.next = newTail
    }

    fun dequeue() : E? {
        if (tail == head) return null
        head = head.next!!  // <-- can throw NPE
        return (head.element as E)
    }

    class Node(val element: Any?) {
        @Volatile var next: Node? = null
    }
}