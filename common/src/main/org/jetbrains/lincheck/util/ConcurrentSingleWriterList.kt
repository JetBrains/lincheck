/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util

import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.contains
import kotlin.concurrent.withLock
import kotlin.math.min


@Suppress("UNCHECKED_CAST")
class ConcurrentSingleWriterList<T>(initialCapacity: Int = DEFAULT_CAPACITY) : MutableList<T> {

    @Volatile
    private var array: AtomicReferenceArray<Any /* T | TOMBSTONE */> =
        AtomicReferenceArray(Array(initialCapacity) { TOMBSTONE })

    @Volatile
    private var _size = 0

    override val size: Int
        get() = _size

    val capacity: Int
        get() = array.length()

    private val writeLock = ReentrantLock()

    override fun isEmpty(): Boolean =
        (size == 0)

    override fun contains(element: T): Boolean {
        val snapshot = array
        val size = min(this.size, snapshot.length())
        for (i in 0 until size) {
            if (snapshot.get(i) == element) return true
        }
        return false
    }

    override fun containsAll(elements: Collection<T>): Boolean {
        return elements.all { contains(it) }
    }

    override fun indexOf(element: T): Int {
        val snapshot = array
        val size = min(this.size, snapshot.length())
        for (i in 0 until size) {
            if (snapshot.get(i) == element) return i
        }
        return -1
    }

    override fun lastIndexOf(element: T): Int {
        val snapshot = array
        val size = min(this.size, snapshot.length())
        for (i in size - 1 downTo 0) {
            if (snapshot.get(i) == element) return i
        }
        return -1
    }

    override fun get(index: Int): T {
        val snapshot = array
        val size = min(this.size, snapshot.length())
        if (index !in 0 ..< size) {
            throw IndexOutOfBoundsException(index, snapshot)
        }

        val element = snapshot.get(index)
        if (element === TOMBSTONE) {
            throw IndexOutOfBoundsException(index, snapshot)
        }

        return element as T
    }

    override fun set(index: Int, element: T): T = writeLock.withLock {
        // We ese `snapshot` here just for convenience to get a reference to array
        // (and also to avoid multiple volatile reads).
        // Since there should be no concurrent updates,
        // the array should remain the same for the duration of the whole function.
        val snapshot = array

        // The same applies to the size field.
        // As there are no concurrent updates,
        // the size should remain the same for the duration of the whole function.
        if (index !in 0 ..< size) {
            throw IndexOutOfBoundsException(index, snapshot)
        }

        val prev = snapshot.get(index)
            // Should not be a tombstone, as we checked the size before.
            .ensure { it !== TOMBSTONE }

        snapshot.set(index, element)
        return (prev as T)
    }

    override fun add(element: T): Boolean = writeLock.withLock {
        val size = this.size
        ensureCapacity(size + 1)

        array.set(size, element)
        _size = size + 1
        return true
    }

    override fun add(index: Int, element: T): Unit = writeLock.withLock {
        val size = this.size
        if (index !in 0 .. size) {
            throw IndexOutOfBoundsException(index, array)
        }

        ensureCapacity(size + 1)
        val snapshot = array

        // Shift existing elements to the right.
        for (i in size downTo index + 1) {
            snapshot.set(i, snapshot.get(i - 1))
        }

        // Insert new element.
        snapshot.set(index, element)
        _size = size + 1
    }

    override fun addAll(elements: Collection<T>): Boolean = writeLock.withLock {
        if (elements.isEmpty()) return false

        val size = this.size
        ensureCapacity(size + elements.size)
        val snapshot = array

        elements.forEachIndexed { i, element ->
            snapshot.set(size + i, element)
        }
        _size += elements.size
        return true
    }

    override fun addAll(index: Int, elements: Collection<T>): Boolean = writeLock.withLock {
        if (elements.isEmpty()) return false

        val size = this.size
        if (index !in 0 .. size) {
            throw IndexOutOfBoundsException(index, array)
        }

        ensureCapacity(size + elements.size)
        val snapshot = array

        // Shift existing elements to the right.
        for (i in size - 1 downTo index) {
            snapshot.set(i + elements.size, snapshot.get(i))
        }

        // Insert new elements.
        elements.forEachIndexed { i, element ->
            snapshot.set(index + i, element)
        }
        _size += elements.size
        return true
    }

    override fun remove(element: T): Boolean = writeLock.withLock {
        val index = indexOf(element)
        if (index == -1) return false
        removeAt(index)
        return true
    }

    override fun removeAll(elements: Collection<T>): Boolean = writeLock.withLock {
        var modified = false
        elements.forEach {
            while (remove(it)) {
                modified = true
            }
        }
        return modified
    }

    override fun removeAt(index: Int): T = writeLock.withLock {
        val size = this.size
        val snapshot = array
        if (index !in 0 ..< size) {
            throw IndexOutOfBoundsException(index, snapshot)
        }

        val element = snapshot.get(index)
            // Should not be a tombstone, as we checked the size before.
            .ensure { it !== TOMBSTONE }

        // Shift elements to the left
        for (i in index until size - 1) {
            snapshot.set(i, snapshot.get(i + 1))
        }

        // Remove the last element (by replacing it with a tombstone).
        snapshot.set(size - 1, TOMBSTONE)
        _size = size - 1
        return element as T
    }

