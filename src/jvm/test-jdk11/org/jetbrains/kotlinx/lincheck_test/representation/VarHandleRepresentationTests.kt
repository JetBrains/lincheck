/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle

class VarHandleReferenceRepresentationTest : BaseTraceRepresentationTest("var_handle/varhandle_reference_representation") {

    @Volatile
    private var wrapper = IntWrapper(1)
    private var array = Array(10) { IntWrapper(it) }
    private val valueWrapper = Wrapper()

    override fun operation() {
        // Instance object field operation.
        nodeHandle.compareAndSet(this, wrapper, IntWrapper(2))
        nodeHandle.set(this, IntWrapper(3))
        // Static object field operation.
        staticNodeHandle.compareAndSet(wrapper, IntWrapper(2))
        staticNodeHandle.set(IntWrapper(3))
        // Array object field operation.
        nodeArrayHandle.compareAndSet(array, 1, IntWrapper(1), IntWrapper(2))
        nodeArrayHandle.set(array, 1, IntWrapper(1))
        // Another object field operation.
        wrapperValueHandle.compareAndSet(valueWrapper, 1, 2)
        wrapperValueHandle.set(valueWrapper, 3)
    }

    class Wrapper {
        @Volatile
        @JvmField
        var value: Int = 1
    }

    companion object {
        @Suppress("unused")
        @Volatile
        @JvmStatic
        private var staticWrapper = IntWrapper(1)

        val nodeHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleReferenceRepresentationTest::class.java)
            .findVarHandle(VarHandleReferenceRepresentationTest::class.java, "wrapper", IntWrapper::class.java)
        val staticNodeHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleReferenceRepresentationTest::class.java)
            .findStaticVarHandle(VarHandleReferenceRepresentationTest::class.java, "staticWrapper", IntWrapper::class.java)
        val nodeArrayHandle: VarHandle = MethodHandles.arrayElementVarHandle(Array::class.java)
        val wrapperValueHandle: VarHandle = MethodHandles.lookup()
            .`in`(Wrapper::class.java)
            .findVarHandle(Wrapper::class.java, "value", Int::class.java)
    }
}

class VarHandleIntRepresentationTest : BaseTraceRepresentationTest("var_handle/varhandle_int_representation") {

    @Volatile
    private var number: Int = 1
    private var array = IntArray(10) { it }

    override fun operation() {
        numberHandle.compareAndSet(this, number, 2)
        numberHandle.set(this, 3)

        staticNumberHandle.compareAndSet(staticNumber, 2)
        staticNumberHandle.set(3)

        arrayHandle.compareAndSet(array, 1, 1, 1)
        arrayHandle.set(array, 1, 1)
    }

    companion object {

        @Volatile
        @JvmStatic
        private var staticNumber: Int = 1

        val numberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleIntRepresentationTest::class.java)
            .findVarHandle(VarHandleIntRepresentationTest::class.java, "number", Int::class.java)

        val staticNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleIntRepresentationTest::class.java)
            .findStaticVarHandle(VarHandleIntRepresentationTest::class.java, "staticNumber", Int::class.java)

        val arrayHandle: VarHandle = MethodHandles.arrayElementVarHandle(IntArray::class.java)
    }
}

class VarHandleShortRepresentationTest : BaseTraceRepresentationTest("var_handle/varhandle_short_representation") {

    @Volatile
    private var number: Short = (1).toShort()
    private var array = ShortArray(10) { it.toShort() }

    override fun operation() {
        numberHandle.compareAndSet(this, number, (1).toShort())
        numberHandle.set(this, (2).toShort())

        staticNumberHandle.compareAndSet(staticNumber, (1).toShort())
        staticNumberHandle.set((3).toShort())

        arrayHandle.compareAndSet(array, 1, (3).toShort(), (1).toShort())
        arrayHandle.set(array, 1, (2).toShort())
    }

    companion object {

        @Volatile
        @JvmStatic
        private var staticNumber: Short = (2).toShort()

        val numberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleShortRepresentationTest::class.java)
            .findVarHandle(VarHandleShortRepresentationTest::class.java, "number", Short::class.java)

        val staticNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleShortRepresentationTest::class.java)
            .findStaticVarHandle(VarHandleShortRepresentationTest::class.java, "staticNumber", Short::class.java)

        val arrayHandle: VarHandle = MethodHandles.arrayElementVarHandle(ShortArray::class.java)
    }
}



class VarHandleByteRepresentationTest : BaseTraceRepresentationTest("var_handle/varhandle_byte_representation") {

    @Volatile
    private var number: Byte = (1).toByte()
    private var array = ByteArray(10) { it.toByte() }

    override fun operation() {
        numberHandle.compareAndSet(this, number, (1).toByte())
        numberHandle.set(this, (2).toByte())

        staticNumberHandle.compareAndSet(staticNumber, (1).toByte())
        staticNumberHandle.set((3).toByte())

        arrayHandle.compareAndSet(array, 1, (3).toByte(), (1).toByte())
        arrayHandle.set(array, 1, (2).toByte())
    }

    companion object {

        @Volatile
        @JvmStatic
        private var staticNumber: Byte = (2).toByte()

        val numberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleByteRepresentationTest::class.java)
            .findVarHandle(VarHandleByteRepresentationTest::class.java, "number", Byte::class.java)

        val staticNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleByteRepresentationTest::class.java)
            .findStaticVarHandle(VarHandleByteRepresentationTest::class.java, "staticNumber", Byte::class.java)

        val arrayHandle: VarHandle = MethodHandles.arrayElementVarHandle(ByteArray::class.java)
    }
}



