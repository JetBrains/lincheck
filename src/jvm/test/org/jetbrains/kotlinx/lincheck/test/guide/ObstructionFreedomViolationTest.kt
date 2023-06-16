/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.test.guide

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.*
import org.junit.*
import java.util.concurrent.atomic.*

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

class ObstructionFreedomViolationTest  {
    private val q = MSQueueBlocking()

    @Operation
    fun enqueue(x: Int) = q.enqueue(x)

    @Operation
    fun dequeue(): Int? = q.dequeue()

    // @Test TODO: Please, uncomment me and comment the line below to run the test and get the output
    @Test(expected = AssertionError::class)
    fun runModelCheckingTest() = ModelCheckingOptions()
        .checkObstructionFreedom(true)
        .check(this::class)
}