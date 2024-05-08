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

class VarHandleRepresentationTest: BaseFailingTest("varhandle_representation.txt") {

    @Volatile
    private var node = Node(1)
    @Volatile
    private var intNumber: Int = 1
    @Volatile
    private var longNumber: Long = 1L
    @Volatile
    private var shortNumber: Short = (1).toShort()
    @Volatile
    private var byteNumber: Byte = (1).toByte()
    @Volatile
    private var charNumber: Char = '1'
    @Volatile
    private var boolFlag: Boolean = false
    @Volatile
    private var doubleNumber: Double = 1.0
    @Volatile
    private var floatNumber: Float = 1f

    /* Arrays */
    private var nodeArray = Array(10) { Node(it) }
    private var intArray = IntArray(10) { it }
    private var longArray = LongArray(10) { it.toLong() }
    private var shortArray = ShortArray(10) { it.toShort() }
    private var byteArray = ByteArray(10) { it.toByte() }
    private var charArray = CharArray(10) { it.toChar() }
    private var boolArray = BooleanArray(10) { false }
    private var doubleArray = DoubleArray(10) { it.toDouble() }
    private var floatArray = FloatArray(10) { it.toFloat() }

    private val wrapper = Wrapper()


    override fun actionsJustForTrace() {
        instanceFieldHandles()
        staticFieldHandles()
        arrayFieldHandles()
        otherInstanceVarHandle()
    }

    private fun otherInstanceVarHandle() {
        wrapperValueHandle.compareAndSet(wrapper, 1, 2)
        wrapperValueHandle.set(wrapper, 3)
    }

    private fun instanceFieldHandles() {
        nodeHandle.compareAndSet(this, node, Node(2))
        nodeHandle.set(this, Node(3))

        intNumberHandle.compareAndSet(this, intNumber, 2)
        intNumberHandle.set(this, 3)

        longNumberHandle.compareAndSet(this, longNumber, 2L)
        longNumberHandle.set(this, 3L)

        shortNumberHandle.compareAndSet(this, shortNumber, (2).toShort())
        shortNumberHandle.set(this, (3).toShort())

        byteNumberHandle.compareAndSet(this, byteNumber, (2).toByte())
        byteNumberHandle.set(this, (1).toByte())

        charNumberHandle.compareAndSet(this, charNumber, '2')
        charNumberHandle.set(this, '1')

        booleanNumberHandle.compareAndSet(this, boolFlag, true)
        booleanNumberHandle.set(this, false)

        doubleNumberHandle.compareAndSet(this, doubleNumber, 2.0)
        doubleNumberHandle.set(this, 3.0)

        floatNumberHandle.compareAndSet(this, floatNumber, 2.0F)
        floatNumberHandle.set(this, 3.0F)
    }

    private fun staticFieldHandles() {
        staticNodeHandle.compareAndSet(node, Node(2))
        staticNodeHandle.set(Node(3))

        staticIntNumberHandle.compareAndSet(staticIntNumber, 2)
        staticIntNumberHandle.set(3)

        staticLongNumberHandle.compareAndSet(staticLongNumber, 2L)
        staticLongNumberHandle.set(3L)

        staticShortNumberHandle.compareAndSet(staticShortNumber, (2).toShort())
        staticShortNumberHandle.set((3).toShort())

        staticByteNumberHandle.compareAndSet(staticByteNumber, (2).toByte())
        staticByteNumberHandle.set((1).toByte())

        staticCharNumberHandle.compareAndSet(staticCharNumber, '2')
        staticCharNumberHandle.set('1')

        staticBooleanNumberHandle.compareAndSet(staticBoolFlag, true)
        staticBooleanNumberHandle.set(false)

        staticDoubleNumberHandle.compareAndSet(staticDoubleNumber, 2.0)
        staticDoubleNumberHandle.set(3.0)

        staticFloatNumberHandle.compareAndSet(staticFloatNumber, 2.0F)
        staticFloatNumberHandle.set(3.0F)
    }

    private fun arrayFieldHandles() {
        nodeArrayHandle.compareAndSet(nodeArray, 1, Node(1), Node(2))
        nodeArrayHandle.set(nodeArray, 1, Node(1))

        intArrayHandle.compareAndSet(intArray, 1, 1, 1)
        intArrayHandle.set(intArray, 1, 1)

        longArrayHandle.compareAndSet(longArray, 1, 1L, 3L)
        longArrayHandle.set(longArray, 1, 2L)

        shortArrayHandle.compareAndSet(shortArray, 1, (1).toShort(), (2).toShort())
        shortArrayHandle.set(shortArray, 1, (4).toShort())

        byteArrayHandle.compareAndSet(byteArray, 1, (2).toByte(), (3).toByte())
        byteArrayHandle.set(byteArray, 1, (4).toByte())

        charArrayHandle.compareAndSet(charArray, 1, '1', '2')
        charArrayHandle.set(charArray, 1, '3')

        booleanArrayHandle.compareAndSet(boolArray, 1, false, true)
        booleanArrayHandle.set(boolArray, 1, true)

        doubleArrayHandle.compareAndSet(doubleArray, 1, 1.0, 2.0)
        doubleArrayHandle.set(doubleArray, 1, 3.0)

        floatArrayHandle.compareAndSet(floatArray, 1, 1f, 2f)
        floatArrayHandle.set(floatArray, 1, 3f)
    }

    class Wrapper {
        @Volatile
        @JvmField
        var value: Int = 1
    }


