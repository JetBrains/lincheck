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

import org.jetbrains.lincheck.util.update
import kotlin.math.max

interface IntMap<out T> {

    interface Entry<out T> {
        val key: Int
        val value: T
    }

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

    interface MutableEntry<T> : IntMap.Entry<T> {
        fun setValue(newValue: T): T
    }

    override val keys: MutableSet<Int>

    override val values: MutableCollection<T>

    override val entries: MutableSet<MutableEntry<T>>

    fun put(key: Int, value: T): T?

    fun remove(key: Int)

    fun clear()

}

operator fun<T> IntMap.Entry<T>.component1(): Int = key
operator fun<T> IntMap.Entry<T>.component2(): T = value

fun<T> intMapOf(vararg pairs: Pair<Int, T>) : IntMap<T> =
    mutableIntMapOf(*pairs)

fun<T> mutableIntMapOf(vararg pairs: Pair<Int, T>) : MutableIntMap<T> =
    ArrayIntMap(*pairs)

fun<T> IntMap<T>.forEach(action: (IntMap.Entry<T>) -> Unit) =
    entries.forEach(action)

fun<T, R> IntMap<T>.map(transform: (IntMap.Entry<T>) -> R) =
    entries.map(transform)

fun<T, R> IntMap<T>.mapNotNull(transform: (IntMap.Entry<T>) -> R?) =
    entries.mapNotNull(transform)

fun <T, R> IntMap<T>.mapValues(transform: (IntMap.Entry<T>) -> R) =
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

// TODO: probably we should remove this file altogether and move this to utils?
fun <T> MutableThreadMap<T>.mergeReduce(other: ThreadMap<T>, reduce: (T, T) -> T) {
    other.forEach { (key, value) ->
        update(key, default = value) { reduce(it, value) }
    }
}

// TODO: Why is this needed? See how to make error in execution frontier go away
fun <T> MutableThreadMap<T>.copy(): MutableThreadMap<T> {
    return copy()
}

class ArrayIntMap<T>(capacity: Int) : MutableIntMap<T> {

    override var size: Int = 0
        private set

    private val array = MutableList<T?>(capacity) { null }

    // we keep a separate bitmap identifying mapped keys
    // to distinguish user-provided `null` value from `null` as not-yet-mapped value
    private var bitmap = BooleanArray(capacity) { false }

    val capacity: Int
        get() = array.size

    override val keys: MutableSet<Int>
        get() = KeySet()

    override val values: MutableCollection<T>
        get() = ValueCollection()

    override val entries: MutableSet<MutableIntMap.MutableEntry<T>>
        get() = EntrySet()

    constructor(vararg pairs: Pair<Int, T>) : this(pairs.calculateCapacity()) {
        if (capacity == 0)
            return
        pairs.forEach { (key, value) ->
            ++size
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
        if (key >= array.size) {
            val newCapacity = key + 1
            expand(newCapacity)
        }
        if (!bitmap[key]) {
            size++
        }
        array[key] = value
        bitmap[key] = true
        return oldValue
    }

    override fun remove(key: Int) {
        if (bitmap[key]) {
            size--
        }
        array[key] = null
        bitmap[key] = false
    }

    override fun clear() {
        size = 0
        array.clear()
        bitmap.fill(false)
    }

    fun copy() = ArrayIntMap<T>(capacity).also {
        for (i in 0 until capacity) {
            if (!bitmap[i])
                continue
            it[i] = this[i] as T
        }
    }

    private fun expand(newCapacity: Int) {
        require(newCapacity > capacity)
        array.expand(newCapacity, null)
        bitmap = BooleanArray(newCapacity) { i -> i < bitmap.size && bitmap[i] }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is ArrayIntMap<*>)
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

    private inner class KeySet : AbstractMutableSet<Int>() {

        override val size: Int
            get() = this@ArrayIntMap.size

        override fun contains(element: Int): Boolean =
            this@ArrayIntMap.containsKey(element)

        override fun add(element: Int): Boolean {
            throw UnsupportedOperationException("Unsupported operation.")
        }

        // TODO: cannot override because of weird compiler bug (probably due to boxing and override)
        // override fun remove(element: Int): Boolean {
        //     return this@ArrayIntMap.containsKey(element).also {
        //         this@ArrayIntMap.remove(element)
        //     }
        // }

        override fun iterator() = object : IteratorBase<Int>() {
            override fun getElement(key: Int): Int {
                return key
            }
        }
    }

    private inner class ValueCollection : AbstractMutableCollection<T>() {

        override val size: Int
            get() = this@ArrayIntMap.size

        override fun add(element: T): Boolean {
            throw UnsupportedOperationException("Unsupported operation.")
        }

        override fun iterator() = object : IteratorBase<T>() {
            override fun getElement(key: Int): T {
                return this@ArrayIntMap[key] as T
            }
        }
    }

    private inner class EntrySet : AbstractMutableSet<MutableIntMap.MutableEntry<T>>() {

        override val size: Int
            get() = this@ArrayIntMap.size

        override fun add(element: MutableIntMap.MutableEntry<T>): Boolean {
            val (key, value) = element
            val prev = this@ArrayIntMap.put(key, value)
            return (prev != value)
        }

        override fun remove(element: MutableIntMap.MutableEntry<T>): Boolean {
            val (key, value) = element
            val prev = this@ArrayIntMap[key]
            this@ArrayIntMap.remove(key)
            return (prev == value)
        }

        override fun iterator() = object : IteratorBase<MutableIntMap.MutableEntry<T>>() {
            override fun getElement(key: Int): MutableIntMap.MutableEntry<T> {
                val value = this@ArrayIntMap[key] as T
                return Entry(key, value)
            }
        }
    }

    private data class Entry<T>(
        override val key: Int,
        override var value: T,
    ) : MutableIntMap.MutableEntry<T> {
        override fun setValue(newValue: T): T {
            val prev = value
            value = newValue
            return prev
        }
    }

    private abstract inner class IteratorBase<E> : AbstractIterator<E>(), MutableIterator<E> {
        private var index: Int = -1

        override fun computeNext() {
            while (++index < this@ArrayIntMap.capacity) {
                if (this@ArrayIntMap.containsKey(index)) {
                    setNext(getElement(index))
                    return
                }
            }
            done()
        }

        override fun remove() {
            throw UnsupportedOperationException("Unsupported operation.")
        }

        abstract fun getElement(key: Int): E

    }

    companion object {
        private fun<T> Array<out Pair<Int, T>>.calculateCapacity(): Int {
            require(all { (i, _) -> i >= 0 })
            return 1 + (maxOfOrNull { (i, _) -> i } ?: -1)
        }
    }

}