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

import org.junit.Assert.*
import org.junit.Test

class SubListTests {

    // ==================== construction ====================

    @Test(expected = IllegalArgumentException::class)
    fun testNegativeFrom() {
        sublist(-1, 3, listOf("a", "b", "c", "d", "e"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testToExceedsSize() {
        sublist(0, 6, listOf("a", "b", "c", "d", "e"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFromGreaterThanTo() {
        sublist(3, 1, listOf("a", "b", "c", "d", "e"))
    }

    // ==================== size / isEmpty ====================

    @Test
    fun testSize() {
        assertEquals(3, sublist(1, 4, listOf("a", "b", "c", "d", "e")).size)
    }

    @Test
    fun testEmptySubList() {
        val sub = sublist(2, 2, listOf("a", "b", "c", "d", "e"))
        assertEquals(0, sub.size)
        assertTrue(sub.isEmpty())
    }

    @Test
    fun testNonEmpty() {
        assertFalse(sublist(1, 4, listOf("a", "b", "c", "d", "e")).isEmpty())
    }

    // ==================== get ====================

    @Test
    fun testGet() {
        val sub = sublist(1, 4, listOf("a", "b", "c", "d", "e")) // [b, c, d]
        assertEquals("b", sub[0])
        assertEquals("c", sub[1])
        assertEquals("d", sub[2])
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun testGetOutOfBounds() {
        sublist(1, 4, listOf("a", "b", "c", "d", "e"))[3]
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun testGetNegativeIndex() {
        sublist(1, 4, listOf("a", "b", "c", "d", "e"))[-1]
    }

    // ==================== contains ====================

    @Test
    fun testContains() {
        val sub = sublist(1, 4, listOf("a", "b", "c", "d", "e")) // [b, c, d]
        assertTrue(sub.contains("b"))
        assertTrue(sub.contains("d"))
        assertFalse(sub.contains("a"))
        assertFalse(sub.contains("e"))
    }

    @Test
    fun testContainsAll() {
        val sub = sublist(1, 4, listOf("a", "b", "c", "d", "e"))
        assertTrue(sub.containsAll(listOf("b", "c")))
        assertFalse(sub.containsAll(listOf("b", "a")))
    }

    // ==================== indexOf / lastIndexOf ====================

    @Test
    fun testIndexOf() {
        val sub = sublist(1, 4, listOf("a", "b", "c", "d", "e")) // [b, c, d]
        assertEquals(0, sub.indexOf("b"))
        assertEquals(2, sub.indexOf("d"))
        assertEquals(-1, sub.indexOf("a"))
        assertEquals(-1, sub.indexOf("e"))
    }

    @Test
    fun testLastIndexOf() {
        val sub = sublist(0, 4, listOf("a", "b", "c", "b", "d")) // [a, b, c, b]
        assertEquals(3, sub.lastIndexOf("b"))
        assertEquals(0, sub.lastIndexOf("a"))
        assertEquals(-1, sub.lastIndexOf("d"))
    }

    // ==================== iterator ====================

    @Test
    fun testIterator() {
        val sub = sublist(1, 4, listOf("a", "b", "c", "d", "e")) // [b, c, d]
        assertEquals(listOf("b", "c", "d"), sub.toList())
    }

    @Test
    fun testListIteratorForward() {
        val sub = sublist(1, 4, listOf("a", "b", "c", "d", "e"))
        val iter = sub.listIterator()
        assertTrue(iter.hasNext())
        assertFalse(iter.hasPrevious())
        assertEquals(0, iter.nextIndex())
        assertEquals("b", iter.next())
        assertEquals("c", iter.next())
        assertEquals("d", iter.next())
        assertFalse(iter.hasNext())
    }

    @Test
    fun testListIteratorBackward() {
        val sub = sublist(1, 4, listOf("a", "b", "c", "d", "e"))
        val iter = sub.listIterator(3)
        assertTrue(iter.hasPrevious())
        assertFalse(iter.hasNext())
        assertEquals("d", iter.previous())
        assertEquals("c", iter.previous())
        assertEquals("b", iter.previous())
        assertFalse(iter.hasPrevious())
    }

    @Test
    fun testListIteratorFromIndex() {
        val sub = sublist(1, 4, listOf("a", "b", "c", "d", "e"))
        val iter = sub.listIterator(1)
        assertEquals(1, iter.nextIndex())
        assertEquals(0, iter.previousIndex())
        assertEquals("c", iter.next())
    }

    // ==================== nested subList ====================

    @Test
    fun testNestedSubList() {
        val sub = sublist(1, 4, listOf("a", "b", "c", "d", "e")) // [b, c, d]
        val nested = sub.subList(1, 3) // [c, d]
        assertEquals(2, nested.size)
        assertEquals("c", nested[0])
        assertEquals("d", nested[1])
    }

    @Test
    fun testNestedSubListEmpty() {
        val sub = sublist(1, 4, listOf("a", "b", "c", "d", "e"))
        val nested = sub.subList(1, 1)
        assertTrue(nested.isEmpty())
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun testNestedSubListInvalidBounds() {
        sublist(1, 4, listOf("a", "b", "c", "d", "e")).subList(0, 4)
    }

    // ==================== edge cases ====================

    @Test
    fun testFullRange() {
        val list = listOf("a", "b", "c", "d", "e")
        val sub = sublist(0, 5, list)
        assertEquals(list.size, sub.size)
        assertEquals(list, sub.toList())
    }

    @Test
    fun testSingleElement() {
        val sub = sublist(2, 3, listOf("a", "b", "c", "d", "e")) // [c]
        assertEquals(1, sub.size)
        assertEquals("c", sub[0])
    }

    @Test
    fun testEmptyContains() {
        val sub = sublist(2, 2, listOf("a", "b", "c", "d", "e"))
        assertFalse(sub.contains("c"))
        assertEquals(-1, sub.indexOf("c"))
        assertEquals(-1, sub.lastIndexOf("c"))
    }

    @Test
    fun testEmptyIterator() {
        val sub = sublist(2, 2, listOf("a", "b", "c", "d", "e"))
        val iter = sub.listIterator()
        assertFalse(iter.hasNext())
        assertFalse(iter.hasPrevious())
    }
}

class MutableSubListTests {

    // ==================== construction ====================

    @Test(expected = IllegalArgumentException::class)
    fun testNegativeFrom() {
        mutableSublist(-1, 3, mutableListOf("a", "b", "c", "d", "e"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testToExceedsSize() {
        mutableSublist(0, 6, mutableListOf("a", "b", "c", "d", "e"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testFromGreaterThanTo() {
        mutableSublist(3, 1, mutableListOf("a", "b", "c", "d", "e"))
    }

    // ==================== set ====================

    @Test
    fun testSet() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(1, 4, list) // [b, c, d]
        val old = sub.set(1, "x")
        assertEquals("c", old)
        assertEquals("x", sub[1])
        assertEquals("x", list[2])
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun testSetOutOfBounds() {
        mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e")).set(3, "x")
    }

    // ==================== add ====================

    @Test
    fun testAdd() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(1, 4, list) // [b, c, d]
        assertTrue(sub.add("x"))
        assertEquals(4, sub.size)
        assertEquals(listOf("b", "c", "d", "x"), sub.toList())
        assertEquals(listOf("a", "b", "c", "d", "x", "e"), list)
    }

    @Test
    fun testAddAtIndex() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(1, 4, list) // [b, c, d]
        sub.add(1, "x")
        assertEquals(4, sub.size)
        assertEquals(listOf("b", "x", "c", "d"), sub.toList())
        assertEquals(listOf("a", "b", "x", "c", "d", "e"), list)
    }

    @Test
    fun testAddAtBeginning() {
        val sub = mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e"))
        sub.add(0, "x")
        assertEquals("x", sub[0])
        assertEquals("b", sub[1])
    }

    @Test
    fun testAddAtEnd() {
        val sub = mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e"))
        sub.add(3, "x")
        assertEquals("x", sub[3])
        assertEquals(4, sub.size)
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun testAddAtInvalidIndex() {
        mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e")).add(4, "x")
    }

    // ==================== addAll ====================

    @Test
    fun testAddAll() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(1, 4, list) // [b, c, d]
        assertTrue(sub.addAll(listOf("x", "y")))
        assertEquals(5, sub.size)
        assertEquals(listOf("b", "c", "d", "x", "y"), sub.toList())
        assertEquals(7, list.size)
    }

    @Test
    fun testAddAllEmpty() {
        val sub = mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e"))
        assertFalse(sub.addAll(emptyList()))
        assertEquals(3, sub.size)
    }

    @Test
    fun testAddAllAtIndex() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(1, 4, list) // [b, c, d]
        assertTrue(sub.addAll(1, listOf("x", "y")))
        assertEquals(5, sub.size)
        assertEquals(listOf("b", "x", "y", "c", "d"), sub.toList())
        assertEquals(7, list.size)
    }

    @Test
    fun testAddAllAtIndexEmpty() {
        val sub = mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e"))
        assertFalse(sub.addAll(1, emptyList()))
        assertEquals(3, sub.size)
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun testAddAllAtInvalidIndex() {
        mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e")).addAll(4, listOf("x"))
    }

    // ==================== remove ====================

    @Test
    fun testRemove() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(1, 4, list) // [b, c, d]
        assertTrue(sub.remove("c"))
        assertEquals(2, sub.size)
        assertEquals(listOf("b", "d"), sub.toList())
        assertEquals(listOf("a", "b", "d", "e"), list)
    }

    @Test
    fun testRemoveNotFound() {
        val sub = mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e"))
        assertFalse(sub.remove("z"))
        assertEquals(3, sub.size)
    }

    @Test
    fun testRemoveOutsideRange() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(1, 4, list) // [b, c, d]
        assertFalse(sub.remove("a"))
        assertEquals(3, sub.size)
        assertEquals(5, list.size)
    }

    @Test
    fun testRemoveAt() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(1, 4, list) // [b, c, d]
        val removed = sub.removeAt(1)
        assertEquals("c", removed)
        assertEquals(2, sub.size)
        assertEquals(listOf("b", "d"), sub.toList())
        assertEquals(4, list.size)
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun testRemoveAtOutOfBounds() {
        mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e")).removeAt(3)
    }

    // ==================== removeAll ====================

    @Test
    fun testRemoveAll() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(1, 4, list) // [b, c, d]
        assertTrue(sub.removeAll(listOf("b", "d")))
        assertEquals(1, sub.size)
        assertEquals(listOf("c"), sub.toList())
        assertEquals(3, list.size)
    }

    @Test
    fun testRemoveAllNoneMatch() {
        val sub = mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e"))
        assertFalse(sub.removeAll(listOf("x", "y")))
        assertEquals(3, sub.size)
    }

    // ==================== retainAll ====================

    @Test
    fun testRetainAll() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(1, 4, list) // [b, c, d]
        assertTrue(sub.retainAll(listOf("b", "d")))
        assertEquals(2, sub.size)
        assertEquals(listOf("b", "d"), sub.toList())
        assertEquals(4, list.size)
    }

    @Test
    fun testRetainAllNoChange() {
        val sub = mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e"))
        assertFalse(sub.retainAll(listOf("b", "c", "d")))
        assertEquals(3, sub.size)
    }

    @Test
    fun testRetainAllEmpty() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(1, 4, list) // [b, c, d]
        assertTrue(sub.retainAll(emptyList()))
        assertEquals(0, sub.size)
        assertEquals(listOf("a", "e"), list)
    }

    // ==================== clear ====================

    @Test
    fun testClear() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(1, 4, list) // [b, c, d]
        sub.clear()
        assertEquals(0, sub.size)
        assertTrue(sub.isEmpty())
        assertEquals(listOf("a", "e"), list)
    }

    // ==================== iterator ====================

    @Test
    fun testIterator() {
        val sub = mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e"))
        assertEquals(listOf("b", "c", "d"), sub.toList())
    }

    @Test
    fun testListIteratorForward() {
        val sub = mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e"))
        val iter = sub.listIterator()
        assertTrue(iter.hasNext())
        assertFalse(iter.hasPrevious())
        assertEquals(0, iter.nextIndex())
        assertEquals("b", iter.next())
        assertEquals("c", iter.next())
        assertEquals("d", iter.next())
        assertFalse(iter.hasNext())
    }

    @Test
    fun testListIteratorBackward() {
        val sub = mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e"))
        val iter = sub.listIterator(3)
        assertTrue(iter.hasPrevious())
        assertFalse(iter.hasNext())
        assertEquals("d", iter.previous())
        assertEquals("c", iter.previous())
        assertEquals("b", iter.previous())
        assertFalse(iter.hasPrevious())
    }

    @Test
    fun testIteratorRemove() {
        val sub = mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e")) // [b, c, d]
        val iter = sub.iterator()
        iter.next() // b
        iter.next() // c
        iter.remove()
        assertEquals(2, sub.size)
        assertEquals(listOf("b", "d"), sub.toList())
    }

    @Test
    fun testListIteratorSet() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(1, 4, list) // [b, c, d]
        val iter = sub.listIterator()
        iter.next() // b
        iter.set("x")
        assertEquals("x", sub[0])
        assertEquals("x", list[1])
    }

    @Test
    fun testListIteratorAdd() {
        val sub = mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e")) // [b, c, d]
        val iter = sub.listIterator()
        iter.next() // b
        iter.add("x") // insert after b
        assertEquals(4, sub.size)
        assertEquals("x", sub[1])
        assertEquals("c", sub[2])
    }

    @Test(expected = IllegalStateException::class)
    fun testIteratorRemoveWithoutNext() {
        mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e")).listIterator().remove()
    }

    @Test(expected = IllegalStateException::class)
    fun testIteratorSetWithoutNext() {
        mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e")).listIterator().set("x")
    }

    // ==================== nested subList ====================

    @Test
    fun testNestedSubList() {
        val sub = mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e")) // [b, c, d]
        val nested = sub.subList(1, 3) // [c, d]
        assertEquals(2, nested.size)
        assertEquals("c", nested[0])
        assertEquals("d", nested[1])
    }

    @Test
    fun testNestedSubListModification() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(1, 4, list) // [b, c, d]
        val nested = sub.subList(1, 3) // [c, d]
        nested.set(0, "x")
        assertEquals("x", nested[0])
        assertEquals("x", sub[1])
        assertEquals("x", list[2])
    }

    @Test(expected = IndexOutOfBoundsException::class)
    fun testNestedSubListInvalidBounds() {
        mutableSublist(1, 4, mutableListOf("a", "b", "c", "d", "e")).subList(0, 4)
    }

    // ==================== edge cases ====================

    @Test
    fun testFullRange() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(0, 5, list)
        assertEquals(list.size, sub.size)
        assertEquals(list.toList(), sub.toList())
    }

    @Test
    fun testSingleElement() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(2, 3, list) // [c]
        assertEquals(1, sub.size)
        assertEquals("c", sub[0])
        sub.set(0, "x")
        assertEquals("x", list[2])
    }

    @Test
    fun testEmptySubListAdd() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(2, 2, list) // empty, between "b" and "c"
        sub.add("x")
        assertEquals(1, sub.size)
        assertEquals("x", sub[0])
        assertEquals(listOf("a", "b", "x", "c", "d", "e"), list)
    }

    @Test
    fun testBackingListReflectsSubListChanges() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(1, 4, list) // [b, c, d]
        sub.add("x")
        sub.removeAt(0) // remove "b"
        // sub is now [c, d, x]
        assertEquals(listOf("a", "c", "d", "x", "e"), list)
    }

    @Test
    fun testMultipleMutations() {
        val list = mutableListOf("a", "b", "c", "d", "e")
        val sub = mutableSublist(1, 4, list) // [b, c, d]
        sub.set(0, "B")
        sub.add(1, "x")
        sub.removeAt(3) // remove "d"
        assertEquals(listOf("B", "x", "c"), sub.toList())
        assertEquals(listOf("a", "B", "x", "c", "e"), list)
    }
}
