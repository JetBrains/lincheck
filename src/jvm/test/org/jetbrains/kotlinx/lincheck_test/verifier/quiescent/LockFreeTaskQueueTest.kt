/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.verifier.quiescent

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck_test.*
import org.jetbrains.kotlinx.lincheck.verifier.quiescent.*
import org.jetbrains.kotlinx.lincheck_test.datastructures.LockFreeTaskQueue
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param


@Param(name = "value", gen = IntGen::class, conf = "1:3")
class LockFreeTaskQueueTest : AbstractLincheckTest() {
    private val q = LockFreeTaskQueue<Int>(true)

    @Operation
    fun addLast(@Param(name = "value") value: Int) = q.addLast(value)

    @QuiescentConsistent
    @Operation(nonParallelGroup = "consumer")
    fun removeFirstOrNull() = q.removeFirstOrNull()

    @Operation
    fun close() = q.close()

    override fun <O : Options<O, *>> O.customize() {
        actorsBefore(2)
        actorsAfter(2)
        threads(2)
        actorsPerThread(3)
        verifier(QuiescentConsistencyVerifier::class.java)
    }
}