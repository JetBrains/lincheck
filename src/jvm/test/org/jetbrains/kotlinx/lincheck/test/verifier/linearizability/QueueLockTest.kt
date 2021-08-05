/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.test.verifier.linearizability

import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.paramgen.ThreadIdGen
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState

private const val THREADS = 3

internal class QueueLockTest : AbstractLincheckTest() {
    private val lock = QueueLock(THREADS + 2)
    private val counter = SequentialCounter()

    @Operation
    fun inc(@Param(gen = ThreadIdGen::class) threadId: Int): Int {
        lock.lock(threadId)
        val result = counter.inc()
        lock.unlock(threadId)
        return result
    }

    override fun <O : Options<O, *>> O.customize() {
        threads(THREADS)
        sequentialSpecification(SequentialCounter::class.java)
    }
}


internal class SequentialCounter : VerifierState() {
    @Volatile
    private var value = 0
    fun inc(threadId: Int) = inc()
    fun inc() = ++value
    override fun extractState() = value
}


private class QNode(@Volatile var next: QNode?, @Volatile var locked: Boolean)

/**
 * @see <a href="https://pdos.csail.mit.edu/6.828/2010/readings/mcs.pdf">Mellor-Crummey and Scott Mutual Exclusion Algorithm</a>
 */
internal class QueueLock(threads: Int) {
    private val q = Array(threads) { QNode(null, false) }
    private val t = atomic<QNode?>(null)

    fun lock(threadId: Int) {
        val qi = q[threadId]
        qi.next = null
        val previous = t.getAndSet(qi)
        if (previous !== null) {
            qi.locked = true
            previous.next = qi
            while (qi.locked);
        }
    }

    fun unlock(threadId: Int) {
        val qi = q[threadId]
        if (qi.next === null) {
            if (t.compareAndSet(qi, null)) return
            while (qi.next === null);
        }
        qi.next!!.locked = false
    }
}
