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

    data class Entry<out T>(val key: Int, val value: T)

    val keys: Set<Int>

    val values: Collection<T>

    val entries: Set<Entry<T>>

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

fun<T> intMapOf(vararg pairs: Pair<Int, T>) : IntMap<T> =
    mutableIntMapOf(*pairs)

fun<T> mutableIntMapOf(vararg pairs: Pair<Int, T>) : MutableIntMap<T> =
    ArrayMap(*pairs)

fun<T> IntMap<T>.forEach(action: (IntMap.Entry<T>) -> Unit) =
    entries.forEach(action)

fun<T, R> IntMap<T>.map(transform: (IntMap.Entry<T>) -> R) =
    entries.map(transform)

fun<T, R> IntMap<T>.mapNotNull(transform: (IntMap.Entry<T>) -> R?) =
    entries.mapNotNull(transform)

fun <T, R> IntMap<T>.mapValues(transform: (IntMap.Entry<T>) -> R?) =
    entries
        .map { entry -> entry.key to transform(entry) }
        .let { intMapOf(*it.toTypedArray()) }

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
    other.forEach { (key, value) ->
        update(key, default = value) { reduce(it, value) }
    }
}

class ArrayMap<T>(capacity: Int) : MutableIntMap<T> {

    override var size: Int = 0
        private set

    private val array = MutableList<T?>(capacity) { null }

    // we keep a separate bitmap identifying mapped keys
    // to distinguish user-provided `null` value from `null` as not-yet-mapped value
    private var bitmap = BooleanArray(capacity) { false }

    val capacity: Int
        get() = array.size

    override val keys: Set<Int>
        get() = array.indices.filter { containsKey(it) }.toSet()

    override val values: Collection<T>
        get() = array.filterIndexed { i, _ -> bitmap[i] } as Collection<T>

    override val entries: Set<IntMap.Entry<T>>
        get() = array.indices.mapNotNull { i ->
            if (containsKey(i))
                IntMap.Entry(i, array[i] as T)
            else null
        }.toSet()

    constructor(vararg pairs: Pair<Int, T>)
        : this(1 + (pairs.maxOfOrNull { (i, _) -> i } ?: -1)) {
        require(pairs.all { (i, _) -> i >= 0 })
        if (capacity == 0)
            return
        pairs.forEach { (key, value) ->
            array[key] = value
            bitmap[key] = true
        }
    }

    override fun isEmpty(): Boolean =
        size == 0

    override fun containsKey(key: Int): Boolean =
        bitmap[key]

    override fun containsValue(value: T): Boolean {
        for (i in array.indices) {
            if (bitmap[i] && value == array[i])
                return true
        }
        return false
    }

    override fun get(key: Int): T? =
        array.getOrNull(key)

    override fun put(key: Int, value: T): T? {
        val oldValue = get(key)
        if (key > array.size) {
            val capacity = key + 1
            array.expand(capacity, null)
            bitmap = BooleanArray(capacity) { false }
        }
        size++
        array[key] = value
        bitmap[key] = true
        return oldValue
    }

    override fun remove(key: Int) {
        size--
        array[key] = null
        bitmap[key] = false
    }

    override fun clear() {
        size = 0
        array.clear()
        bitmap.fill(false)
    }

    fun copy() = ArrayMap<T>(capacity).also {
        for (i in 0 until capacity) {
            if (!bitmap[i])
                continue
            it[i] = this[i] as T
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is ArrayMap<*>)
            return false
        for (i in 0 until max(array.size, other.array.size)) {
            if (this[i] != other[i] || bitmap[i] != other.bitmap[i])
                return false
        }
        return true
    }

    override fun hashCode(): Int {
        var hashCode = 1
        for (i in array.indices) {
            if (!bitmap[i])
                continue
            hashCode = 31 * hashCode + (this[i]?.hashCode() ?: 0)
        }
        return hashCode
    }

    override fun toString(): String =
        keys.map { key -> key to get(key) }.toString()

}