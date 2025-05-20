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

import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.kotlinx.lincheck_test.AbstractLincheckTest
import org.jetbrains.kotlinx.lincheck_test.util.StringPoolGenerator
import java.lang.invoke.MethodHandles

@Param(name = "string", gen = StringPoolGenerator::class)
class VarHandleReferenceFieldTest : AbstractLincheckTest() {
    @Volatile
    var value: String = ""

    private val varHandle = run {
        val lookup = MethodHandles.lookup()
        lookup.findVarHandle(VarHandleReferenceFieldTest::class.java, "value", String::class.java)
    }

    @Operation
    fun getField() = value

    @Operation
    fun get() =
        varHandle.get(this) as String

    @Operation
    fun getOpaque() =
        varHandle.getOpaque(this) as String

    @Operation
    fun getAcquire() =
        varHandle.getAcquire(this) as String

    @Operation
    fun getVolatile() =
        varHandle.getVolatile(this) as String

    @Operation
    fun setField(@Param(name = "string") newValue: String) =
        run { value = newValue }

    @Operation
    fun set(@Param(name = "string") newValue: String) =
        varHandle.set(this, newValue)

    @Operation
    fun setOpaque(@Param(name = "string") newValue: String) =
        varHandle.setOpaque(this, newValue)

    @Operation
    fun setRelease(@Param(name = "string") newValue: String) =
        varHandle.setRelease(this, newValue)

    @Operation
    fun setVolatile(@Param(name = "string") newValue: String) =
        varHandle.setVolatile(this, newValue)

    @Operation
    fun getAndSet(@Param(name = "string") newValue: String) =
        varHandle.getAndSet(this, newValue) as String

    @Operation
    fun getAndSetAcquire(@Param(name = "string") newValue: String) =
        varHandle.getAndSetAcquire(this, newValue) as String

    @Operation
    fun getAndSetRelease(@Param(name = "string") newValue: String) =
        varHandle.getAndSetRelease(this, newValue) as String

