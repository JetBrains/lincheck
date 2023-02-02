/**
 * Based on paper: https://nkoval.com/publications/europar19-channels.pdf
 */

package ScalableFIFOChannel

import kotlinx.atomicfu.atomic

class ScalableFIFOChannelFixed {
    private val head = atomic(Node(0))
    private val tail = atomic(head.value)

    fun addSequential(n: Node) {
        tail.value.next.value = n
        n.prev.value = tail.value
        tail.value = n
    }

    fun remove(n: Node) {
        n.cleaned = true
        val next = n.next.value
        val prev = n.prev.value
        if (next == null)
            return
        if (prev == null)
            return
        n.removed = true
        prev.moveNextToRight(next)
        next.movePrevToLeft(prev)
        if (prev.cleaned)
            remove(prev)
        if (next.cleaned)
            remove(next)
    }

    fun dequeue() {
        while (true) {
            var curHead = head.value
            val curTail = tail.value
            if (curHead == head.value) {
                if (curHead == curTail)
                    return
                var next = curHead.next.value!!
                next.prev.value = null
                while (head.compareAndSet(curHead, next)) {
                    curHead = head.value
                    if (!head.value.cleaned || curHead == curTail)
                        return
                    next = curHead.next.value!!
                    next.prev.value = null
                }
            }
        }
    }

    fun hasRemovedNodes(): Boolean {
        var curNode = head.value
        while (curNode != tail.value) {
            if (curNode.removed) return true
            if (curNode != head.value && curNode.prev.value!!.next.value != curNode) return true
            curNode = curNode.next.value!!
        }
        if (curNode.removed) return true
        return false
    }
}