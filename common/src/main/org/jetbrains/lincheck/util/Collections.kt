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

fun <K, V> MutableMap<K, V>.update(key: K, default: V, transform: (V) -> V): V =
    compute(key) { _, current -> transform(current ?: default) }!!

fun <K, V> MutableMap<K, V>.updateInplace(key: K, default: V, apply: V.() -> Unit) {
    computeIfAbsent(key) { default }.also(apply)
}