/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.gpt.structure

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions

@Param(name = "element", gen = IntGen::class, conf = "0:3")
class GptTest {
    private val queue = FAABasedQueue<Int>()
    @Operation
    fun enqueue(@Param(name = "element") element: Int) = queue.enqueue(element)

    @Operation
    fun dequeue() = queue.dequeue()

}

fun modelCheckingFailure(): LincheckFailure? {
    return ModelCheckingOptions()
        .iterations(100)
        .invocationsPerIteration(5_000)
        .actorsBefore(2)
        .threads(3)
        .actorsPerThread(2)
        .actorsAfter(2)
        .checkObstructionFreedom(true)
        .sequentialSpecification(IntQueueSequential::class.java)
        .checkImpl(GptTest::class.java)
}

class IntQueueSequential {
    private val q = ArrayList<Int>()

    fun enqueue(element: Int) {
        q.add(element)
    }

    fun dequeue() = q.removeFirstOrNull()
    fun remove(element: Int) = q.remove(element)
}

fun main() {
    println(modelCheckingFailure())
}