/**
 * Based on paper: https://nkoval.com/publications/europar19-channels.pdf
 */

package ScalableFIFOChannel

import kotlinx.atomicfu.atomic

class ScalableFIFOChannel {
    private val head = atomic(Node(0))
    private val tail = atomic(head.value)

    fun addSequential(n: Node) {
        tail.value.next.value = n
        n.prev.value = tail.value
        tail.value = n
    }

    fun remove(n: Node) {
        val next = n.next.value
        val prev = n.prev.value
        if (next == null)
            return
        if (prev == null)
            return
        n.cleaned = true
        prev.moveNextToRight(next)
        next.movePrevToLeft(prev)
        if (prev.cleaned)
            remove(prev)
        if (next.cleaned)
            remove(next)
    }

    fun dequeue() {
        while (true) {
            val curHead = head.value
            val curTail = tail.value
            if (curHead == head.value) {
                if (curHead == curTail) {
                    return
                }
                val next = curHead.next.value!!
                next.prev.value = null
                if (head.compareAndSet(curHead, next))
                    return
            }
        }
    }

    fun hasRemovedNodes(): Boolean {
        var curNode = head.value
        while (curNode != tail.value) {
            if (curNode.cleaned) return true
            curNode = curNode.next.value!!
        }
        if (curNode.cleaned) return true
        return false
    }
}