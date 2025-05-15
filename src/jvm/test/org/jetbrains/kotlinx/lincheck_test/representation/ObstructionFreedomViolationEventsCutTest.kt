/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck_test.representation

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.isInTraceDebuggerMode
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck_test.util.checkLincheckOutput
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Checks that spin-cycle repeated events are cut in case of obstruction freedom violation
 */
class ObstructionFreedomViolationEventsCutTest {

    @Before // spin-loop detection is unsupported in trace debugger mode
    fun setUp() = assumeFalse(isInTraceDebuggerMode)

    private val q = MSQueueBlocking()

    @Operation
    fun enqueue(x: Int) = q.enqueue(x)

    @Operation
    fun dequeue(): Int? = q.dequeue()

    @Test
    fun runModelCheckingTest() = ModelCheckingOptions()
        .checkObstructionFreedom(true)
        .checkImpl(this::class.java)
        .checkLincheckOutput("obstruction_freedom_violation_events_cut")
}

class MSQueueBlocking {
    private val DUMMY_NODE = Node(0)
    private val head = AtomicReference(DUMMY_NODE)
    private val tail = AtomicReference(DUMMY_NODE)

    fun enqueue(x: Int) {
        val newTail = Node(x)
        while (true) { // CAS loop
            val curTail = tail.get()
            if (curTail.next.compareAndSet(null, newTail)) {
                // node has been added -> move tail forward
                tail.compareAndSet(curTail, newTail)
                break
            } else {
                // TODO: helping part is missing now, uncomment the line below to help other enqueue operations to move tail forward
                //tail.compareAndSet(curTail, curTail.next.get())
            }
        }
    }

    fun dequeue(): Int? {
        while (true) { // CAS loop
            val curHead = head.get()
            val headNext = curHead.next.get() ?: return null
            if (head.compareAndSet(curHead, headNext)) {
                return headNext.value
            }
        }
    }

    class Node(
        val value: Int,
        val next: AtomicReference<Node?> = AtomicReference(null)
    )
}

