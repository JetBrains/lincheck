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

import org.jetbrains.lincheck.util.UnsafeHolder


class UnsafeTraceRepresentationTest : BaseTraceRepresentationTest("unsafe_representation_trace") {

    private val array = Array(3) { IntWrapper(it) }
    private var value: Int = 2
    private val node = IntWrapper(3)

    override fun operation() {
        unsafe.getObject(array, baseOffset + indexScale * 2L)
        unsafe.compareAndSwapObject(this, nodeFieldOffset, node, IntWrapper(4))
        unsafe.compareAndSwapInt(this, valueFieldOffset, value, 3)

        unsafe.compareAndSwapObject(staticNodeFieldBase, staticNodeFieldOffset, staticNode, IntWrapper(6))
        unsafe.compareAndSwapInt(node, nodeValueOffset, node.value, 6)
    }

    private data class IntWrapper(val value: Int)

    @Suppress("DEPRECATION") // Unsafe
    companion object {
        val unsafe = UnsafeHolder.UNSAFE

        private val nodeField = UnsafeTraceRepresentationTest::class.java.getDeclaredField("node")
        private val staticNodeField = UnsafeTraceRepresentationTest::class.java.getDeclaredField("staticNode")
        private val valueField = UnsafeTraceRepresentationTest::class.java.getDeclaredField("value")
        private val nodeValueField = IntWrapper::class.java.getDeclaredField("value")

        private val baseOffset: Int = unsafe.arrayBaseOffset(Array::class.java)
        private val indexScale: Int = unsafe.arrayIndexScale(Array::class.java)

        private val nodeFieldOffset = unsafe.objectFieldOffset(nodeField)
        private val staticNodeFieldOffset = unsafe.staticFieldOffset(staticNodeField)
        private val staticNodeFieldBase = unsafe.staticFieldBase(staticNodeField)
        private val valueFieldOffset = unsafe.objectFieldOffset(valueField)
        private val nodeValueOffset = unsafe.objectFieldOffset(nodeValueField)

        @JvmStatic
        private val staticNode = IntWrapper(5)
    }

}