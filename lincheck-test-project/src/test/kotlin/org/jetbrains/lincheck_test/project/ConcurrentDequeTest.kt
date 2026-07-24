package org.jetbrains.lincheck_test.project

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.test.Test

class ConcurrentDequeTest {
    private val deque = ConcurrentLinkedDeque<Int>()

    @Operation
    fun addFirst(e: Int) = deque.addFirst(e)

    @Operation
    fun addLast(e: Int) = deque.addLast(e)

    @Operation
    fun pollFirst() = deque.pollFirst()

    @Operation
    fun pollLast() = deque.pollLast()

    @Operation
    fun peekFirst() = deque.peekFirst()

    @Operation
    fun peekLast() = deque.peekLast()

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread {
                    actor(ConcurrentDequeTest::addLast, 1)
                    actor(ConcurrentDequeTest::pollFirst)
                }
                thread {
                    actor(ConcurrentDequeTest::addFirst, 0)
                    actor(ConcurrentDequeTest::peekLast)
                }
            }
        }
        .check(this::class)
}