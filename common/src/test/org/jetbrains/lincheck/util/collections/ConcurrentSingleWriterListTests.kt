/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util.collections

import org.jetbrains.lincheck.util.collections.ConcurrentSingleWriterList.ThreadSafetyMode
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicReference

class ConcurrentSingleWriterListTests {

    // ==================== size / isEmpty ====================

    @Test
    fun testEmptyList() {
        val list = ConcurrentSingleWriterList<String>()
        assertEquals(0, list.size)
        assertTrue(list.isEmpty())
    }

    @Test
    fun testSizeAfterAdd() {
        val list = ConcurrentSingleWriterList<String>()
        list.add("a")
        list.add("b")
        assertEquals(2, list.size)
        assertFalse(list.isEmpty())
    }

    // ==================== get ====================

    @Test
    fun testGet() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "c"))
        assertEquals("a", list[0])
        assertEquals("b", list[1])
        assertEquals("c", list[2])
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun testGetOutOfBounds() {
        val list = ConcurrentSingleWriterList<String>()
        list.add("a")
        list[1]
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun testGetNegativeIndex() {
        val list = ConcurrentSingleWriterList<String>()
        list.add("a")
        list[-1]
    }

    // ==================== set ====================

    @Test
    fun testSet() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "c"))
        val old = list.set(1, "x")
        assertEquals("b", old)
        assertEquals("x", list[1])
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun testSetOutOfBounds() {
        val list = ConcurrentSingleWriterList<String>()
        list.add("a")
        list.set(1, "x")
    }

    // ==================== add ====================

    @Test
    fun testAdd() {
        val list = ConcurrentSingleWriterList<String>()
        assertTrue(list.add("a"))
        assertTrue(list.add("b"))
        assertEquals(listOf("a", "b"), list.toList())
    }

    @Test
    fun testAddAtIndex() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "c"))
        list.add(1, "b")
        assertEquals(listOf("a", "b", "c"), list.toList())
    }

    @Test
    fun testAddAtBeginning() {
        val list = ConcurrentSingleWriterList<String>()
        list.add("b")
        list.add(0, "a")
        assertEquals(listOf("a", "b"), list.toList())
    }

    @Test
    fun testAddAtEnd() {
        val list = ConcurrentSingleWriterList<String>()
        list.add("a")
        list.add(1, "b")
        assertEquals(listOf("a", "b"), list.toList())
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun testAddAtInvalidIndex() {
        val list = ConcurrentSingleWriterList<String>()
        list.add("a")
        list.add(3, "x")
    }

    // ==================== addAll ====================

    @Test
    fun testAddAll() {
        val list = ConcurrentSingleWriterList<String>()
        list.add("a")
        assertTrue(list.addAll(listOf("b", "c")))
        assertEquals(listOf("a", "b", "c"), list.toList())
    }

    @Test
    fun testAddAllEmpty() {
        val list = ConcurrentSingleWriterList<String>()
        assertFalse(list.addAll(emptyList()))
        assertEquals(0, list.size)
    }

    @Test
    fun testAddAllAtIndex() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "d"))
        assertTrue(list.addAll(1, listOf("b", "c")))
        assertEquals(listOf("a", "b", "c", "d"), list.toList())
    }

    @Test
    fun testAddAllAtIndexEmpty() {
        val list = ConcurrentSingleWriterList<String>()
        list.add("a")
        assertFalse(list.addAll(0, emptyList()))
        assertEquals(1, list.size)
    }

    // ==================== contains ====================

    @Test
    fun testContains() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "c"))
        assertTrue(list.contains("b"))
        assertFalse(list.contains("x"))
    }

    @Test
    fun testContainsAll() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "c"))
        assertTrue(list.containsAll(listOf("a", "c")))
        assertFalse(list.containsAll(listOf("a", "x")))
    }

    // ==================== indexOf / lastIndexOf ====================

    @Test
    fun testIndexOf() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "c"))
        assertEquals(1, list.indexOf("b"))
        assertEquals(-1, list.indexOf("x"))
    }

    @Test
    fun testLastIndexOf() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "a", "c"))
        assertEquals(2, list.lastIndexOf("a"))
        assertEquals(-1, list.lastIndexOf("x"))
    }

    // ==================== remove ====================

    @Test
    fun testRemove() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "c"))
        assertTrue(list.remove("b"))
        assertEquals(listOf("a", "c"), list.toList())
    }

    @Test
    fun testRemoveNotFound() {
        val list = ConcurrentSingleWriterList<String>()
        list.add("a")
        assertFalse(list.remove("x"))
        assertEquals(1, list.size)
    }

    @Test
    fun testRemoveAt() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "c"))
        val removed = list.removeAt(1)
        assertEquals("b", removed)
        assertEquals(listOf("a", "c"), list.toList())
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun testRemoveAtOutOfBounds() {
        val list = ConcurrentSingleWriterList<String>()
        list.add("a")
        list.removeAt(1)
    }

    // ==================== removeAll ====================

    @Test
    fun testRemoveAll() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "c", "d"))
        assertTrue(list.removeAll(listOf("b", "d")))
        assertEquals(listOf("a", "c"), list.toList())
    }

    @Test
    fun testRemoveAllNoneMatch() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b"))
        assertFalse(list.removeAll(listOf("x", "y")))
        assertEquals(2, list.size)
    }

    // ==================== retainAll ====================

    @Test
    fun testRetainAll() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "c", "d"))
        assertTrue(list.retainAll(listOf("b", "c")))
        assertEquals(listOf("b", "c"), list.toList())
    }

    @Test
    fun testRetainAllNoChange() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b"))
        assertFalse(list.retainAll(listOf("a", "b")))
        assertEquals(2, list.size)
    }

    // ==================== clear ====================

    @Test
    fun testClear() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "c"))
        list.clear()
        assertEquals(0, list.size)
        assertTrue(list.isEmpty())
    }

    // ==================== capacity / growth ====================

    @Test
    fun testGrowBeyondInitialCapacity() {
        val list = ConcurrentSingleWriterList<Int>(initialCapacity = 2)
        for (i in 0 until 20) {
            list.add(i)
        }
        assertEquals(20, list.size)
        for (i in 0 until 20) {
            assertEquals(i, list[i])
        }
    }

    @Test
    fun testCustomInitialCapacity() {
        val list = ConcurrentSingleWriterList<String>(initialCapacity = 100)
        assertEquals(100, list.capacity)
        assertEquals(0, list.size)
    }

    // ==================== iterator ====================

    @Test
    fun testIterator() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), list.toList())
    }

    @Test
    fun testListIteratorForward() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "c"))
        val iter = list.listIterator()
        assertTrue(iter.hasNext())
        assertFalse(iter.hasPrevious())
        assertEquals("a", iter.next())
        assertEquals("b", iter.next())
        assertEquals("c", iter.next())
        assertFalse(iter.hasNext())
    }

    @Test
    fun testListIteratorBackward() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "c"))
        val iter = list.listIterator(3)
        assertEquals("c", iter.previous())
        assertEquals("b", iter.previous())
        assertEquals("a", iter.previous())
        assertFalse(iter.hasPrevious())
    }

    @Test
    fun testListIteratorFromIndex() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "c"))
        val iter = list.listIterator(1)
        assertEquals(1, iter.nextIndex())
        assertEquals(0, iter.previousIndex())
        assertEquals("b", iter.next())
    }

    @Test
    fun testIteratorAdd() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "c"))
        val iter = list.listIterator()
        iter.next() // a
        iter.add("b")
        assertEquals(listOf("a", "b", "c"), list.toList())
    }

    @Test
    fun testIteratorRemove() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "c"))
        val iter = list.iterator()
        iter.next() // a
        iter.next() // b
        iter.remove()
        assertEquals(listOf("a", "c"), list.toList())
    }

    @Test
    fun testIteratorSet() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "c"))
        val iter = list.listIterator()
        iter.next() // a
        iter.set("x")
        assertEquals("x", list[0])
    }

    @Test
    fun testEmptyIterator() {
        val list = ConcurrentSingleWriterList<String>()
        val iter = list.listIterator()
        assertFalse(iter.hasNext())
        assertFalse(iter.hasPrevious())
    }

    // ==================== subList ====================

    @Test
    fun testSubList() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "c", "d", "e"))
        val sub = list.subList(1, 4) // [b, c, d]
        assertEquals(3, sub.size)
        assertEquals("b", sub[0])
        assertEquals("d", sub[2])
    }

    @Test
    fun testSubListMutation() {
        val list = ConcurrentSingleWriterList<String>()
        list.addAll(listOf("a", "b", "c", "d", "e"))
        val sub = list.subList(1, 4)
        sub.set(0, "x")
        assertEquals("x", list[1])
    }

    // ==================== ThreadSafetyMode.NONE ====================

    @Test
    fun testNoneModeBasicOperations() {
        val list = ConcurrentSingleWriterList<String>(mode = ThreadSafetyMode.NONE)
        list.addAll(listOf("a", "b", "c"))
        assertEquals(3, list.size)
        assertEquals("b", list[1])
        list.set(1, "x")
        assertEquals("x", list[1])
        list.removeAt(0)
        assertEquals(listOf("x", "c"), list.toList())
    }

    // ==================== equivalence with ArrayList ====================

    @Test
    fun testMatchesArrayListBehavior() {
        val cswl = ConcurrentSingleWriterList<Int>()
        val reference = ArrayList<Int>()

        // add
        for (i in 0 until 10) {
            cswl.add(i)
            reference.add(i)
        }
        assertEquals(reference, cswl.toList())

        // set
        cswl.set(3, 99)
        reference.set(3, 99)
        assertEquals(reference, cswl.toList())

        // add at index
        cswl.add(5, 42)
        reference.add(5, 42)
        assertEquals(reference, cswl.toList())

        // remove
        cswl.remove(99)
        reference.remove(99)
        assertEquals(reference, cswl.toList())

        // removeAt
        cswl.removeAt(0)
        reference.removeAt(0)
        assertEquals(reference, cswl.toList())

        // addAll
        cswl.addAll(listOf(100, 200))
        reference.addAll(listOf(100, 200))
        assertEquals(reference, cswl.toList())

        // retainAll
        cswl.retainAll(listOf(1, 3, 5, 42, 100, 200))
        reference.retainAll(listOf(1, 3, 5, 42, 100, 200))
        assertEquals(reference, cswl.toList())

        // clear
        cswl.clear()
        reference.clear()
        assertEquals(reference, cswl.toList())
        assertTrue(cswl.isEmpty())
    }
}

