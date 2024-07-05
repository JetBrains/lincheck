package org.jetbrains.ecoop24

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.jupiter.api.Test

class FaaQueueTest {
    private val faaQueue = FAAQueue<Int>()

    @Operation
    fun dequeue() = faaQueue.dequeue()

    @Operation
    fun enqueue(x: Int) = faaQueue.enqueue(x)

    @Test
    fun modelCheckingTest() = ModelCheckingOptions()
        .addCustomScenario {
            initial { actor(FaaQueueTest::dequeue) }
            parallel {
                thread {
                    actor(FaaQueueTest::enqueue, 1)
                    actor(FaaQueueTest::enqueue, 1)
                    actor(FaaQueueTest::dequeue)
                    actor(FaaQueueTest::enqueue, 0)
                }
                thread {
                    actor(FaaQueueTest::enqueue, 1)
                }
            }
            post { actor(FaaQueueTest::dequeue) }
        }
        .check(this::class)

}