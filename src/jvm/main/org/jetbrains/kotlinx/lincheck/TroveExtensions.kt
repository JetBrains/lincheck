/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck

import gnu.trove.list.TIntList
import gnu.trove.list.array.TIntArrayList
import gnu.trove.map.TIntIntMap
import gnu.trove.map.TIntObjectMap
import gnu.trove.set.hash.TIntHashSet


internal val <T> TIntObjectMap<T>.size: Int get() = this.size()

internal val <T> TIntObjectMap<T>.keys: IntArray get() = this.keys()

internal val TIntList.size: Int get() = this.size()

internal val TIntList.lastIndex: Int get() = this.size - 1

internal fun TIntIntMap.getOrDefault(key: Int, defaultValue: Int): Int = if (containsKey(key)) get(key) else defaultValue

internal fun IntArray.toTIntList(): TIntList = TIntArrayList(this)

internal operator fun <T> TIntObjectMap<T>.set(key: Int, value: T): T = put(key, value)

internal operator fun TIntIntMap.set(key: Int, value: Int): Int = put(key, value)

internal operator fun TIntList.plusAssign(value: Int) {
    add(value)
}

internal fun <T> TIntObjectMap<T>.computeIfAbsent(key: Int, elementProducer: () -> T): T {
    if (containsKey(key)) return get(key)
    val element = elementProducer()
    this[key] = element

    return element
}

internal fun TIntList.lastOrNull(): Int? = if (isEmpty) null else this[lastIndex]

internal fun Collection<Int>.toTIntHashSet(): TIntHashSet = TIntHashSet(this)

internal fun Collection<Int>.toTIntList(): TIntArrayList {
    val result = TIntArrayList(size)
    for (element in this) {
        result.add(element)
    }

    return result
}

@Suppress("DuplicatedCode")
internal inline fun TIntList.mapIndexed(operation: (Int, Int) -> Int): TIntList {
    val result = TIntArrayList(size)
    val iterator = iterator()
    var index = 0
    while (iterator.hasNext()) {
        val element = iterator.next()
        result.add(operation(index, element))
        index++
    }

    return result
}

@Suppress("DuplicatedCode")
internal inline fun <T> Collection<T>.mapIndexedToTIntList(operation: (Int, T) -> Int): TIntList {
    val result = TIntArrayList(size)
    val iterator = iterator()
    var index = 0
    while (iterator.hasNext()) {
        val element = iterator.next()
        result.add(operation(index, element))
        index++
    }

    return result
}

internal inline fun <T> Collection<T>.mapToTIntList(operation: (T) -> Int): TIntList {
    val result = TIntArrayList(size)
    for (element in this) {
        result.add(operation(element))
    }

    return result
}

internal inline fun <T> TIntObjectMap<T>.forEach(crossinline operation: (Int, T) -> Unit) {
    forEachEntry { key, value ->
        operation(key, value)
        return@forEachEntry true
    }
}