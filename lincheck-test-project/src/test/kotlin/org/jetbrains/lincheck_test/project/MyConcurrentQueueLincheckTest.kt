package org.jetbrains.lincheck_test.project

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import kotlin.test.Test

class MyConcurrentQueueLincheckTest {
    private val q = MyConcurrentQueue<Int>()

    @Operation
    fun enqueue(element: Int) = q.enqueue(element)

    @Operation
    fun dequeue() = q.dequeue()

    @Test
    fun test() = ModelCheckingOptions().check(this::class)
}

class MyConcurrentQueueLincheckWithExceptionInInitAndParallelPartTest {
    private val q = MyConcurrentQueue<Int>()

    @Operation
    fun enqueue(element: Int) = q.enqueue(element)

    @Operation
    fun dequeue() = q.dequeue()

    @Operation
    fun failure() {
        q.enqueue(1)
        q.dequeue()
        error("Expected error")
    }

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            initial {
                actor(MyConcurrentQueueLincheckWithExceptionInInitAndParallelPartTest::failure)
            }
            parallel {
                thread { actor(MyConcurrentQueueLincheckWithExceptionInInitAndParallelPartTest::dequeue) }
                thread {
                    actor(MyConcurrentQueueLincheckWithExceptionInInitAndParallelPartTest::enqueue, -1)
                    actor(MyConcurrentQueueLincheckWithExceptionInInitAndParallelPartTest::dequeue)
                }
            }
        }
        .check(this::class)
}