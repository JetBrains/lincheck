package org.jetbrains.ecoop24

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedDeque

class ConcurrentDequeTest {
    private val deque = ConcurrentLinkedDeque<Int>()

    @Operation
    fun addFirst(e: Int) = deque.addFirst(e)

    @Operation
    fun addLast(e: Int) = deque.addLast(e)

    @Operation
    fun pollFirst(): Int = deque.pollFirst()

    @Operation
    fun peekLast(): Int = deque.peekLast()

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