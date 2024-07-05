package org.jetbrains.ecoop24

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.jupiter.api.Test

class SPSCMSQueueTest {
    val q = SPSCMSQueue<Int>()

    @Operation(nonParallelGroup = "producer")
    fun enqueue(element: Int) = q.enqueue(element)

    @Operation(nonParallelGroup = "consumer")
    fun dequeue() = q.dequeue()

    @Test
    fun test() = ModelCheckingOptions().check(this::class)
}