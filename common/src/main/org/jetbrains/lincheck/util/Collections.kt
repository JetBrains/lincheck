/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Expands the list to the specified size by adding the given value
 * to the end of the list until it reaches the target size.
 * If the current size is already greater than or equal to the specified size,
 * the list remains unchanged.
 *
 * @param size the target size to expand the list to. Must be non-negative.
 * @param value the element to fill the list with.
 */
fun <T> MutableList<T>.expandTo(size: Int, value: T) {
    for (i in this.size until size) add(value)
}

/**
 * Truncates the mutable list to the specified size by removing all elements beyond the target size.
 * If the current size is already less than or equal to the specified size, the list remains unchanged.
 *
 * @param size the target size to truncate the list to.
 *   Must be non-negative and not greater than the current list size.
 */
fun <T> MutableList<T>.truncateTo(size: Int) {
    require(size >= 0 && size <= this.size) {
        "New size must be non-negative and not greater than the current list size"
    }
    this.subList(size, this.size).clear()
}

/**
 * Resizes the mutable list to the specified size.
 * If the new size is greater than the current size, the list is expanded and the new elements are
 * initialized to the specified default value.
 * If the new size is less than the current size, the list is truncated.
 *
 * @param size The target size for the list.
 * @param defaultValue The value to use for initializing new elements if the list needs to be expanded.
 */
fun <T> MutableList<T>.resize(size: Int, defaultValue: T) {
    if (this.size == size) {
        return
    } else if (this.size < size) {
        expandTo(size, defaultValue)
    } else {
        truncateTo(size)
    }
}

/**
 * Finds the index of the first element that matches the given [predicate]
 * starting from the specified [from] index.
 *
 * @param from the index from which to start searching. Must be within the bounds of the list.
 * @param predicate a lambda function to test each element for a condition.
 * @return the index of the first matching element, or -1 if no elements match the condition.
 * @throws IllegalArgumentException if [from] is out of bounds.
 */
fun <T> List<T>.indexOf(from: Int, predicate: (T) -> Boolean): Int {
    require(from in indices) {
        "Index out of bounds: $from"
    }
    for (i in from until size) {
        if (predicate(this[i])) return i
    }
    return -1
}

/**
 * Finds the index of the last element in the list that matches the given [predicate],
 * starting the search from the specified [from] index and moving backwards.
 *
 * @param from the index to start searching from, moving backwards.
 * @param predicate the condition to match elements against.
 * @return the index of the last element matching the condition, or -1 if no such element exists.
 * @throws IllegalArgumentException if [from] is out of bounds of the list.
 */
fun <T> List<T>.indexOfLast(from: Int, predicate: (T) -> Boolean): Int {
    require(from in indices) {
        "Index out of bounds: $from"
    }
    for (i in from downTo 0) {
        if (predicate(this[i])) return i
    }
    return -1
}

/**
 * Returns the first non-null transformed element.
 *
 * @param transform a transformation function.
 * @return the first non-null result of the transformation function, or null if no such result exists.
 */
fun <T, R> List<T>.findMapped(transform: (T) -> R?): R? {
    for (element in this) {
        transform(element)?.let { return it }
    }
    return null
}

/**
 * Returns a view of the portion of this mutable list within the specified range.
 *
 * @param range the range of indices defining the sublist,
 *        start index and end index are inclusive.
 * @return a mutable sublist view of the list within the specified range.
 */
fun <T> MutableList<T>.subList(range: IntRange): MutableList<T> =
    subList(range.first, range.last + 1)

/**
 * Moves an element within the mutable list from one index to another.
 * If the `from` and `to` indices are the same, no operation is performed.
 *
 * @param from The index of the element to move. Must be a valid index within the list.
 * @param to The target index where the element should be moved. Must be a valid index within the list.
 */
fun <T> MutableList<T>.move(from: Int, to: Int) {
    if (from == to) return
    val element = this[from]
    removeAt(from)
    // if `to` was after `from`, we need to adjust the insertion index
    val adjustedTo = if (from < to) to - 1 else to
    add(adjustedTo, element)
}

/**
 * Moves a sublist defined by the specified range of indices to a new position within the mutable list.
 *
 * @param from the range of indices that defines the source elements to be moved.
 *        The start index and end index of the range are inclusive.
 * @param to the target index where the sublist will be inserted.
 *        The index must not overlap with the source range.
 */
