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

package org.jetbrains.kotlinx.lincheck.test.verifier.durable

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.DurableRecoverAll
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.NonVolatileRef
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressCTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedDeque

private const val THREADS_NUMBER = 3

internal interface DurableSet {
    fun add(key: Int): Boolean
    fun remove(key: Int): Boolean
    fun contains(key: Int): Boolean
    fun recover()
}

/**
 * @see  <a https://arxiv.org/pdf/1909.02852.pdf">Efficient Lock-Free Durable Sets</a>
 */
@StressCTest(
    sequentialSpecification = SequentialSet::class,
    threads = THREADS_NUMBER,
    recover = Recover.DURABLE,
    minimizeFailedScenario = false
)
internal class DurableLinkFreeListTest {
    val s = DurableLinkFreeList<Any>()

    @Operation
    fun add(key: Int) = s.add(key)

    @Operation
    fun remove(key: Int) = s.remove(key)

    @Operation
    fun contains(key: Int) = s.contains(key)

    @DurableRecoverAll
    fun recover() = s.recover()

    @Test
    fun test() = LinChecker.check(this::class.java)
}

class SequentialSet : VerifierState() {
    private val set = mutableSetOf<Int>()

    fun add(key: Int) = set.add(key)
    fun remove(key: Int) = set.remove(key)
    fun contains(key: Int) = set.contains(key)

    override fun extractState() = set.toList()
}


private const val MASK_LOW = 1
private const val MASK_HIGH = 2

private data class NextRef<T>(val next: ListNode<T>?, val deleted: Boolean)

private class ListNode<T>(val key: Int, val value: T? = null, nextNode: ListNode<T>?, valid: Int = 0) {
    val validityBits = nonVolatile(valid)
    val insertFlushFlag = nonVolatile(false)
    val deleteFlushFlag = nonVolatile(false)
    val nextRef = nonVolatile(NextRef(nextNode, false))

    private fun v1(metadata: Int) = (metadata and MASK_HIGH) ushr 1
    private fun v2(metadata: Int) = metadata and MASK_LOW

    fun isValid(metadata: Int = validityBits.value) = v1(metadata) == v2(metadata)

    fun makeValid() {
        val metadata = validityBits.value
        if (isValid(metadata)) return
        val v1 = v1(metadata)
        val newMetadata = v1 + (metadata and MASK_LOW.inv())
        validityBits.value = newMetadata
    }

    fun flipV1() {
        val metadata = validityBits.value
        val newV1 = (metadata and MASK_HIGH) xor MASK_HIGH
        val newMetadata = newV1 + (metadata and MASK_HIGH.inv())
        validityBits.value = newMetadata
    }

    fun isMarked() = nextRef.value.deleted
}

private fun <T> flushDelete(node: ListNode<T>) {
    if (node.deleteFlushFlag.value) return
    node.nextRef.flush()
    node.deleteFlushFlag.value = true
}

private fun <T> flushInsert(node: ListNode<T>) {
    if (node.insertFlushFlag.value) return
    node.nextRef.flush()
    node.validityBits.flush()
    node.insertFlushFlag.value = true
}

internal class DurableLinkFreeList<T> : DurableSet {
    private val head: NonVolatileRef<ListNode<T>>

    // non volatile allocation storage, stores nodes in any order
    private val nodeStorage = ConcurrentLinkedDeque<ListNode<T>>()

    init {
        val mx = ListNode(Int.MAX_VALUE, null as T?, null)
        head = nonVolatile(ListNode(Int.MAX_VALUE, null as T?, mx))
    }

    private fun allocateNode(key: Int, value: T?, next: ListNode<T>?): ListNode<T> {
        return ListNode(key, value, next, MASK_HIGH).also { nodeStorage.add(it) }
    }

    private fun trimNext(pred: ListNode<T>, curr: ListNode<T>): Boolean {
        val nextRef = pred.nextRef.value
        flushDelete(curr)
        val res = pred.nextRef.compareAndSet(nextRef, curr.nextRef.value)
        if (res) {
            nodeStorage.remove(curr)
        }
        return res
    }

