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

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Test
import sun.misc.Unsafe
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

/**
 * Test checks that in case of special field update ways we remove useless arguments from the trace:
 * 1. Unsafe - remove a host object and an offset
 * 2. VarHandle - remove a host object
 * 3. AtomicReferenceFieldUpdater - remove a host object
 */
class UnsafeUpdaterVarHandleTraceRepresentationTest {

    @Volatile
    private var node: Node = Node(1)
    @Volatile
    private var counter: Int = 0

    @Operation
    fun increment(): Int {
        val result = counter++
        actionsJustForTrace()
        return result
    }

    @Test
    fun test() = ModelCheckingOptions()
        .addCustomScenario {
            parallel {
                thread { actor(::increment) }
            }
        }
        .checkImpl(this::class.java)
        .checkLincheckOutput("unsafe_varhandle_updater_trace.txt")

    private fun actionsJustForTrace() {
        unsafe.compareAndSwapObject(this, offset, node, Node(2))
        unsafe.getAndSetObject(this, offset, Node(3))

        nodeUpdater.compareAndSet(this, node, Node(4))
        nodeUpdater.set(this, Node(5))

        nodeHandle.compareAndSet(this, node, Node(6))
        nodeHandle.set(this, Node(7))
    }

    companion object {
        val unsafe: Unsafe = try {
            val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            unsafeField.get(null) as Unsafe
        } catch (ex: Exception) {
            throw RuntimeException(ex)
        }
        val offset = unsafe.objectFieldOffset(UnsafeUpdaterVarHandleTraceRepresentationTest::class.java.getDeclaredField("node"))

        val nodeUpdater: AtomicReferenceFieldUpdater<UnsafeUpdaterVarHandleTraceRepresentationTest, Node> = AtomicReferenceFieldUpdater.newUpdater(
            UnsafeUpdaterVarHandleTraceRepresentationTest::class.java,
            Node::class.java,
            "node"
        )

        val nodeHandle: VarHandle = MethodHandles.lookup()
            .`in`(UnsafeUpdaterVarHandleTraceRepresentationTest::class.java)
            .findVarHandle(UnsafeUpdaterVarHandleTraceRepresentationTest::class.java, "node", Node::class.java)
    }


}

data class Node(val value: Int)