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

import kotlin.math.max

interface IntMap<out T> {

    val keys: Set<Int>

    val values: Collection<T>

    val size: Int

    fun isEmpty(): Boolean

    fun containsKey(key: Int): Boolean

    fun containsValue(value: @UnsafeVariance T): Boolean

    operator fun get(key: Int): T?

}

interface MutableIntMap<T>: IntMap<T> {

    fun put(key: Int, value: T): T?

    fun remove(key: Int)

    fun clear()

}

fun<T> IntMap<T>.forEach(action: (Int, T) -> Unit) {
    for (key in keys) {
        action(key, get(key) ?: continue)
    }
}

inline fun<T> MutableIntMap<T>.getOrPut(key: Int, defaultValue: () -> T): T {
    get(key)?.let { return it }
    return defaultValue().also { put(key, it) }
}

operator fun<T> MutableIntMap<T>.set(key: Int, value: T) {
    put(key, value)
}

fun <T> MutableIntMap<T>.update(key: Int, default: T, transform: (T) -> T) {
    // TODO: could it be done with a single lookup in a map?
    put(key, get(key)?.let(transform) ?: default)
}

fun <T> MutableIntMap<T>.mergeReduce(other: IntMap<T>, reduce: (T, T) -> T) {
    other.forEach { key, value ->
        update(key, default = value) { reduce(it, value) }
    }
}

class ArrayMap<T : Any>(capacity: Int) : MutableIntMap<T> {

    var capacity: Int = capacity
        private set

    override var size: Int = 0
        private set

    private val array = MutableList<T?>(capacity) { null }

    override val keys: Set<Int>
        get() = (0 until capacity).filter { containsKey(it) }.toSet()

    override val values: Collection<T>
        get() = array.filterNotNull()

    constructor(capacity: Int, init: (Int) -> T?) : this(capacity) {
        for (i in 0 until capacity) {
            array[i] = init(i)
        }
    }

    override fun isEmpty(): Boolean =
        size == 0

    override fun containsKey(key: Int): Boolean =
        array[key] != null

    override fun containsValue(value: T): Boolean =
        array.any { it == value }

    override fun get(key: Int): T? =
        array.getOrNull(key)

    override fun put(key: Int, value: T): T? {
        val oldValue = get(key)
        if (key > array.size) {
            array.expand(key + 1, null)
        }
        size++
        array[key] = value
        return oldValue
    }

    override fun remove(key: Int) {
        size--
        array[key] = null
    }

    override fun clear() {
        size = 0
        array.clear()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is ArrayMap<*>)
            return false
        for (i in 0 until max(array.size, other.array.size)) {
            if (this[i] != other[i])
                return false
        }
        return true
    }

    override fun hashCode(): Int {
        var hashCode = 1
        for (i in array.indices) {
            hashCode = 31 * hashCode + (this[i]?.hashCode() ?: 0)
        }
        return hashCode
    }

    override fun toString(): String =
        array.mapIndexedNotNull { key, value -> value?.let { (key to it) } }.toString()

}