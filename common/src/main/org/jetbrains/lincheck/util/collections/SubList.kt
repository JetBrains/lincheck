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

/**
 * Returns a read-only sublist view of the given list
 * starting from the [from] index (inclusive) and ending at the [to] index (exclusive).
 *
 * @see SubList
 */
fun <T> sublist(from: Int, to: Int, list: List<T>): List<T> =
    SubList(from, to, list)

/**
 * Returns a mutable sublist view of the given list,
 * starting from the [from] index (inclusive) and ending at the [to] index (exclusive).
 *
 * @see MutableSubList
 */
fun <T> mutableSublist(from: Int, to: Int, list: MutableList<T>): MutableList<T> =
    MutableSubList(from, to, list)

/**
 * A generic implementation of a sublist that represents a read-only view of a specific,
 * contiguous portion of another list.
 *
 * Changes to the backing list are reflected in the sublist, unless these changes
 * _structurally modify_ the list (for instance, add or remove elements from the list) ---
 * in which case the sublist behavior is undefined.
 * This is the same contract as guaranteed by Java's [java.util.List.subList] method.
 *
 * Primarily use case for this class is to simplify implementation of custom [List] interface implementations
 * by providing a simple way to implement [List.subList] method via delegation to [SubList].
 *
 * @param T The type of elements in this list.
 * @param from The starting index (inclusive) of the sublist within the original list.
 * @param to The ending index (exclusive) of the sublist within the original list.
 * @param list The original list from which the sublist is created.
 * @throws IllegalArgumentException If the provided indices [from] or [to] are invalid.
 */
open class SubList<T>(open val from: Int, open val to: Int, open val list: List<T>) : List<T> {

    init {
        require(from >= 0) {
            "Invalid sublist indices: from must be non-negative, actual: $from."
        }
        require(to <= list.size) {
            "Invalid sublist indices: to must be less than or equal to list size, actual: $to."
        }
        require(from <= to) {
            "Invalid sublist indices: from must be less than or equal to to, actual: ($from, $to)."
        }
    }

    override val size: Int
        get() = to - from

    override fun isEmpty(): Boolean =
        (from == to)

    override fun get(index: Int): T {
        checkBounds(index)
        return list[from + index]
    }

    override fun contains(element: T): Boolean {
        for (i in from until to) {
            if (list[i] == element) return true
        }
        return false
    }

    override fun containsAll(elements: Collection<T>): Boolean {
        return elements.all { contains(it) }
    }

    override fun indexOf(element: T): Int {
        for (i in from until to) {
            if (list[i] == element) return i - from
        }
        return -1
    }

    override fun lastIndexOf(element: T): Int {
        for (i in to - 1 downTo from) {
            if (list[i] == element) return i - from
        }
        return -1
    }

    override fun subList(fromIndex: Int, toIndex: Int): List<T> {
        checkSubListBounds(fromIndex, toIndex)
        return SubList(from + fromIndex, from + toIndex, list)
    }

    override fun iterator(): kotlin.collections.Iterator<T> = listIterator()

    override fun listIterator(): ListIterator<T> = listIterator(0)

    override fun listIterator(index: Int): ListIterator<T> = Iterator(index)

    open inner class Iterator(index: Int) : ListIterator<T> {
        protected var index = from + index

        override fun hasNext(): Boolean = (index < to)
        override fun hasPrevious(): Boolean = (index > from)

        override fun next(): T = list[index++]
        override fun previous(): T = list[--index]

        override fun nextIndex(): Int = index - from
        override fun previousIndex(): Int = index - from - 1
    }

    protected fun checkBounds(index: Int) {
        if (index !in 0 ..< size) {
            throw IndexOutOfBoundsException("Index $index out of bounds for list of size $size")
        }
    }

    protected fun checkSubListBounds(fromIndex: Int, toIndex: Int) {
        if (fromIndex < 0 || toIndex > size || fromIndex > toIndex) {
            throw IndexOutOfBoundsException("SubList indices out of bounds: fromIndex=$fromIndex, toIndex=$toIndex, size=$size")
        }
    }
}