    private fun find(key: Int): Pair<ListNode<T>, ListNode<T>> {
        var prev = head.value
        var curr = prev.nextRef.value.next
        while (true) {
            if (!curr!!.isMarked()) {
                if (curr.key >= key) break
                prev = curr
            } else {
                trimNext(prev, curr)
            }
            curr = curr.nextRef.value.next
        }
        return prev to curr!!
    }

    override fun add(key: Int): Boolean {
        while (true) {
            val (pred, curr) = find(key)
            if (curr.key == key) {
                curr.makeValid()
                flushInsert(curr)
                return false
            }
            val nextRef = pred.nextRef.value
            if (nextRef.next !== curr) continue
            val newNode = allocateNode(key, null, curr)
            if (pred.nextRef.compareAndSet(nextRef, NextRef(newNode, false))) {
                newNode.makeValid()
                flushInsert(newNode)
                return true
            }
        }
    }

    override fun remove(key: Int): Boolean {
        while (true) {
            val (pred, curr) = find(key)
            if (curr.key != key) return false
            val nextRef = curr.nextRef.value
            val deletedRef = NextRef(nextRef.next, true)
            curr.makeValid()
            if (curr.nextRef.compareAndSet(nextRef, deletedRef)) {
                trimNext(pred, curr)
                return true
            }
        }
    }

    override fun contains(key: Int): Boolean {
        var curr = head.value.nextRef.value.next!!
        while (curr.key < key) {
            curr = curr.nextRef.value.next!!
        }
        if (curr.key != key) return false
        if (curr.isMarked()) {
            flushDelete(curr)
            return false
        }
        curr.makeValid()
        flushInsert(curr)
        return true
    }

    override fun recover() = doRecover()

    private fun fastAdd(newNode: ListNode<T>) {
        val key = newNode.key
        retry@ while (true) {
            var prev = head.value
            var curr = prev.nextRef.value.next!!
            while (true) {
                val succ = curr.nextRef.value.next
                if (curr.key < key) {
                    prev = curr
                    curr = succ!!
                    assert(!succ.isMarked())
                    continue
                }
                assert(curr.key != key)
                val nextRef = prev.nextRef.value
                if (nextRef.next !== curr) continue
                newNode.nextRef.value = NextRef(curr, false)
                if (prev.nextRef.compareAndSet(nextRef, NextRef(newNode, false))) {
                    return
                } else {
                    continue@retry
                }
            }
        }
    }

    private fun doRecover() {
        val mx = ListNode(Int.MAX_VALUE, null as T?, null)
        head.value = ListNode(Int.MIN_VALUE, null as T?, mx)
        val toDelete = mutableListOf<ListNode<T>>()
        for (v in nodeStorage) {
            if (v.nextRef.value.next === null && v.isValid()) continue
            if (!v.isValid() || v.isMarked()) {
                v.makeValid()
                toDelete.add(v)
            } else {
                fastAdd(v)
            }
        }
        toDelete.forEach { nodeStorage.remove(it) }
    }
}

@StressCTest(
    sequentialSpecification = SequentialSet::class,
    threads = THREADS_NUMBER,
    recover = Recover.DURABLE,
    minimizeFailedScenario = false
)
internal class DurableLinkFreeListNoRecoverFailingTest {
    val s = DurableLinkFreeList<Any>()

    @Operation
    fun add(key: Int) = s.add(key)

    @Operation
    fun remove(key: Int) = s.remove(key)

    @Operation
    fun contains(key: Int) = s.contains(key)

    @Test(expected = LincheckAssertionError::class)
    fun test() = LinChecker.check(this::class.java)
}

@StressCTest(
    sequentialSpecification = SequentialSet::class,
    threads = THREADS_NUMBER,
    recover = Recover.DURABLE,
    minimizeFailedScenario = false
)
internal abstract class DurableLinkFreeListFailingTest {
    internal abstract val s: DurableSet

    @Operation
    fun add(key: Int) = s.add(key)

    @Operation
    fun remove(key: Int) = s.remove(key)

    @Operation
    fun contains(key: Int) = s.contains(key)

    @DurableRecoverAll
    fun recover() = s.recover()

    @Test(expected = LincheckAssertionError::class)
    fun test() = LinChecker.check(this::class.java)
}

