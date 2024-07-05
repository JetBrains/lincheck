package org.jetbrains.ecoop24

class SPSCMSQueue<E> {
    @Volatile var head = QNode(null)
    @Volatile var tail = head

    fun enqueue(element: E) {
        val newTail = QNode(element)
        val curTail = tail
        tail = newTail
        curTail.next = newTail
    }

    fun dequeue() : E? {
        if (tail == head) return null // <-- incorrect emptiness check
        head = head.next!!
        return head.element as E
    }
}
class QNode(val element: Any?) {
    @Volatile var next: QNode? = null
}