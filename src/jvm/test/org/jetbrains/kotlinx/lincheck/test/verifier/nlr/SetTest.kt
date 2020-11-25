/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.test.verifier.nlr

import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.CrashFree
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.annotations.Recoverable
import org.jetbrains.kotlinx.lincheck.nvm.Persistent
import org.jetbrains.kotlinx.lincheck.paramgen.ThreadIdGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.util.concurrent.atomic.AtomicMarkableReference


private const val THREADS_NUMBER = 3

@StressCTest(
    sequentialSpecification = SequentialSet::class,
    threads = THREADS_NUMBER,
    addCrashes = true
)
class SetTest {
    private val set = NRLSet<Int>(2 + THREADS_NUMBER)

    @Operation
    fun add(@Param(gen = ThreadIdGen::class) threadId: Int, key: Int) = set.add(threadId, key)

    @Operation
    fun remove(@Param(gen = ThreadIdGen::class) threadId: Int, key: Int) = set.remove(threadId, key)

    @Operation
    fun contains(key: Int) = set.contains(key)

    @Test
    fun test() = LinChecker.check(this::class.java)
}

class SequentialSet : VerifierState() {
    private val set = mutableSetOf<Int>()

    fun add(ignore: Int, key: Int) = set.add(key)
    fun remove(ignore: Int, key: Int) = set.remove(key)
    fun contains(key: Int) = set.contains(key)

    override fun extractState() = set.toList()
}

private const val NULL_DELETER = -1

/**
 * @see [Tracking in Order to Recover: Recoverable Lock-Free Data Structures](https://arxiv.org/pdf/1905.13600.pdf)
 */
class NRLSet<T : Comparable<T>> @Recoverable constructor(threadsCount: Int) {

    private inner class Node(val value: T, next: Node?) {
        val next: AtomicMarkableReference<Node?> = AtomicMarkableReference(next, false)
        val deleter = atomic(NULL_DELETER)
    }

    private inner class Info(var node: Persistent<Node?> = Persistent(null)) {
        var result = Persistent<Boolean?>(null)
    }

    private inner class PrevNextPair(val previous: Node?, val next: Node?)

    private val recoveryData = MutableList(threadsCount) { Persistent<Info?>(null) }
    private val checkPointer = Array(threadsCount) { Persistent(0) }
    private val head = atomic<Node?>(null)

    @Recoverable
    private fun findPrevNext(value: T): PrevNextPair {
        start@ while (true) {
            var previous: Node? = null
            var current = head.value
            while (current != null) {
                val isDeleted = booleanArrayOf(false)
                val next = current.next[isDeleted]
                if (isDeleted[0]) {
                    if (previous?.next?.compareAndSet(current, next, false, false)
                            ?: head.compareAndSet(current, next)
                    ) {
                        current = next
                        continue
                    } else {
                        continue@start
                    }
                }
                if (current.value >= value) {
                    break
                }
                previous = current
                current = next
            }
            return PrevNextPair(previous, current)
        }
    }

    @Recoverable(beforeMethod = "addBefore", recoverMethod = "addRecover")
    fun add(p: Int, value: T) = addImpl(p, value)

    private fun addImpl(p: Int, value: T): Boolean {
        val newNode = recoveryData[p].read(p)!!.node.read(p)!!
        while (true) {
            val prevNext = findPrevNext(value)
            val previous = prevNext.previous
            val next = prevNext.next
            if (next != null && next.value.compareTo(value) == 0) {
                recoveryData[p].read(p)!!.result.write(p, false)
                recoveryData[p].read(p)!!.result.flush(p)
                return false
            }
            newNode.next[next] = false
            // flush
            if (previous == null) {
                if (head.compareAndSet(next, newNode)) {
                    recoveryData[p].read(p)!!.result.write(p, true)
                    recoveryData[p].read(p)!!.result.flush(p)
                    return true
                }
            } else {
                if (previous.next.compareAndSet(next, newNode, false, false)) {
                    recoveryData[p].read(p)!!.result.write(p, true)
                    recoveryData[p].read(p)!!.result.flush(p)
                    return true
                }
            }
        }
    }

    fun addBefore(p: Int, value: T) {
        checkPointer[p].write(p, 0)
        checkPointer[p].flush(p)
        recoveryData[p].write(p, Info(Persistent(Node(value, null))))
        recoveryData[p].flush(p)
        checkPointer[p].write(p, 1)
        checkPointer[p].flush(p)
    }

    fun addRecover(p: Int, value: T): Boolean {
        if (checkPointer[p].read(p) == 0) return addImpl(p, value)
        val node = recoveryData[p].read(p)!!.node.read(p)!!
        val result = recoveryData[p].read(p)!!.result.read(p)
        if (result != null) return result
        val prevNext = findPrevNext(value)
        val current = prevNext.next
        if (current === node || node.next.isMarked) {
            recoveryData[p].read(p)!!.result.write(p, true)
            recoveryData[p].read(p)!!.result.flush(p)
            return true
        }
        return addImpl(p, value)
    }

    @Recoverable(beforeMethod = "removeBefore", recoverMethod = "removeRecover")
    fun remove(p: Int, value: T) = removeImpl(p, value)

    private fun removeImpl(p: Int, value: T): Boolean {
        val prevNext = findPrevNext(value)
        val previous = prevNext.previous
        val current = prevNext.next
        if (current == null || current.value.compareTo(value) != 0) {
            recoveryData[p].read(p)!!.result.write(p, false)
            recoveryData[p].read(p)!!.result.flush(p)
            return false
        }
        recoveryData[p].read(p)!!.node.write(p, current)
        recoveryData[p].read(p)!!.node.flush(p)
        while (!current.next.isMarked) {
            val next = current.next.reference
            current.next.compareAndSet(next, next, false, true)
        }
        val next = current.next.reference
        previous?.next?.compareAndSet(current, next, false, false) ?: head.compareAndSet(current, next)
        val result = current.deleter.compareAndSet(NULL_DELETER, p)
        recoveryData[p].read(p)!!.result.write(p, result)
        recoveryData[p].read(p)!!.result.flush(p)
        return result
    }

    fun removeBefore(p: Int, value: T) {
        checkPointer[p].write(p, 0)
        checkPointer[p].flush(p)
        recoveryData[p].write(p, Info())
        recoveryData[p].flush(p)
        checkPointer[p].write(p, 1)
        checkPointer[p].flush(p)
    }

    fun removeRecover(p: Int, value: T): Boolean {
        if (checkPointer[p].read(p) == 0) return removeImpl(p, value)
        val result = recoveryData[p].read(p)!!.result.read(p)
        if (result != null) return result
        val node = recoveryData[p].read(p)!!.node.read(p)
        if (node != null && node.next.isMarked) {
            node.deleter.compareAndSet(NULL_DELETER, p)
            val res = node.deleter.value == p
            recoveryData[p].read(p)!!.result.write(p, res)
            recoveryData[p].read(p)!!.result.flush(p)
            return res
        }
        return removeImpl(p, value)
    }

    @Recoverable
    operator fun contains(value: T): Boolean {
        var current = head.value
        val isDeleted = booleanArrayOf(false)
        while (current != null && current.value <= value) {
            val next = current.next[isDeleted]
            if (current.value.compareTo(value) == 0 && !isDeleted[0]) {
                return true
            }
            current = next
        }
        return false
    }
}