internal class DurableLinkFreeListFailingTest1 : DurableLinkFreeListFailingTest() {
    override val s = DurableLinkFreeFailingList1<Int>()
}

internal class DurableLinkFreeListFailingTest2 : DurableLinkFreeListFailingTest() {
    override val s = DurableLinkFreeFailingList2<Int>()
}

internal class DurableLinkFreeFailingList1<T> : DurableSet {
    private val head: NonVolatileRef<ListNode<T>>

    // non volatile allocation storage, stores nodes in any order
    private val nodeStorage = ConcurrentLinkedDeque<ListNode<T>>()

    init {
        val mx = ListNode(Int.MAX_VALUE, null as T?, null)
        head = nonVolatile(ListNode(Int.MAX_VALUE, null as T?, mx))
    }

    private fun allocateNode(key: Int, value: T?, next: ListNode<T>?): ListNode<T> {
        return ListNode(key, value, next, MASK_HIGH).also { nodeStorage.add(it) }
    }

    private fun trimNext(pred: ListNode<T>, curr: ListNode<T>): Boolean {
        val nextRef = pred.nextRef.value
        flushDelete(curr)
        val res = pred.nextRef.compareAndSet(nextRef, curr.nextRef.value)
        if (res) {
            nodeStorage.remove(curr)
        }
        return res
    }

    private fun find(key: Int): Pair<ListNode<T>, ListNode<T>> {
        var prev = head.value
        var curr = prev.nextRef.value.next
        while (true) {
            if (!curr!!.isMarked()) {
                if (curr.key >= key) break
                prev = curr
            } else {
                trimNext(prev, curr)
            }
            curr = curr.nextRef.value.next
        }
        return prev to curr!!
    }

    override fun add(key: Int): Boolean {
        while (true) {
            val (pred, curr) = find(key)
            if (curr.key == key) {
                curr.makeValid()
                // here should be flushInsert(curr)
                return false
            }
            val nextRef = pred.nextRef.value
            if (nextRef.next !== curr) continue
            val newNode = allocateNode(key, null, curr)
            if (pred.nextRef.compareAndSet(nextRef, NextRef(newNode, false))) {
                newNode.makeValid()
                flushInsert(newNode)
                return true
            }
        }
    }

    override fun remove(key: Int): Boolean {
        while (true) {
            val (pred, curr) = find(key)
            if (curr.key != key) return false
            val nextRef = curr.nextRef.value
            val deletedRef = NextRef(nextRef.next, true)
            curr.makeValid()
            if (curr.nextRef.compareAndSet(nextRef, deletedRef)) {
                trimNext(pred, curr)
                return true
            }
        }
    }

    override fun contains(key: Int): Boolean {
        var curr = head.value.nextRef.value.next!!
        while (curr.key < key) {
            curr = curr.nextRef.value.next!!
        }
        if (curr.key != key) return false
        if (curr.isMarked()) {
            flushDelete(curr)
            return false
        }
        curr.makeValid()
        flushInsert(curr)
        return true
    }

    override fun recover() = doRecover()

    private fun fastAdd(newNode: ListNode<T>) {
        val key = newNode.key
        retry@ while (true) {
            var prev = head.value
            var curr = prev.nextRef.value.next!!
            while (true) {
                val succ = curr.nextRef.value.next
                if (curr.key < key) {
                    prev = curr
                    curr = succ!!
                    assert(!succ.isMarked())
                    continue
                }
                assert(curr.key != key)
                val nextRef = prev.nextRef.value
                if (nextRef.next !== curr) continue
                newNode.nextRef.value = NextRef(curr, false)
                if (prev.nextRef.compareAndSet(nextRef, NextRef(newNode, false))) {
                    return
                } else {
                    continue@retry
                }
            }
        }
    }

    private fun doRecover() {
        val mx = ListNode(Int.MAX_VALUE, null as T?, null)
        head.value = ListNode(Int.MIN_VALUE, null as T?, mx)
        val toDelete = mutableListOf<ListNode<T>>()
        for (v in nodeStorage) {
            if (v.nextRef.value.next === null && v.isValid()) continue
            if (!v.isValid() || v.isMarked()) {
                v.makeValid()
                toDelete.add(v)
            } else {
                fastAdd(v)
            }
        }
        toDelete.forEach { nodeStorage.remove(it) }
    }
}


