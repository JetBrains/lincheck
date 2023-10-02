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

inline fun<reified T> List<Any?>.reify(): List<T>? {
    return if (all { it is T }) (this as List<T>) else null
}

private fun rangeCheck(size: Int, fromIndex: Int, toIndex: Int) {
    when {
        fromIndex > toIndex -> throw IllegalArgumentException("fromIndex ($fromIndex) is greater than toIndex ($toIndex).")
        fromIndex < 0 -> throw IndexOutOfBoundsException("fromIndex ($fromIndex) is less than zero.")
        toIndex > size -> throw IndexOutOfBoundsException("toIndex ($toIndex) is greater than size ($size).")
    }
}

fun<T> List<T>.binarySearch(fromIndex: Int = 0, toIndex: Int = size, predicate: (T) -> Boolean): Int {
    rangeCheck(size, fromIndex, toIndex)
    var low = fromIndex - 1
    var high = toIndex
    while (low + 1 < high) {
        val mid = (low + high).ushr(1) // safe from overflows
        if (predicate(get(mid)))
            high = mid
        else
            low = mid
    }
    return high
}

fun<T> MutableList<T>.expand(size: Int, defaultValue: T) {
    if (size > this.size) {
        addAll(List(size - this.size) { defaultValue })
    }
}

fun<T> MutableList<T>.cut(index: Int) {
    require(index <= size)
    subList(index, size).clear()
}

fun <T> List<T>.squash(relation: (T, T) -> Boolean): List<List<T>> {
    if (isEmpty())
        return emptyList()
    val squashed = arrayListOf<List<T>>()
    var pos = 0
    while (pos < size) {
        val i = pos
        var j = i
        while (++j < size) {
            if (!relation(get(j - 1), get(j)))
                break
        }
        squashed.add(subList(i, j))
        pos = j
    }
    return squashed
}

fun <T> List<Sequence<T>>.cartesianProduct(): Sequence<List<T>> = sequence {
    val sequences = this@cartesianProduct
    if (sequences.isEmpty())
        return@sequence

    // prepare iterators of argument sequences
    val iterators = sequences.map { it.iterator() }
        .toMutableList()
    // compute the first element of each argument sequence,
    // while also count the number of non-empty sequences
    var count = 0
    val elements = iterators.map {
        if (it.hasNext()) it.next().also { count++ } else null
    }.toMutableList()
    // return the empty sequence if at least one of the argument sequences is empty
    if (count != iterators.size)
        return@sequence
    // can cast here since the list can only contain elements
    // returned by iterators' `next()` function
    elements as MutableList<T>

    // produce tuples in a loop
    while (true) {
        // yield current tuple (make a copy)
        yield(elements.toMutableList())
        // prepare the next tuple:
        // while the last sequence has elements, spawn it
        if (iterators.last().hasNext()) {
            elements[iterators.lastIndex] = iterators.last().next()
            continue
        }
        // otherwise, reset the last sequence iterator,
        // advance a preceding sequence, and repeat this process
        // until we find a non-exceeded sequence
        var idx = iterators.indices.last
        while (idx >= 0 && !iterators[idx].hasNext()) {
            iterators[idx] = sequences[idx].iterator()
            elements[idx] = iterators[idx].next()
            idx -= 1
        }
        // if all sequences have been exceeded, return
        if (idx < 0)
            return@sequence
        // otherwise, advance the non-exceeded sequence
        elements[idx] = iterators[idx].next()
    }
}