fun <T> MutableList<T>.move(from: IntRange, to: Int) {
    // don't need to do anything if the range is empty
    if (from.isEmpty()) return
    // check that the target position is not within the source range
    require(to !in from) {
        "Target position cannot be within the source range"
    }
    val sublist = this.subList(from)
    val elements = sublist.toList()
    val elementCount = elements.size
    // calculate the correct insertion index
    // based on where the range is relative to the target
    val adjustedTo = if (to > from.last) to - elementCount else to
    sublist.clear()
    addAll(adjustedTo, elements)
}

/**
 * Checks if the elements in the list are sorted in ascending order.
 *
 * @return `true` if the list is sorted in ascending order, `false` otherwise.
 */
fun <T : Comparable<T>> List<T>.isSorted(): Boolean {
    for (i in 1 until size) {
        if (this[i - 1] > this[i]) return false
    }
    return true
}

/**
 * Checks if the list is sorted in ascending order of the values returned by the given selector function.
 *
 * @param selector A function that maps each element in the list to a comparable value.
 * @return `true` if the list is sorted in ascending order, `false` otherwise.
 */
fun <T, R : Comparable<R>> List<T>.isSortedBy(selector: (T) -> R): Boolean {
    for (i in 1 until size) {
        if (selector(this[i - 1]) > selector(this[i])) return false
    }
    return true
}

/**
 * Checks if the elements in the list are sorted in ascending order according to the given comparator.
 *
 * @param comparator the comparator used to define the sorting order of the elements.
 * @return `true` if the list is sorted according to the comparator, `false` otherwise.
 */
fun <T> List<T>.isSortedWith(comparator: Comparator<in T>): Boolean {
    for (i in 1 until size) {
        if (comparator.compare(this[i - 1], this[i]) > 0) return false
    }
    return true
}

/**
 * Validates that a range specified by [fromIndex] and [toIndex] is within the bounds of the given collection.
 * Throws exceptions if the range is invalid.
 *
 * @param this the collection to check the range against.
 * @param fromIndex the starting index of the range, inclusive.
 * @param toIndex the ending index of the range, exclusive.
 * @throws IllegalArgumentException if [fromIndex] is greater than [toIndex].
 * @throws IndexOutOfBoundsException if [fromIndex] is negative or [toIndex] exceeds [size].
 */
fun <T> Collection<T>.validateRangeBounds(fromIndex: Int, toIndex: Int) {
    when {
        fromIndex > toIndex -> throw IllegalArgumentException("fromIndex ($fromIndex) is greater than toIndex ($toIndex).")
        fromIndex < 0 -> throw IndexOutOfBoundsException("fromIndex ($fromIndex) is less than zero.")
        toIndex > size -> throw IndexOutOfBoundsException("toIndex ($toIndex) is greater than size ($size).")
    }
}

/**
 * Performs a binary search to find the first index where the [predicate] returns `true`.
 *
 * The list is assumed to be partitioned according to the predicate, meaning all elements
 * for which the predicate returns `false` must precede all elements for which it returns `true`.
 *
 * @param fromIndex the starting index of the search range, inclusive; defaults to `0`.
 * @param toIndex the ending index of the search range, exclusive; defaults to list size.
 * @param predicate a function that returns `true` for elements in the "upper" partition
 *        and `false` for elements in the "lower" partition.
 * @return the index of the first element for which [predicate] returns `true`,
 *         or [toIndex] if no such element exists within the specified range.
 * @throws IllegalArgumentException if [fromIndex] is greater than [toIndex].
 * @throws IndexOutOfBoundsException if [fromIndex] is negative or [toIndex] exceeds list size.
 */