class ConcurrentSingleWriterListStressTests {

    @Test
    fun testConcurrentReadWriteNoneThreadSafetyMode() {
        // In NONE mode, only one thread writes, but readers may run concurrently.
        val list = ConcurrentSingleWriterList<Int>(mode = ThreadSafetyMode.NONE)
        val nReaders = 4
        val nWriteIterations = 1000
        val nReadIterations = 1000
        val nThreads = nReaders + 1
        val barrier = CyclicBarrier(nThreads)
        val error = AtomicReference<Throwable?>(null)

        val writer = Thread {
            try {
                barrier.await()
                for (i in 0 until nWriteIterations) {
                    if (i % 2 == 0) {
                            list.add(i)
                    }
                    if (i % 3 == 0 && list.isNotEmpty()) {
                        try {
                            list.removeAt(0)
                        } catch (_: IndexOutOfBoundsException) {
                            // acceptable under concurrent modification
                        }
                    }
                    if (i % 5 == 0 && list.isNotEmpty()) {
                        try {
                            list.set(list.size - 1, i * 10)
                        } catch (_: IndexOutOfBoundsException) {
                            // acceptable under concurrent modification
                        }
                    }
                }
            } catch (e: Throwable) {
                error.compareAndSet(null, e)
            }
        }

        val readers = (0 until nReaders).map {
            Thread {
                try {
                    val containsResults = BooleanArray(nReadIterations)
                    val indexOfResults = IntArray(nReadIterations)
                    val getResults = IntArray(nReadIterations)
                    val isEmptyResults = BooleanArray(nReadIterations)

                    barrier.await()
                    for (i in 0 until nReadIterations) {
                        // read operations should not throw ConcurrentModificationException

                        val size = list.size
                        if (size > 0) {
                            containsResults[i] = list.contains(i)
                            indexOfResults[i] = list.indexOf(i)
                            try {
                                getResults[i] = list[0]
                            } catch (_: IndexOutOfBoundsException) {
                                // acceptable under concurrent modification
                            }
                        }
                        isEmptyResults[i] = list.isEmpty()
                    }
                } catch (e: Throwable) {
                    error.compareAndSet(null, e)
                }
            }
        }

        writer.start()
        readers.forEach { it.start() }

        writer.join()
        readers.forEach { it.join() }

        val failure = error.get()
        if (failure != null) throw failure

        assertTrue(list.size >= 0)
        assertEquals(list.size, list.toList().size)
    }

