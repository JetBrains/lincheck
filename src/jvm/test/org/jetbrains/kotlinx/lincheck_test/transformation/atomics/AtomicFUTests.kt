/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck_test.transformation.atomics

import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest
import org.jetbrains.kotlinx.lincheck_test.util.StringPoolGenerator
import kotlinx.atomicfu.*

class AtomicFUBooleanTest : AbstractLincheckTest() {
    private val bool = atomic(false)

    @Operation
    fun get() = bool.value

    @Operation
    fun set(newValue: Boolean) {
        bool.value = newValue
    }

    @Operation
    fun getAndSet(newValue: Boolean) =
        bool.getAndSet(newValue)

    @Operation
    fun compareAndSet(expectedValue: Boolean, newValue: Boolean) =
        bool.compareAndSet(expectedValue, newValue)

}

class AtomicFUIntegerTest : AbstractLincheckTest() {
    private val int = atomic(0)

    @Operation
    fun get() = int.value

    @Operation
    fun set(newValue: Int) {
        int.value = newValue
    }

    @Operation
    fun getAndSet(newValue: Int) =
        int.getAndSet(newValue)

    @Operation
    fun compareAndSet(expectedValue: Int, newValue: Int) =
        int.compareAndSet(expectedValue, newValue)

    @Operation
    fun getAndIncrement() = int.getAndIncrement()

    @Operation
    fun getAndDecrement() = int.getAndDecrement()

    @Operation
    fun getAndAdd(delta: Int) = int.getAndAdd(delta)

    @Operation
    fun addAndGet(delta: Int) = int.addAndGet(delta)

    @Operation
    fun incrementAndGet() = int.incrementAndGet()

    @Operation
    fun decrementAndGet() = int.decrementAndGet()

    /*
     * `plusAssign` and `minusAssign` operators are not supported
     * in atomicfu Kotlin-JVM IR compiler plugin:
     * https://github.com/Kotlin/kotlinx-atomicfu/issues/414
     */

    // @Operation
    // fun plusAssign(delta: Int) {
    //     int += delta
    // }
    //
    // @Operation
    // fun minusAssign(delta: Int) {
    //     int -= delta
    // }
}

class AtomicFULongTest : AbstractLincheckTest() {
    private val long = atomic(0L)

    @Operation
    fun get() = long.value

    @Operation
    fun set(newValue: Long) {
        long.value = newValue
    }

    @Operation
    fun getAndSet(newValue: Long) =
        long.getAndSet(newValue)

    @Operation
    fun compareAndSet(expectedValue: Long, newValue: Long) =
        long.compareAndSet(expectedValue, newValue)

    @Operation
    fun getAndIncrement() = long.getAndIncrement()

    @Operation
    fun getAndDecrement() = long.getAndDecrement()

    @Operation
    fun getAndAdd(delta: Long) = long.getAndAdd(delta)

    @Operation
    fun addAndGet(delta: Long) = long.addAndGet(delta)

    @Operation
    fun incrementAndGet() = long.incrementAndGet()

    @Operation
    fun decrementAndGet() = long.decrementAndGet()

    /*
     * `plusAssign` and `minusAssign` operators are not supported
     * in atomicfu Kotlin-JVM IR compiler plugin:
     * https://github.com/Kotlin/kotlinx-atomicfu/issues/414
     */

    // @Operation
    // fun plusAssign(delta: Long) {
    //     long += delta
    // }
    //
    // @Operation
    // fun minusAssign(delta: Long) {
    //     long -= delta
    // }
}

// see comment on `AtomicReferenceTest` explaining usage of custom parameter generator here
@Param(name = "string", gen = StringPoolGenerator::class)
class AtomicFUReferenceTest : AbstractLincheckTest() {
    private val ref = atomic("")

    @Operation
    fun get() = ref.value

    @Operation
    fun set(@Param(name = "string") newValue: String) {
        ref.value = newValue
    }

    @Operation
    fun getAndSet(@Param(name = "string") newValue: String) =
        ref.getAndSet(newValue)

    @Operation
    fun compareAndSet(@Param(name = "string") expectedValue: String,
                      @Param(name = "string") newValue: String) =
        ref.compareAndSet(expectedValue, newValue)
}