fun<T> List<T>.binarySearch(fromIndex: Int = 0, toIndex: Int = size, predicate: (T) -> Boolean): Int {
    validateRangeBounds(fromIndex, toIndex)

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

/**
 * Returns the average value of the elements in the list, or `null` if the list is empty.
 */
fun List<Long>.averageOrNull(): Double? {
    return if (isEmpty()) null else average()
}

/**
 * Splits the list into sublists where each sublist contains consecutive elements that satisfy the given relation.
 *
 * @param relation A binary relation that determines whether two consecutive elements should be in the same sublist.
 * @return A list of sublists, where each sublist contains consecutive elements that satisfy the relation.
 */
fun <T> List<T>.squash(relation: (T, T) -> Boolean): List<List<T>> {
    if (isEmpty()) return emptyList()
    var pos = 0
    val squashed = arrayListOf<List<T>>()
    while (pos < size) {
        val i = pos
        var j = i
        while (++j < size) {
            if (!relation(get(j - 1), get(j))) break
        }
        squashed.add(subList(i, j))
        pos = j
    }
    return squashed
}

/**
 * Computes the cartesian product of a list of sequences.
 *
 * Each element of the resulting sequence is a list representing one combination of elements,
 * where one element is taken from each input sequence.
 * The order of combinations adheres to the order of elements in the input sequences.
 * If any input sequence is empty, the resulting sequence will also be empty.
 *
 * @return a sequence of lists, where each list represents a tuple of elements from the input sequences.
 */
fun <T> List<Sequence<T>>.cartesianProduct(): Sequence<List<T>> = sequence {
    val sequences = this@cartesianProduct
    if (sequences.isEmpty()) return@sequence

    // prepare iterators of argument sequences
    val iterators = sequences.map { it.iterator() }.toMutableList()

    // compute the first element of each argument sequence,
    // while also count the number of non-empty sequences
    var count = 0
    val elements = iterators
        .map { if (it.hasNext()) it.next().also { count++ } else null }
        .toMutableList()
    // return the empty sequence if at least one of the argument sequences is empty
    if (count != iterators.size) return@sequence
    // can cast here since the list can only contain elements
    // returned by iterators' `next()` function
    @Suppress("UNCHECKED_CAST")
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
        if (idx < 0) return@sequence
        // otherwise, advance the non-exceeded sequence
        elements[idx] = iterators[idx].next()
    }
}

/**
 * Checks if all elements in the iterable satisfy the given predicate when invoked with their index and value.
 *
 * @param predicate A predicate that accepts the index and element as parameters.
 * @return `true` if all elements satisfy the predicate, `false` otherwise.
 */
fun <T> Iterable<T>.allIndexed(predicate: (Int, T) -> Boolean): Boolean {
    if (this is Collection && isEmpty()) return true
    var index = 0
    for (element in this) {
        if (!predicate(index++, element)) return false
    }
    return true
}

/**
 * Computes the intersection of all sets in this iterable.
 *
 * For an empty receiver, returns an empty mutable set.
 */
fun <T> Iterable<Set<T>>.intersectAll(): MutableSet<T> {
    val intersection = mutableSetOf<T>()
    forEachIndexed { i, set ->
        if (i == 0) intersection.addAll(set)
        else intersection.retainAll(set)
    }
    return intersection
}

/**
 * Creates a mutable set backed by an [IdentityHashMap].
 * This set uses identity comparisons `===` to determine equality of elements, rather than the `equals` method.
 *
 * @return A new empty mutable identity hash set.
 */
fun <T> identityHashSetOf(): MutableSet<T> =
    Collections.newSetFromMap(IdentityHashMap())

/**
 * Creates a mutable set backed by an [IdentityHashMap], initialized with the provided elements.
 * This set uses identity comparisons (`===`) to determine equality of elements, rather than the `equals` method.
 *
 * @param elements Zero or more elements to initialize the set with.
 * @return A new mutable identity hash set containing the provided elements.
 */
fun <T> identityHashSetOf(vararg elements: T): MutableSet<T> =
    elements.toCollection(identityHashSetOf())

/**
 * Updates a key-value pair in the map.
 *
 * If the [key] exists, its value is transformed using the provided transformation function.
 * If the key does not exist, it is mapped to the result of
 * applying the provided transformation function to the given [default] value.
 *
 * @param key the key whose associated value is to be updated or inserted.
 * @param default the default value to use if the key is not currently in the map.
 * @param transform a function that takes the current value and returns the transformed value.
 * @return the updated or inserted value associated with the key.
 */
fun <K, V> MutableMap<K, V>.update(key: K, default: V, transform: (V) -> V): V =
    compute(key) { _, current -> transform(current ?: default) }!!

/**
 * Performs inplace update of the value associated with the specified [key] in the map
 * by applying the given transformation.
 *
 * If the [key] is not already present in the map, the [default] value is used as the initial value,
 * and the transformation function is immediately applied to it.
 *
 * @param key the key whose associated value is to be updated.
 * @param default the default value to use if the key is not already present in the map.
 * @param apply a transformation function that modifies the value associated with the key.
 */
fun <K, V> MutableMap<K, V>.updateInplace(key: K, default: V, apply: V.() -> Unit) {
    computeIfAbsent(key) { default }.also(apply)
}

//TODO: Add docs. or perhaps this should go to boolean, since there is another refine function there
inline fun<reified T> List<Any?>.refine(): List<T>? {
    return if (all { it is T }) (this as List<T>) else null
}
