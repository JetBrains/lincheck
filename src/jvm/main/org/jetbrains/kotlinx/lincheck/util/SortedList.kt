/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util

import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.Relation
import org.jetbrains.lincheck.util.ensure

interface SortedList<out T : Comparable<@UnsafeVariance T>> : List<T> {

    override fun contains(element: @UnsafeVariance T): Boolean =
        binarySearch(element) >= 0

    override fun containsAll(elements: Collection<@UnsafeVariance T>): Boolean =
        elements.all { contains(it) }

    override fun indexOf(element: @UnsafeVariance T): Int {
        var i: Int
        var j = size
        do {
            i = j
            j = binarySearch(element, toIndex = i)
        } while (j >= 0)
        return i
    }

    override fun lastIndexOf(element: @UnsafeVariance T): Int {
        var i: Int
        var j = 0
        do {
            i = j
            j = binarySearch(element, fromIndex = i)
        } while (j >= 0)
        return i
    }

}

interface SortedMutableList<T : Comparable<T>> : MutableList<T>, SortedList<T>

private class SortedListImpl<T : Comparable<T>>(val list: List<T>) : SortedList<T> {

    init {
        require(list.isSorted())
    }

    override val size: Int
        get() = list.size

    override fun isEmpty(): Boolean =
        list.isEmpty()

    override fun get(index: Int): T =
        list[index]

    override fun subList(fromIndex: Int, toIndex: Int): SortedList<T> {
        return SortedListImpl(list.subList(fromIndex, toIndex))
    }

    override fun iterator(): Iterator<T> =
        list.iterator()

    override fun listIterator(): ListIterator<T> =
        list.listIterator()

    override fun listIterator(index: Int): ListIterator<T> =
        list.listIterator(index)

}

class SortedArrayList<T : Comparable<T>> : ArrayList<T>, SortedMutableList<T> {

    constructor() : super()

    constructor(initialCapacity: Int) : super(initialCapacity)

    constructor(elements: Collection<T>) : super(elements) {
        require(isSorted()) { "Expected sorted list" }
    }

    override fun add(element: T): Boolean {
        require(isNotEmpty() implies { last() <= element })
        return super.add(element)
    }

    override fun add(index: Int, element: T) {
        require((index - 1 >= 0) implies { get(index - 1) <= element })
        require((index < size) implies { element <= get(index) })
        super.add(index, element)
    }

    override fun addAll(elements: Collection<T>): Boolean {
        val oldSize = size
        return super.addAll(elements).ensure {
            isSorted(fromIndex = oldSize)
        }
    }

    override fun addAll(index: Int, elements: Collection<T>): Boolean {
        return super.addAll(index, elements).ensure {
            val lastIndex = index + elements.size
            val fromIndex = if (index - 1 >= 0) (index - 1) else index
            val toIndex = if (lastIndex + 1 < size) (lastIndex + 1) else lastIndex
            isSorted(fromIndex = fromIndex, toIndex = toIndex)
        }
    }

    override fun set(index: Int, element: T): T {
        require((index - 1 >= 0) implies { get(index - 1) <= element })
        require((index + 1 < size) implies { element <= get(index + 1) })
        return super.set(index, element)
    }

    override fun contains(element: T): Boolean =
        super<SortedMutableList>.contains(element)

    override fun containsAll(elements: Collection<T>): Boolean =
        super<SortedMutableList>.containsAll(elements)

    override fun indexOf(element: T): Int =
        super<SortedMutableList>.indexOf(element)

    override fun lastIndexOf(element: T): Int =
        super<SortedMutableList>.lastIndexOf(element)

}

fun<T : Comparable<T>> sortedListOf(vararg elements: T): SortedList<T> =
    SortedListImpl(elements.asList())

fun<T : Comparable<T>> sortedMutableListOf(vararg elements: T): SortedMutableList<T> =
    sortedArrayListOf(*elements)

fun<T : Comparable<T>> sortedArrayListOf(vararg elements: T): SortedArrayList<T> =
    SortedArrayList(elements.asList())


fun <T : Comparable<T>> List<T>.isSorted(fromIndex : Int = 0, toIndex : Int = size): Boolean =
    isChain(fromIndex, toIndex) { x, y -> x <= y }

fun <T> List<T>.isChain(fromIndex : Int = 0, toIndex : Int = size, relation: Relation<T>): Boolean {
    for (i in fromIndex until toIndex - 1) {
        if (!relation(get(i), get(i + 1)))
            return false
    }
    return true
}