class VarHandleCharRepresentationTest : BaseTraceRepresentationTest("var_handle/varhandle_char_representation") {

    @Volatile
    private var number: Char = '1'
    private var array = CharArray(10) { it.toChar() }

    override fun operation() {
        numberHandle.compareAndSet(this, number, '1')
        numberHandle.set(this, '2')

        staticNumberHandle.compareAndSet(staticNumber, '1')
        staticNumberHandle.set('3')

        arrayHandle.compareAndSet(array, 1, '3', '1')
        arrayHandle.set(array, 1, '2')
    }

    companion object {

        @Volatile
        @JvmStatic
        private var staticNumber: Char = '2'

        val numberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleCharRepresentationTest::class.java)
            .findVarHandle(VarHandleCharRepresentationTest::class.java, "number", Char::class.java)

        val staticNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleCharRepresentationTest::class.java)
            .findStaticVarHandle(VarHandleCharRepresentationTest::class.java, "staticNumber", Char::class.java)

        val arrayHandle: VarHandle = MethodHandles.arrayElementVarHandle(CharArray::class.java)
    }
}



class VarHandleBooleanRepresentationTest : BaseTraceRepresentationTest("var_handle/varhandle_boolean_representation") {

    @Volatile
    private var number: Boolean = false
    private var array = BooleanArray(10) { false }

    override fun operation() {
        numberHandle.compareAndSet(this, number, false)
        numberHandle.set(this, true)

        staticNumberHandle.compareAndSet(staticNumber, false)
        staticNumberHandle.set(false)

        arrayHandle.compareAndSet(array, 1, false, false)
        arrayHandle.set(array, 1, true)
    }

    companion object {

        @Volatile
        @JvmStatic
        private var staticNumber: Boolean = true

        val numberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleBooleanRepresentationTest::class.java)
            .findVarHandle(VarHandleBooleanRepresentationTest::class.java, "number", Boolean::class.java)

        val staticNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleBooleanRepresentationTest::class.java)
            .findStaticVarHandle(VarHandleBooleanRepresentationTest::class.java, "staticNumber", Boolean::class.java)

        val arrayHandle: VarHandle = MethodHandles.arrayElementVarHandle(BooleanArray::class.java)
    }
}



class VarHandleLongRepresentationTest : BaseTraceRepresentationTest("var_handle/varhandle_long_representation") {

    @Volatile
    private var number: Long = 1L
    private var array = LongArray(10) { it.toLong() }

    override fun operation() {
        numberHandle.compareAndSet(this, number, 1L)
        numberHandle.set(this, 2L)

        staticNumberHandle.compareAndSet(staticNumber, 1L)
        staticNumberHandle.set(3L)

        arrayHandle.compareAndSet(array, 1, 3L, 1L)
        arrayHandle.set(array, 1, 2L)
    }

    companion object {

        @Volatile
        @JvmStatic
        private var staticNumber: Long = 2L

        val numberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleLongRepresentationTest::class.java)
            .findVarHandle(VarHandleLongRepresentationTest::class.java, "number", Long::class.java)

        val staticNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleLongRepresentationTest::class.java)
            .findStaticVarHandle(VarHandleLongRepresentationTest::class.java, "staticNumber", Long::class.java)

        val arrayHandle: VarHandle = MethodHandles.arrayElementVarHandle(LongArray::class.java)
    }
}



class VarHandleFloatRepresentationTest : BaseTraceRepresentationTest("var_handle/varhandle_float_representation") {

    @Volatile
    private var number: Float = 1f
    private var array = FloatArray(10) { it.toFloat() }

    override fun operation() {
        numberHandle.compareAndSet(this, number, 1f)
        numberHandle.set(this, 2f)

        staticNumberHandle.compareAndSet(staticNumber, 1f)
        staticNumberHandle.set(3f)

        arrayHandle.compareAndSet(array, 1, 3f, 1f)
        arrayHandle.set(array, 1, 2f)
    }

    companion object {

        @Volatile
        @JvmStatic
        private var staticNumber: Float = 2f

        val numberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleFloatRepresentationTest::class.java)
            .findVarHandle(VarHandleFloatRepresentationTest::class.java, "number", Float::class.java)

        val staticNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleFloatRepresentationTest::class.java)
            .findStaticVarHandle(VarHandleFloatRepresentationTest::class.java, "staticNumber", Float::class.java)

        val arrayHandle: VarHandle = MethodHandles.arrayElementVarHandle(FloatArray::class.java)
    }
}



class VarHandleDoubleRepresentationTest : BaseTraceRepresentationTest("var_handle/varhandle_double_representation") {

    @Volatile
    private var number: Double = 1.0
    private var array = DoubleArray(10) { it.toDouble() }

    override fun operation() {
        numberHandle.compareAndSet(this, number, 1.0)
        numberHandle.set(this, 2.0)

        staticNumberHandle.compareAndSet(staticNumber, 1.0)
        staticNumberHandle.set(3.0)

        arrayHandle.compareAndSet(array, 1, 3.0, 1.0)
        arrayHandle.set(array, 1, 2.0)
    }

    companion object {

        @Volatile
        @JvmStatic
        private var staticNumber: Double = 2.0

        val numberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleDoubleRepresentationTest::class.java)
            .findVarHandle(VarHandleDoubleRepresentationTest::class.java, "number", Double::class.java)

        val staticNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleDoubleRepresentationTest::class.java)
            .findStaticVarHandle(VarHandleDoubleRepresentationTest::class.java, "staticNumber", Double::class.java)

        val arrayHandle: VarHandle = MethodHandles.arrayElementVarHandle(DoubleArray::class.java)
    }
}