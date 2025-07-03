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

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import sun.misc.Unsafe

/**
 * Test checks that in case of a field update using Unsafe we remove receiver and offset arguments from the trace.
 */
class SunUnsafeTraceRepresentationTest : BaseTraceRepresentationTest("sun_unsafe_trace") {

    @Volatile
    private var node: IntWrapper = IntWrapper(1)

    override fun operation() {
        unsafe.compareAndSwapObject(this, offset, node, IntWrapper(2))
        unsafe.getAndSetObject(this, offset, IntWrapper(3))
    }

    companion object {
        val unsafe: Unsafe = try {
            val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            unsafeField.get(null) as Unsafe
        } catch (ex: Exception) {
            throw RuntimeException(ex)
        }
        @Suppress("DEPRECATION")
        val offset = unsafe.objectFieldOffset(SunUnsafeTraceRepresentationTest::class.java.getDeclaredField("node"))
    }
}

/**
 * Test checks that in case of a field update using Unsafe we remove receiver and offset arguments from the trace.
 */
class JdkUnsafeTraceRepresentationTest : BaseTraceRepresentationTest("jdk_unsafe_trace") {

    // We use it just to interact with jdk.internal.misc.Unsafe, which we cannot access directly.
    private val hashMap = ConcurrentHashMap<Int, Int>()

    override fun operation() {
        // Here under the hood we interact with the Unsafe instance.
        hashMap[1] = 2
    }

    override fun ModelCheckingOptions.customize() {
        analyzeStdLib(true)
    }
}

/**
 * Test checks that in case of a field update using AtomicReferenceFieldUpdater
 * we remove receiver argument from the trace.
 */
class AtomicUpdaterTraceRepresentationTest : BaseTraceRepresentationTest("atomic_updater_trace") {

    @Volatile
    private var node: IntWrapper = IntWrapper(1)

    override fun operation() {
        nodeUpdater.compareAndSet(this, node, IntWrapper(4))
        nodeUpdater.set(this, IntWrapper(5))
    }

    companion object {
        val nodeUpdater: AtomicReferenceFieldUpdater<AtomicUpdaterTraceRepresentationTest, IntWrapper> =
            AtomicReferenceFieldUpdater.newUpdater(
                AtomicUpdaterTraceRepresentationTest::class.java,
                IntWrapper::class.java,
                "node"
            )
    }
}

data class IntWrapper(val value: Int)