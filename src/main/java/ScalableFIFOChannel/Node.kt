package ScalableFIFOChannel

import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic

class Node(val id: Int) {
    val prev: AtomicRef<Node?> = atomic(null)
    val next: AtomicRef<Node?> = atomic(null)
    var cleaned = false
    var removed = false

    fun moveNextToRight(newNext: Node) {
        val curNext: Node = next.value ?: return
        if (curNext.id < newNext.id) {
            next.compareAndSet(curNext, newNext)
        }
    }

    fun movePrevToLeft(newPrev: Node) {
        val curPrev: Node = prev.value ?: return
        if (curPrev.id > newPrev.id) {
            prev.compareAndSet(curPrev, newPrev)
        }
    }
}