internal class DurableLinkFreeFailingList2<T> : DurableSet {
    private val head: NonVolatileRef<ListNode<T>>

    // non volatile allocation storage, stores nodes in any order
    private val nodeStorage = ConcurrentLinkedDeque<ListNode<T>>()

    init {
        val mx = ListNode(Int.MAX_VALUE, null as T?, null)
        head = nonVolatile(ListNode(Int.MAX_VALUE, null as T?, mx))
    }

    private fun allocateNode(key: Int, value: T?, next: ListNode<T>?): ListNode<T> {
        return ListNode(key, value, next, MASK_HIGH).also { nodeStorage.add(it) }
    }

    private fun trimNext(pred: ListNode<T>, curr: ListNode<T>): Boolean {
        val nextRef = pred.nextRef.value
        flushDelete(curr)
        val res = pred.nextRef.compareAndSet(nextRef, curr.nextRef.value)
        if (res) {
            nodeStorage.remove(curr)
        }
        return res
    }

    private fun find(key: Int): Pair<ListNode<T>, ListNode<T>> {
        var prev = head.value
        var curr = prev.nextRef.value.next
        while (true) {
            if (!curr!!.isMarked()) {
                if (curr.key >= key) break
                prev = curr
            } else {
                trimNext(prev, curr)
            }
            curr = curr.nextRef.value.next
        }
        return prev to curr!!
    }

    override fun add(key: Int): Boolean {
        while (true) {
            val (pred, curr) = find(key)
            if (curr.key == key) {
                curr.makeValid()
                flushInsert(curr)
                return false
            }
            val nextRef = pred.nextRef.value
            if (nextRef.next !== curr) continue
            val newNode = allocateNode(key, null, curr)
            if (pred.nextRef.compareAndSet(nextRef, NextRef(newNode, false))) {
                newNode.makeValid()
                // here should be flushInsert(newNode)
                return true
            }
        }
    }

    override fun remove(key: Int): Boolean {
        while (true) {
            val (pred, curr) = find(key)
            if (curr.key != key) return false
            val nextRef = curr.nextRef.value
            val deletedRef = NextRef(nextRef.next, true)
            curr.makeValid()
            if (curr.nextRef.compareAndSet(nextRef, deletedRef)) {
                trimNext(pred, curr)
                return true
            }
        }
    }

    override fun contains(key: Int): Boolean {
        var curr = head.value.nextRef.value.next!!
        while (curr.key < key) {
            curr = curr.nextRef.value.next!!
        }
        if (curr.key != key) return false
        if (curr.isMarked()) {
            flushDelete(curr)
            return false
        }
        curr.makeValid()
        flushInsert(curr)
        return true
    }

    override fun recover() = doRecover()

    private fun fastAdd(newNode: ListNode<T>) {
        val key = newNode.key
        retry@ while (true) {
            var prev = head.value
            var curr = prev.nextRef.value.next!!
            while (true) {
                val succ = curr.nextRef.value.next
                if (curr.key < key) {
                    prev = curr
                    curr = succ!!
                    assert(!succ.isMarked())
                    continue
                }
                assert(curr.key != key)
                val nextRef = prev.nextRef.value
                if (nextRef.next !== curr) continue
                newNode.nextRef.value = NextRef(curr, false)
                if (prev.nextRef.compareAndSet(nextRef, NextRef(newNode, false))) {
                    return
                } else {
                    continue@retry
                }
            }
        }
    }

    private fun doRecover() {
        val mx = ListNode(Int.MAX_VALUE, null as T?, null)
        head.value = ListNode(Int.MIN_VALUE, null as T?, mx)
        val toDelete = mutableListOf<ListNode<T>>()
        for (v in nodeStorage) {
            if (v.nextRef.value.next === null && v.isValid()) continue
            if (!v.isValid() || v.isMarked()) {
                v.makeValid()
                toDelete.add(v)
            } else {
                fastAdd(v)
            }
        }
        toDelete.forEach { nodeStorage.remove(it) }
    }
}
