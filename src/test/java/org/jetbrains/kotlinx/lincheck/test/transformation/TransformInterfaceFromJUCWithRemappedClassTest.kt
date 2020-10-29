package org.jetbrains.kotlinx.lincheck.test.transformation

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.test.*
import java.util.concurrent.*

class TransformInterfaceFromJUCWithRemappedClassTest : AbstractLincheckTest() {
    private val q: BlockingQueue<Int> = ArrayBlockingQueue(10)

    init {
        q.add(10)
    }

    @Operation
    fun op() = q.poll(100, TimeUnit.DAYS)

    override fun <O : Options<O, *>> O.customize() {
        iterations(1)
        actorsBefore(0)
        threads(1)
        actorsPerThread(1)
        actorsAfter(0)
        requireStateEquivalenceImplCheck(false)
    }
}