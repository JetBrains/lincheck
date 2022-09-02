/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

fun <K, V> MutableMap<K, V>.update(key: K, default: V, transform: (V) -> V) {
    // TODO: could it be done with a single lookup in a map?
    put(key, get(key)?.let(transform) ?: default)
}

fun <K, V> Map<K, V>.mergeReduce(other: Map<K, V>, reduce: (V, V) -> V): MutableMap<K, V> =
    toMutableMap().apply { other.forEach { (key, value) ->
        update(key, default = value) { reduce(it, value) }
    }}

fun <T> List<T>.getSquashed(position: Int, combine: (T, T) -> T?): Pair<T, Int>? {
    var i = position
    var accumulator = getOrNull(i) ?: return null
    while (++i < size) {
        accumulator = combine(accumulator, get(i)) ?: break
    }
    return accumulator to i
}

fun <T> List<T>.squash(combine: (T, T) -> T?): List<T> {
    if (isEmpty()) return emptyList()
    val squashed = arrayListOf<T>()
    var position = 0
    while (position < size) {
        val (element, nextPosition) = getSquashed(position, combine)!!
        squashed.add(element)
        position = nextPosition
    }
    return squashed
}

fun <T> List<T>.isChain(fromIndex : Int = 0, toIndex : Int = size, relation: (T, T) -> Boolean): Boolean {
    for (i in fromIndex until toIndex - 1) {
        if (!relation(get(i), get(i + 1)))
            return false
    }
    return true
}

fun <T : Comparable<T>> List<T>.isSorted(fromIndex : Int = 0, toIndex : Int = size): Boolean =
    isChain(fromIndex, toIndex) { x, y -> x <= y }

infix fun Boolean.implies(other: Boolean): Boolean = !this || other

infix fun Boolean.implies(other: () -> Boolean): Boolean = !this || other()

class UnreachableException: Exception()

fun unreachable(): Nothing {
    throw UnreachableException()
}

interface SortedList<T : Comparable<T>> : List<T> {

    override fun contains(element: T): Boolean =
        binarySearch(element) >= 0

    override fun containsAll(elements: Collection<T>): Boolean =
        elements.all { contains(it) }

    override fun indexOf(element: T): Int {
        var i: Int
        var j = size
        do {
            i = j
            j = binarySearch(element, toIndex = i)
        } while (j >= 0)
        return i
    }

    override fun lastIndexOf(element: T): Int {
        var i: Int
        var j = 0
        do {
            i = j
            j = binarySearch(element, fromIndex = i)
        } while (j >= 0)
        return i
    }

}

class SortedArrayList<T : Comparable<T>> : ArrayList<T>, SortedList<T> {

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
        super<SortedList>.contains(element)

    override fun containsAll(elements: Collection<T>): Boolean =
        super<SortedList>.containsAll(elements)

    override fun indexOf(element: T): Int =
        super<SortedList>.indexOf(element)

    override fun lastIndexOf(element: T): Int =
        super<SortedList>.lastIndexOf(element)

}

fun<T : Comparable<T>> sortedArrayListOf(vararg elements: T): SortedArrayList<T> =
    SortedArrayList(elements.asList())