    override fun retainAll(elements: Collection<T>): Boolean = writeLock.withLock {
        val size = this.size
        val snapshot = array

        val retained = mutableListOf<T>()
        for (i in 0 until size) {
            val element = snapshot.get(i)
                // Should not be a tombstone, as we checked the size before.
                .ensure { it !== TOMBSTONE }

            if (element in elements) {
                retained.add(element as T)
            }
        }

        if (retained.size == size) return false

        for (i in retained.indices) {
            snapshot.set(i, retained[i])
        }
        for (i in retained.size until size) {
            snapshot.set(i, TOMBSTONE)
        }
        _size = retained.size
        return true
    }

    override fun clear(): Unit = writeLock.withLock {
        array = AtomicReferenceArray(Array(DEFAULT_CAPACITY) { TOMBSTONE })
        _size = 0
    }

    private fun ensureCapacity(capacity: Int) {
        if (capacity <= this.capacity) return

        val newCapacity = maxOf(capacity, this.capacity * 2)
        val newArray = AtomicReferenceArray<Any>(newCapacity)

        val size = this.size
        val snapshot = array

        for (i in 0 until size) {
            val element = snapshot.get(i)
                // Should not be a tombstone, as we checked the size before.
                .ensure { it !== TOMBSTONE }

            newArray.set(i, element as T)
        }
        for (i in size until newCapacity) {
            newArray.set(i, TOMBSTONE)
        }
        array = newArray
    }

    override fun iterator(): MutableIterator<T> =
        listIterator()

    override fun listIterator(): MutableListIterator<T> =
        listIterator(0)

    override fun listIterator(index: Int): MutableListIterator<T> {
        val size = this.size
        val snapshot = array
        if (index !in 0 .. size) {
            throw IndexOutOfBoundsException(index, snapshot)
        }
        return Iterator(index)
    }

    private inner class Iterator(private var index: Int) : MutableListIterator<T> {
        private var lastIndex = -1

        override fun hasNext(): Boolean = (index < size)
        override fun hasPrevious(): Boolean = (index > 0)

        override fun nextIndex(): Int = index
        override fun previousIndex(): Int = index - 1

        override fun next(): T {
            val snapshot = array
            if (index >= snapshot.length()) throw NoSuchElementException()

            val element = snapshot.get(index)
            if (element === TOMBSTONE) throw NoSuchElementException()

            lastIndex = index++
            return element as T
        }

        override fun previous(): T {
            if (index <= 0) throw NoSuchElementException()

            val snapshot = array
            val prevIndex = index - 1
            if (prevIndex >= snapshot.length()) throw NoSuchElementException()

            val element = snapshot.get(prevIndex)
            if (element === TOMBSTONE) throw NoSuchElementException()

            index = prevIndex
            lastIndex = prevIndex
            return element as T
        }

        override fun add(element: T) {
            this@ConcurrentSingleWriterList.add(index, element)
            index++
            lastIndex = -1
        }

        override fun remove() {
            check(lastIndex >= 0) { "No element to remove; call next() or previous() first" }
            this@ConcurrentSingleWriterList.removeAt(lastIndex)
            index = lastIndex
            lastIndex = -1
        }

        override fun set(element: T) {
            check(lastIndex >= 0) { "No element to set; call next() or previous() first" }
            set(lastIndex, element)
        }
    }

    override fun subList(fromIndex: Int, toIndex: Int): MutableList<T> = SubList(fromIndex, toIndex)

    private inner class SubList(from: Int, to: Int) : MutableSubList<T>(from, to, this@ConcurrentSingleWriterList) {

        override fun set(index: Int, element: T): T = writeLock.withLock {
            super.set(index, element)
        }

        override fun add(element: T): Boolean = writeLock.withLock {
            super.add(element)
        }

        override fun add(index: Int, element: T) = writeLock.withLock {
            super.add(index, element)
        }

        override fun addAll(elements: Collection<T>): Boolean = writeLock.withLock {
            super.addAll(elements)
        }

        override fun addAll(index: Int, elements: Collection<T>): Boolean = writeLock.withLock {
            super.addAll(index, elements)
        }

        override fun remove(element: T): Boolean = writeLock.withLock {
            super.remove(element)
        }

        override fun removeAll(elements: Collection<T>): Boolean = writeLock.withLock {
            super.removeAll(elements)
        }

        override fun removeAt(index: Int): T = writeLock.withLock {
            super.removeAt(index)
        }

        override fun retainAll(elements: Collection<T>): Boolean = writeLock.withLock {
            super.retainAll(elements)
        }

        override fun clear() = writeLock.withLock {
            super.clear()
        }

        override fun subList(fromIndex: Int, toIndex: Int): MutableList<T> {
            checkSubListBounds(fromIndex, toIndex)
            return SubList(from + fromIndex, from + toIndex)
        }
    }

    private fun IndexOutOfBoundsException(index: Int, snapshot: AtomicReferenceArray<Any>): IndexOutOfBoundsException {
        // Due to possible concurrent updates of the underlying array,
        // we do not know the precise size of the filled-in part of the array in advance.
        // We could, in theory, try to look up the index of the first `TOMBSTONE` value in the array.
        // However, it would complicate implementation (and make complexity linear in the worst case),
        // without significant benefits, because due to concurrent updates,
        // the notion of "precise array size at the current moment of time" is elusive.
        return IndexOutOfBoundsException("index $index is out of bounds")
    }

    companion object {
        private const val DEFAULT_CAPACITY = 10
    }

    // Special value used as a tombstone to mark elements in reserved slots
    // inside [size ... capacity) range.
    private object TOMBSTONE
}