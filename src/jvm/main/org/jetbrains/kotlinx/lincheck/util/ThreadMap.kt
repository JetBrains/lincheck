/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util

typealias ThreadId = Int

// TODO: document why we need separate map for threads
//   (long story short, to ensure the thread managing code is not instrumented)

interface ThreadMap<out T> {

    val size: Int

    fun isEmpty(): Boolean

    operator fun get(threadId: ThreadId): T

    fun getOrNull(threadId: ThreadId): T?

}

interface MutableThreadMap<T>: ThreadMap<T> {

    fun add(value: T)

    fun clear()

}

fun <T> threadMapOf(): ThreadMap<T> =
    mutableThreadMapOf()

fun <T : Any> mutableThreadMapOf(): MutableThreadMap<T> =
    ArrayThreadMap()

fun <T> ThreadMap<T>.find(predicate: (T) -> Boolean): T? {
    var i = 0
    while (i < size) {
        val value = get(i)
        if (predicate(value))
            return value
        i++
    }
    return null
}

fun <T> ThreadMap<T>.forEach(action: (T) -> Unit) {
    var i = 0
    while (i < size) {
        val value = get(i)
        action(value)
        i++
    }
}

fun <T> ThreadMap<T>.all(predicate: (T) -> Boolean): Boolean {
    var i = 0
    while (i < size) {
        val value = get(i)
        if (!predicate(value))
            return false
        i++
    }
    return true
}

private class ArrayThreadMap<T : Any> : MutableThreadMap<T> {

    private var array = Array<Any?>(DEFAULT_CAPACITY) { null }

    val capacity: Int
        get() = array.size

    override var size: Int = 0
        private set

    override fun isEmpty(): Boolean =
        (size == 0)

    @Suppress("UNCHECKED_CAST")
    override fun get(threadId: ThreadId): T {
        if (threadId < 0 || threadId >= size)
            throw IndexOutOfBoundsException("Thread id $threadId is out of bounds")
        return (array[threadId] as T)
    }

    @Suppress("UNCHECKED_CAST")
    override fun getOrNull(threadId: ThreadId): T? {
        return if (threadId >= 0 && threadId < capacity) (array[threadId] as? T?) else null
    }

    override fun add(value: T) {
        if (size == capacity) {
            expand()
        }
        array[size++] = value
    }

    override fun clear() {
        array = Array(DEFAULT_CAPACITY) { null }
        size = 0
    }

    private fun expand() {
        val newCapacity = capacity * 2
        val newArray = Array<Any?>(newCapacity) { null }
        System.arraycopy(array, 0, newArray, 0, capacity)
        array = newArray
    }

}

private const val DEFAULT_CAPACITY = 8