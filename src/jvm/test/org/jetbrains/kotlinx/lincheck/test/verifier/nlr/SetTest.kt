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
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.annotations.Recoverable
import org.jetbrains.kotlinx.lincheck.nvm.api.NonVolatileRef
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.paramgen.ThreadIdGen
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.util.concurrent.atomic.AtomicMarkableReference


private const val THREADS_NUMBER = 3

@StressCTest(
    sequentialSpecification = SequentialSet::class,
    threads = THREADS_NUMBER,
    recover = Recover.NRL
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
        val next: AtomicMarkableReference<Node?> = AtomicMarkableReference(next, false) // TODO use nonVolatile here
        val deleter = atomic(NULL_DELETER) // TODO use nonVolatile here
    }

    private inner class Info(var node: NonVolatileRef<Node?> = nonVolatile(null)) {
        var result = nonVolatile(null as Boolean?)
    }

    private inner class PrevNextPair(val previous: Node?, val next: Node?)

    private val recoveryData = MutableList(threadsCount) { nonVolatile<Info?>(null) }
    private val checkPointer = Array(threadsCount) { nonVolatile(0) }
    private val head = nonVolatile<Node?>(null)

    @Recoverable
    private fun findPrevNext(value: T): PrevNextPair {
        start@ while (true) {
            var previous: Node? = null
            var current = head.value
            head.flush()
            while (current != null) {
                val isDeleted = booleanArrayOf(false)
                val next = current.next[isDeleted]
                if (isDeleted[0]) {
                    if (previous?.next?.compareAndSet(current, next, false, false)
                            ?: head.compareAndSet(current, next).also { head.flush() }
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
        val newNode = recoveryData[p].value!!.node.value!!
        while (true) {
            val prevNext = findPrevNext(value)
            val previous = prevNext.previous
            val next = prevNext.next
            if (next != null && next.value.compareTo(value) == 0) {
                recoveryData[p].value!!.result.value = false
                recoveryData[p].value!!.result.flush()
                return false
            }
            newNode.next[next] = false
            // flush
            if (previous == null) {
                if (head.compareAndSet(next, newNode)) {
                    head.flush()
                    recoveryData[p].value!!.result.value = true
                    recoveryData[p].value!!.result.flush()
                    return true
                }
                head.flush()
            } else {
                if (previous.next.compareAndSet(next, newNode, false, false)) {
                    recoveryData[p].value!!.result.value = true
                    recoveryData[p].value!!.result.flush()
                    return true
                }
            }
        }
    }

    fun addBefore(p: Int, value: T) {
        checkPointer[p].value = 0
        checkPointer[p].flush()
        recoveryData[p].value = Info(nonVolatile(Node(value, null)))
        recoveryData[p].flush()
        checkPointer[p].value = 1
        checkPointer[p].flush()
    }

    fun addRecover(p: Int, value: T): Boolean {
        if (checkPointer[p].value == 0) return addImpl(p, value)
        val node = recoveryData[p].value!!.node.value!!
        val result = recoveryData[p].value!!.result.value
        if (result != null) return result
        val prevNext = findPrevNext(value)
        val current = prevNext.next
        if (current === node || node.next.isMarked) {
            recoveryData[p].value!!.result.value = true
            recoveryData[p].value!!.result.flush()
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
            recoveryData[p].value!!.result.value = false
            recoveryData[p].value!!.result.flush()
            return false
        }
        recoveryData[p].value!!.node.value = current
        recoveryData[p].value!!.node.flush()
        while (!current.next.isMarked) {
            val next = current.next.reference
            current.next.compareAndSet(next, next, false, true)
        }
        val next = current.next.reference
        previous?.next?.compareAndSet(current, next, false, false)
            ?: head.compareAndSet(current, next).also { head.flush() }
        val result = current.deleter.compareAndSet(NULL_DELETER, p)
        recoveryData[p].value!!.result.value = result
        recoveryData[p].value!!.result.flush()
        return result
    }

    fun removeBefore(p: Int, value: T) {
        checkPointer[p].value = 0
        checkPointer[p].flush()
        recoveryData[p].value = Info()
        recoveryData[p].flush()
        checkPointer[p].value = 1
        checkPointer[p].flush()
    }

    fun removeRecover(p: Int, value: T): Boolean {
        if (checkPointer[p].value == 0) return removeImpl(p, value)
        val result = recoveryData[p].value!!.result.value
        if (result != null) return result
        val node = recoveryData[p].value!!.node.value
        if (node != null && node.next.isMarked) {
            node.deleter.compareAndSet(NULL_DELETER, p)
            val res = node.deleter.value == p
            recoveryData[p].value!!.result.value = res
            recoveryData[p].value!!.result.flush()
            return res
        }
        return removeImpl(p, value)
    }

    @Recoverable
    operator fun contains(value: T): Boolean {
        var current = head.value
        head.flush()
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
