@file:Suppress("unused")

package template

import org.jetbrains.lincheck.datastructures.*
import java.util.concurrent.*
import kotlin.test.*

class ConcurrentLinkedDequeTest {
    private val deque = ConcurrentLinkedDeque<Int>()

    @Operation
    fun addFirst(element: Int) = deque.addFirst(element)

    @Operation
    fun addLast(element: Int) = deque.addLast(element)

    @Operation
    fun peekFirst() = deque.peekFirst()

    @Operation
    fun peekLast() = deque.peekLast()

    @Operation
    fun pollFirst() = deque.pollFirst()

    @Operation
    fun pollLast() = deque.pollLast()

    @Test
    fun test() = ModelCheckingOptions().check(this::class)
}