    @Operation
    fun compareAndSet(@Param(name = "string") expectedValue: String, @Param(name = "string") newValue: String) =
        varHandle.compareAndSet(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSet(@Param(name = "string") expectedValue: String, @Param(name = "string") newValue: String) =
        varHandle.weakCompareAndSet(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSetAcquire(@Param(name = "string") expectedValue: String, @Param(name = "string") newValue: String) =
        varHandle.weakCompareAndSetAcquire(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSetRelease(@Param(name = "string") expectedValue: String, @Param(name = "string") newValue: String) =
        varHandle.weakCompareAndSetRelease(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSetPlain(@Param(name = "string") expectedValue: String, @Param(name = "string") newValue: String) =
        varHandle.weakCompareAndSetPlain(this, expectedValue, newValue)
}

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
    fun get() =
        varHandle.get(this) as Int

    @Operation
    fun getOpaque() =
        varHandle.getOpaque(this) as Int

    @Operation
    fun getAcquire() =
        varHandle.getAcquire(this) as Int

    @Operation
    fun getVolatile() =
        varHandle.getVolatile(this) as Int

    @Operation
    fun setField(newValue: Int) =
        run { value = newValue }

    @Operation
    fun set(newValue: Int) =
        varHandle.set(this, newValue)

    @Operation
    fun setOpaque(newValue: Int) =
        varHandle.setOpaque(this, newValue)

    @Operation
    fun setRelease(newValue: Int) =
        varHandle.setRelease(this, newValue)

    @Operation
    fun setVolatile(newValue: Int) =
        varHandle.setVolatile(this, newValue)

    @Operation
    fun getAndSet(newValue: Int) =
        varHandle.getAndSet(this, newValue) as Int

    @Operation
    fun getAndSetAcquire(newValue: Int) =
        varHandle.getAndSetAcquire(this, newValue) as Int

    @Operation
    fun getAndSetRelease(newValue: Int) =
        varHandle.getAndSetRelease(this, newValue) as Int

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
        varHandle.getAndAdd(this, delta) as Int

    @Operation
    fun getAndAddAcquire(delta: Int) =
        varHandle.getAndAddAcquire(this, delta) as Int

    @Operation
    fun getAndAddRelease(delta: Int) =
        varHandle.getAndAddRelease(this, delta) as Int
}

class VarHandleLongFieldTest : AbstractLincheckTest() {
    @Volatile
    var value: Long = 0

    private val varHandle = run {
        val lookup = MethodHandles.lookup()
        lookup.findVarHandle(VarHandleLongFieldTest::class.java, "value", Long::class.javaPrimitiveType)
    }

    @Operation
    fun getField() = value

    @Operation
    fun get() =
        varHandle.get(this) as Long

    @Operation
    fun getOpaque() =
        varHandle.getOpaque(this) as Long

    @Operation
    fun getAcquire() =
        varHandle.getAcquire(this) as Long

    @Operation
    fun getVolatile() =
        varHandle.getVolatile(this) as Long

    @Operation
    fun setField(newValue: Long) =
        run { value = newValue }

    @Operation
    fun set(newValue: Long) =
        varHandle.set(this, newValue)

    @Operation
    fun setOpaque(newValue: Long) =
        varHandle.setOpaque(this, newValue)

    @Operation
    fun setRelease(newValue: Long) =
        varHandle.setRelease(this, newValue)

    @Operation
    fun setVolatile(newValue: Long) =
        varHandle.setVolatile(this, newValue)

    @Operation
    fun getAndSet(newValue: Long) =
        varHandle.getAndSet(this, newValue) as Long

    @Operation
    fun getAndSetAcquire(newValue: Long) =
        varHandle.getAndSetAcquire(this, newValue) as Long

    @Operation
    fun getAndSetRelease(newValue: Long) =
        varHandle.getAndSetRelease(this, newValue) as Long

    @Operation
    fun compareAndSet(expectedValue: Long, newValue: Long) =
        varHandle.compareAndSet(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSet(expectedValue: Long, newValue: Long) =
        varHandle.weakCompareAndSet(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSetAcquire(expectedValue: Long, newValue: Long) =
        varHandle.weakCompareAndSetAcquire(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSetRelease(expectedValue: Long, newValue: Long) =
        varHandle.weakCompareAndSetRelease(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSetPlain(expectedValue: Long, newValue: Long) =
        varHandle.weakCompareAndSetPlain(this, expectedValue, newValue)

    @Operation
    fun getAndAdd(delta: Long) =
        varHandle.getAndAdd(this, delta) as Long

    @Operation
    fun getAndAddAcquire(delta: Long) =
        varHandle.getAndAddAcquire(this, delta) as Long

    @Operation
    fun getAndAddRelease(delta: Long) =
        varHandle.getAndAddRelease(this, delta) as Long
}

class VarHandleByteFieldTest : AbstractLincheckTest() {
    @Volatile
    var value: Byte = 0

    private val varHandle = run {
        val lookup = MethodHandles.lookup()
        lookup.findVarHandle(VarHandleByteFieldTest::class.java, "value", Byte::class.javaPrimitiveType)
    }

    @Operation
    fun getField() = value

    @Operation
    fun get() =
        varHandle.get(this) as Byte

    @Operation
    fun getOpaque() =
        varHandle.getOpaque(this) as Byte

    @Operation
    fun getAcquire() =
        varHandle.getAcquire(this) as Byte

    @Operation
    fun getVolatile() =
        varHandle.getVolatile(this) as Byte

    @Operation
    fun setField(newValue: Byte) =
        run { value = newValue }

    @Operation
    fun set(newValue: Byte) =
        varHandle.set(this, newValue)

    @Operation
    fun setOpaque(newValue: Byte) =
        varHandle.setOpaque(this, newValue)

    @Operation
    fun setRelease(newValue: Byte) =
        varHandle.setRelease(this, newValue)

    @Operation
    fun setVolatile(newValue: Byte) =
        varHandle.setVolatile(this, newValue)

    @Operation
    fun getAndSet(newValue: Byte) =
        varHandle.getAndSet(this, newValue) as Byte

    @Operation
    fun getAndSetAcquire(newValue: Byte) =
        varHandle.getAndSetAcquire(this, newValue) as Byte

    @Operation
    fun getAndSetRelease(newValue: Byte) =
        varHandle.getAndSetRelease(this, newValue) as Byte

    @Operation
    fun compareAndSet(expectedValue: Byte, newValue: Byte) =
        varHandle.compareAndSet(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSet(expectedValue: Byte, newValue: Byte) =
        varHandle.weakCompareAndSet(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSetAcquire(expectedValue: Byte, newValue: Byte) =
        varHandle.weakCompareAndSetAcquire(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSetRelease(expectedValue: Byte, newValue: Byte) =
        varHandle.weakCompareAndSetRelease(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSetPlain(expectedValue: Byte, newValue: Byte) =
        varHandle.weakCompareAndSetPlain(this, expectedValue, newValue)

    @Operation
    fun getAndAdd(delta: Byte) =
        varHandle.getAndAdd(this, delta) as Byte

    @Operation
    fun getAndAddAcquire(delta: Byte) =
        varHandle.getAndAddAcquire(this, delta) as Byte

    @Operation
    fun getAndAddRelease(delta: Byte) =
        varHandle.getAndAddRelease(this, delta) as Byte
}

class VarHandleShortFieldTest : AbstractLincheckTest() {
    @Volatile
    var value: Short = 0

    private val varHandle = run {
        val lookup = MethodHandles.lookup()
        lookup.findVarHandle(VarHandleShortFieldTest::class.java, "value", Short::class.javaPrimitiveType)
    }

    @Operation
    fun getField() = value

    @Operation
    fun get() =
        varHandle.get(this) as Short

    @Operation
    fun getOpaque() =
        varHandle.getOpaque(this) as Short

    @Operation
    fun getAcquire() =
        varHandle.getAcquire(this) as Short

    @Operation
    fun getVolatile() =
        varHandle.getVolatile(this) as Short

    @Operation
    fun setField(newValue: Short) =
        run { value = newValue }

    @Operation
    fun set(newValue: Short) =
        varHandle.set(this, newValue)

    @Operation
    fun setOpaque(newValue: Short) =
        varHandle.setOpaque(this, newValue)

    @Operation
    fun setRelease(newValue: Short) =
        varHandle.setRelease(this, newValue)

    @Operation
    fun setVolatile(newValue: Short) =
        varHandle.setVolatile(this, newValue)

    @Operation
    fun getAndSet(newValue: Short) =
        varHandle.getAndSet(this, newValue) as Short

    @Operation
    fun getAndSetAcquire(newValue: Short) =
        varHandle.getAndSetAcquire(this, newValue) as Short

    @Operation
    fun getAndSetRelease(newValue: Short) =
        varHandle.getAndSetRelease(this, newValue) as Short

    @Operation
    fun compareAndSet(expectedValue: Short, newValue: Short) =
        varHandle.compareAndSet(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSet(expectedValue: Short, newValue: Short) =
        varHandle.weakCompareAndSet(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSetAcquire(expectedValue: Short, newValue: Short) =
        varHandle.weakCompareAndSetAcquire(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSetRelease(expectedValue: Short, newValue: Short) =
        varHandle.weakCompareAndSetRelease(this, expectedValue, newValue)

    @Operation
    fun weakCompareAndSetPlain(expectedValue: Short, newValue: Short) =
        varHandle.weakCompareAndSetPlain(this, expectedValue, newValue)

    @Operation
    fun getAndAdd(delta: Short) =
        varHandle.getAndAdd(this, delta) as Short

    @Operation
    fun getAndAddAcquire(delta: Short) =
        varHandle.getAndAddAcquire(this, delta) as Short

    @Operation
    fun getAndAddRelease(delta: Short) =
        varHandle.getAndAddRelease(this, delta) as Short
}