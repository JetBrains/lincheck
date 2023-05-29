/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.utils

import org.jetbrains.kotlinx.lincheck.implies

fun <T> List<T>.isChain(fromIndex : Int = 0, toIndex : Int = size, relation: (T, T) -> Boolean): Boolean {
    for (i in fromIndex until toIndex - 1) {
        if (!relation(get(i), get(i + 1)))
            return false
    }
    return true
}

fun <T : Comparable<T>> List<T>.isSorted(fromIndex : Int = 0, toIndex : Int = size): Boolean =
    isChain(fromIndex, toIndex) { x, y -> x <= y }

fun<T : Comparable<T>> sortedArrayListOf(vararg elements: T): SortedArrayList<T> =
    SortedArrayList(elements.asList())

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

interface MutableSortedList<T : Comparable<T>> : MutableList<T>, SortedList<T>

class SortedArrayList<T : Comparable<T>> : ArrayList<T>, MutableSortedList<T> {

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
        super.add(index, element).also {
            check((index - 1 >= 0) implies { get(index - 1) <= get(index) })
            check((index + 1 < size) implies { get(index) <= get(index + 1) })
        }
    }

    override fun addAll(elements: Collection<T>): Boolean {
        val oldSize = size
        return super.addAll(elements).also {
            check(isSorted(fromIndex = oldSize))
        }
    }

    override fun addAll(index: Int, elements: Collection<T>): Boolean {
        return super.addAll(index, elements).also {
            val lastIndex = index + elements.size
            check((index - 1 >= 0) implies { get(index - 1) <= get(index) })
            check((lastIndex + 1 < size) implies { get(lastIndex) <= get(lastIndex + 1) })
            check(isSorted(fromIndex = index, toIndex = lastIndex))
        }
    }

    override fun set(index: Int, element: T): T {
        return super.set(index, element).also {
            check((index - 1 >= 0) implies { get(index - 1) <= get(index) })
            check((index + 1 < size) implies { get(index) <= get(index + 1) })
        }
    }

    override fun contains(element: T): Boolean =
        super<MutableSortedList>.contains(element)

    override fun containsAll(elements: Collection<T>): Boolean =
        super<MutableSortedList>.containsAll(elements)

    override fun indexOf(element: T): Int =
        super<MutableSortedList>.indexOf(element)

    override fun lastIndexOf(element: T): Int =
        super<MutableSortedList>.lastIndexOf(element)

}