/**
 * A generic implementation of a sublist that represents a mutable view of a specific,
 * contiguous portion of another list.
 *
 * Changes to this list view are reflected in the original list.
 * Changes to the backing list are reflected in the sublist, unless these changes
 * _structurally modify_ the list (for instance, add or remove elements from the list) ---
 * in which case the sublist behavior is undefined.
 * This is the same contract as guaranteed by Java's [java.util.List.subList] method.
 *
 * @see SubList
 *
 * @param T The type of elements in the list.
 * @param from The starting index (inclusive) of the sublist within the original list.
 * @param to The ending index (exclusive) of the sublist within the original list.
 * @param list The original mutable list from which the sublist is created.
 * @throws IllegalArgumentException If the provided indices [from] or [to] are invalid.
 */
open class MutableSubList<T>(from: Int, to: Int, override val list: MutableList<T>) : SubList<T>(from, to, list), MutableList<T> {

    override var from: Int = from
        protected set

    override var to: Int = to
        protected set

    override fun set(index: Int, element: T): T {
        checkBounds(index)
        return list.set(from + index, element)
    }

    override fun add(element: T): Boolean {
        list.add(to++, element)
        return true
    }

    override fun add(index: Int, element: T) {
        checkBoundsForAdd(index)
        list.add(from + index, element)
        to++
    }

    override fun addAll(elements: Collection<T>): Boolean {
        if (elements.isEmpty()) return false
        
        list.addAll(to, elements)
        to += elements.size
        return true
    }

    override fun addAll(index: Int, elements: Collection<T>): Boolean {
        if (elements.isEmpty()) return false

        checkBoundsForAdd(index)
        list.addAll(from + index, elements)
        to += elements.size
        return true
    }

    override fun remove(element: T): Boolean {
        for (i in from until to) {
            if (list[i] == element) {
                list.removeAt(i)
                to--
                return true
            }
        }
        return false
    }

    override fun removeAll(elements: Collection<T>): Boolean {
        var modified = false
        for (element in elements) {
            remove(element).also { modified = modified || it }
        }
        return modified
    }

    override fun removeAt(index: Int): T {
        checkBounds(index)
        return list.removeAt(from + index).also { to-- }
    }

    override fun retainAll(elements: Collection<T>): Boolean {
        val toRemove = mutableListOf<T>()
        for (i in from until to) {
            val element = list[i]
            if (element !in elements) {
                toRemove.add(element)
            }
        }
        return removeAll(toRemove)
    }

    override fun clear() {
        for (i in to - 1 downTo from) {
            list.removeAt(i)
        }
        to = from
    }

    override fun subList(fromIndex: Int, toIndex: Int): MutableList<T> {
        checkSubListBounds(fromIndex, toIndex)
        return MutableSubList(from + fromIndex, from + toIndex, list)
    }

    override fun iterator(): kotlin.collections.MutableIterator<T> = listIterator()

    override fun listIterator(): MutableListIterator<T> = listIterator(0)

    override fun listIterator(index: Int): MutableListIterator<T> = MutableIterator(index)

    inner class MutableIterator(index: Int) : SubList<T>.Iterator(index), MutableListIterator<T> {
        private var lastIndex = -1

        override fun next(): T {
            lastIndex = index
            return super.next()
        }

        override fun previous(): T {
            lastIndex = index - 1
            return super.previous()
        }

        override fun set(element: T) {
            check(lastIndex >= 0) { "No element to set; call next() or previous() first" }
            list[lastIndex] = element
        }

        override fun add(element: T) {
            list.add(index++, element)
            lastIndex = -1
            to++
        }

        override fun remove() {
            check(lastIndex >= 0) { "No element to remove; call next() or previous() first" }
            list.removeAt(lastIndex)
            if (lastIndex < index) {
                index--
            }
            lastIndex = -1
            to--
        }
    }

    protected fun checkBoundsForAdd(index: Int) {
        if (index !in 0 .. size) {
            throw IndexOutOfBoundsException("Index $index out of bounds for adding an element to list of size $size")
        }
    }
}