    companion object {
        @Volatile
        @JvmStatic
        private var staticNode = Node(1)

        @Volatile
        @JvmStatic
        private var staticIntNumber: Int = 1

        @Volatile
        @JvmStatic
        private var staticLongNumber: Long = 1L

        @Volatile
        @JvmStatic
        private var staticShortNumber: Short = (1).toShort()

        @Volatile
        @JvmStatic
        private var staticByteNumber: Byte = (1).toByte()

        @Volatile
        @JvmStatic
        private var staticCharNumber: Char = '1'

        @Volatile
        @JvmStatic
        private var staticBoolFlag: Boolean = false

        @Volatile
        @JvmStatic
        private var staticDoubleNumber: Double = 1.0

        @Volatile
        @JvmStatic
        private var staticFloatNumber: Float = 1f

        /* Instance VarHandles */

        val nodeHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleRepresentationTest::class.java)
            .findVarHandle(VarHandleRepresentationTest::class.java, "node", Node::class.java)
        val intNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleRepresentationTest::class.java)
            .findVarHandle(VarHandleRepresentationTest::class.java, "intNumber", Int::class.java)
        val longNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleRepresentationTest::class.java)
            .findVarHandle(VarHandleRepresentationTest::class.java, "longNumber", Long::class.java)
        val shortNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleRepresentationTest::class.java)
            .findVarHandle(VarHandleRepresentationTest::class.java, "shortNumber", Short::class.java)
        val byteNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleRepresentationTest::class.java)
            .findVarHandle(VarHandleRepresentationTest::class.java, "byteNumber", Byte::class.java)
        val charNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleRepresentationTest::class.java)
            .findVarHandle(VarHandleRepresentationTest::class.java, "charNumber", Char::class.java)
        val booleanNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleRepresentationTest::class.java)
            .findVarHandle(VarHandleRepresentationTest::class.java, "boolFlag", Boolean::class.java)
        val doubleNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleRepresentationTest::class.java)
            .findVarHandle(VarHandleRepresentationTest::class.java, "doubleNumber", Double::class.java)
        val floatNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleRepresentationTest::class.java)
            .findVarHandle(VarHandleRepresentationTest::class.java, "floatNumber", Float::class.java)

        /* Static VarHandles */

        val staticNodeHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleRepresentationTest::class.java)
            .findStaticVarHandle(VarHandleRepresentationTest::class.java, "staticNode", Node::class.java)
        val staticIntNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleRepresentationTest::class.java)
            .findStaticVarHandle(VarHandleRepresentationTest::class.java, "staticIntNumber", Int::class.java)
        val staticLongNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleRepresentationTest::class.java)
            .findStaticVarHandle(VarHandleRepresentationTest::class.java, "staticLongNumber", Long::class.java)
        val staticShortNumberHandle: VarHandle =
            MethodHandles.lookup()
                .`in`(VarHandleRepresentationTest::class.java)
                .findStaticVarHandle(VarHandleRepresentationTest::class.java, "staticShortNumber", Short::class.java)
        val staticByteNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleRepresentationTest::class.java)
            .findStaticVarHandle(VarHandleRepresentationTest::class.java, "staticByteNumber", Byte::class.java)
        val staticCharNumberHandle: VarHandle = MethodHandles.lookup()
            .`in`(VarHandleRepresentationTest::class.java)
            .findStaticVarHandle(VarHandleRepresentationTest::class.java, "staticCharNumber", Char::class.java)
        val staticBooleanNumberHandle: VarHandle =
            MethodHandles.lookup()
                .`in`(VarHandleRepresentationTest::class.java)
                .findStaticVarHandle(VarHandleRepresentationTest::class.java, "staticBoolFlag", Boolean::class.java)
        val staticDoubleNumberHandle: VarHandle =
            MethodHandles.lookup()
                .`in`(VarHandleRepresentationTest::class.java)
                .findStaticVarHandle(VarHandleRepresentationTest::class.java, "staticDoubleNumber", Double::class.java)
        val staticFloatNumberHandle: VarHandle =
            MethodHandles.lookup()
                .`in`(VarHandleRepresentationTest::class.java)
                .findStaticVarHandle(VarHandleRepresentationTest::class.java, "staticFloatNumber", Float::class.java)

        /* Array handles */
        val nodeArrayHandle: VarHandle = MethodHandles.arrayElementVarHandle(Array::class.java)
        val intArrayHandle: VarHandle = MethodHandles.arrayElementVarHandle(IntArray::class.java)
        val longArrayHandle: VarHandle = MethodHandles.arrayElementVarHandle(LongArray::class.java)
        val shortArrayHandle: VarHandle = MethodHandles.arrayElementVarHandle(ShortArray::class.java)
        val byteArrayHandle: VarHandle = MethodHandles.arrayElementVarHandle(ByteArray::class.java)
        val charArrayHandle: VarHandle = MethodHandles.arrayElementVarHandle(CharArray::class.java)
        val booleanArrayHandle: VarHandle = MethodHandles.arrayElementVarHandle(BooleanArray::class.java)
        val doubleArrayHandle: VarHandle = MethodHandles.arrayElementVarHandle(DoubleArray::class.java)
        val floatArrayHandle: VarHandle = MethodHandles.arrayElementVarHandle(FloatArray::class.java)

        /* Other class VarHandle */
        val wrapperValueHandle: VarHandle = MethodHandles.lookup()
            .`in`(Wrapper::class.java)
            .findVarHandle(Wrapper::class.java, "value", Int::class.java)
    }
}