    @Test
    fun testConcurrentReadWriteSynchronizedThreadSafetyMode() {
        val list = ConcurrentSingleWriterList<Int>(mode = ThreadSafetyMode.SYNCHRONIZED)
        val nWriters = 2
        val nReaders = 4
        val nWriteIterations = 1000
        val nReadIterations = 1000
        val nThreads = nWriters + nReaders
        val barrier = CyclicBarrier(nThreads)
        val error = AtomicReference<Throwable?>(null)

        val writers = (0 until nWriters).map {
            Thread {
                try {
                    barrier.await()
                    for (i in 0 until nWriteIterations) {
                        // write operations should not throw ConcurrentModificationException

                        if (i % 2 == 0) {
                            list.add(i)
                        }
                        if (i % 3 == 0 && list.isNotEmpty()) {
                            try {
                                list.removeAt(0)
                            } catch (_: IndexOutOfBoundsException) {
                                // acceptable under concurrent modification
                            }
                        }
                        if (i % 5 == 0 && list.isNotEmpty()) {
                            try {
                                list.set(list.size - 1, i * 10)
                            } catch (_: IndexOutOfBoundsException) {
                                // acceptable under concurrent modification
                            }
                        }
                    }
                } catch (e: Throwable) {
                    error.compareAndSet(null, e)
                }
            }
        }

        val readers = (0 until nReaders).map {
            Thread {
                try {
                    val containsResults = BooleanArray(nReadIterations)
                    val indexOfResults = IntArray(nReadIterations)
                    val getResults = IntArray(nReadIterations)
                    val isEmptyResults = BooleanArray(nReadIterations)

                    barrier.await()
                    for (i in 0 until nReadIterations) {
                        // read operations should not throw ConcurrentModificationException

                        val size = list.size
                        if (size > 0) {
                            containsResults[i] = list.contains(i)
                            indexOfResults[i] = list.indexOf(i)
                            try {
                                getResults[i] = list[0]
                            } catch (_: IndexOutOfBoundsException) {
                                // acceptable under concurrent modification
                            }
                        }
                        isEmptyResults[i] = list.isEmpty()
                    }
                } catch (e: Throwable) {
                    error.compareAndSet(null, e)
                }
            }
        }

        writers.forEach { it.start() }
        readers.forEach { it.start() }

        writers.forEach { it.join() }
        readers.forEach { it.join() }

        val failure = error.get()
        if (failure != null) throw failure

        // basic sanity checks after all threads complete
        assertTrue(list.size >= 0)
        assertEquals(list.size, list.toList().size)
    }
}