/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.transformation.atomics

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest
import java.util.concurrent.atomic.AtomicIntegerArray

@Param(name = "idx", gen = IntGen::class, conf = "0:4")
class AtomicIntegerArrayTest : AbstractLincheckTest() {
    val value = AtomicIntegerArray(5)

    @Operation
    fun get(@Param(name = "idx") idx: Int) =
        value.get(idx)

    @Operation
    fun set(@Param(name = "idx") idx: Int, newValue: Int) =
        value.set(idx, newValue)

    @Operation
    fun getAndSet(@Param(name = "idx") idx: Int, newValue: Int) =
        value.getAndSet(idx, newValue)

    @Operation
    fun compareAndSet(@Param(name = "idx") idx: Int, expectedValue: Int, newValue: Int) =
        value.compareAndSet(idx, expectedValue, newValue)

    @Operation
    fun addAndGet(@Param(name = "idx") idx: Int, delta: Int) =
        value.addAndGet(idx, delta)

    @Operation
    fun getAndAdd(@Param(name = "idx") idx: Int, delta: Int) =
        value.getAndAdd(idx, delta)

    @Operation
    fun incrementAndGet(@Param(name = "idx") idx: Int) =
        value.incrementAndGet(idx)

    @Operation
    fun getAndIncrement(@Param(name = "idx") idx: Int) =
        value.getAndIncrement(idx)

    @Operation
    fun decrementAndGet(@Param(name = "idx") idx: Int) =
        value.decrementAndGet(idx)

    @Operation
    fun getAndDecrement(@Param(name = "idx") idx: Int) =
        value.getAndDecrement(idx)
}