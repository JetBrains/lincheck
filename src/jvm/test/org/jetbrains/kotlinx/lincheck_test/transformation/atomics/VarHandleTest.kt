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

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest
import java.lang.invoke.MethodHandles

class VarHandleIntegerFieldTest : AbstractLincheckTest() {
    @Volatile
    var value: Int = 0

    private val varHandle = run {
        val lookup = MethodHandles.lookup()
        lookup.findVarHandle(VarHandleIntegerFieldTest::class.java, "value", Int::class.javaPrimitiveType)
    }

    @Operation
    fun getField() = value

    @Operation
    fun get() = varHandle.get(this)

    @Operation
    fun getOpaque() = varHandle.getOpaque(this)

    @Operation
    fun getAcquire() = varHandle.getAcquire(this)

    @Operation
    fun getVolatile() = varHandle.getVolatile(this)

    @Operation
    fun setField(newValue: Int) = run { value = newValue }

    @Operation
    fun set(newValue: Int) = varHandle.set(this, newValue)

    @Operation
    fun setOpaque(newValue: Int) = varHandle.setOpaque(this, newValue)

    @Operation
    fun setRelease(newValue: Int) = varHandle.setRelease(this, newValue)

    @Operation
    fun setVolatile(newValue: Int) = varHandle.setVolatile(this, newValue)

    @Operation
    fun getAndSet(newValue: Int) =
        varHandle.getAndSet(this, newValue)

    @Operation
    fun getAndSetAcquire(newValue: Int) =
        varHandle.getAndSetAcquire(this, newValue)

    @Operation
    fun getAndSetRelease(newValue: Int) =
        varHandle.getAndSetRelease(this, newValue)

    @Operation
    fun compareAndSet(expectedValue: Int, newValue: Int) =
        varHandle.compareAndSet(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSet(expectedValue: Int, newValue: Int) =
        varHandle.weakCompareAndSet(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSetAcquire(expectedValue: Int, newValue: Int) =
        varHandle.weakCompareAndSetAcquire(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSetRelease(expectedValue: Int, newValue: Int) =
        varHandle.weakCompareAndSetRelease(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSetPlain(expectedValue: Int, newValue: Int) =
        varHandle.weakCompareAndSetPlain(this, expectedValue, newValue)

    @Operation
    fun getAndAdd(delta: Int) =
        varHandle.getAndAdd(this, delta)

    @Operation
    fun getAndAddAcquire(delta: Int) =
        varHandle.getAndAddAcquire(this, delta)

    @Operation
    fun getAndAddRelease(delta: Int) =
        varHandle.getAndAddRelease(this, delta)

}