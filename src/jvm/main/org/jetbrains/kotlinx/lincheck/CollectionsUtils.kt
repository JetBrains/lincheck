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

import it.unimi.dsi.fastutil.Hash
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenCustomHashMap
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.ObjectArrayList
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.function.Function

internal typealias MutableIntList = IntArrayList
internal typealias MutableObjectList<T> = ObjectArrayList<T>
internal typealias MutableIntToObjectMap<V> = Int2ObjectOpenHashMap<V>
internal typealias MutableObjectToObjectMap<K, V> = Object2ObjectOpenHashMap<K, V>
internal typealias MutableIntToIntMap = Int2IntOpenHashMap
internal typealias MutableIntSet = IntOpenHashSet

internal fun mutableIntSetOf(vararg ints: Int): MutableIntSet = MutableIntSet(ints)

internal fun mutableIntListOf(vararg ints: Int): MutableIntList = MutableIntList(ints)

internal fun <T> mutableObjectListOf(vararg ints: T): MutableObjectList<T> = MutableObjectList(ints)

internal fun <K, V> mutableObjectToObjectMapOf(vararg pairs: Pair<K, V>): MutableObjectToObjectMap<K, V> {
    val result = MutableObjectToObjectMap<K, V>()
    pairs.forEach { (key, value) -> result[key] = value }
    return result
}


internal fun <V> mutableIntToObjectMapOf(vararg pairs: Pair<Int, V>): MutableIntToObjectMap<V> {
    val result = MutableIntToObjectMap<V>()
    pairs.forEach { (key, value) -> result[key] = value }
    return result
}

internal fun mutableIntToIntMapOf(vararg pairs: Pair<Int, Int>): MutableIntToIntMap {
    val result = MutableIntToIntMap()
    pairs.forEach { (key, value) -> result[key] = value }
    return result
}

/**
 * Does the same as [java.util.Map.computeIfAbsent].
 *
 * Exists as there is an ambiguity when using [java.util.Map.computeIfAbsent] in Kotlin, because Fastutil
 * library provides [it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap.computeIfAbsent] method with the same name.
 */
internal fun <K, V> MutableMap<K, V>.getOrComputeIfAbsent(key: K, mappingFunction: Function<K, V>): V = computeIfAbsent(key, mappingFunction)

internal class ObjectToObjectIdentityHashMap<K, V> : Object2ObjectOpenCustomHashMap<K, V>(IdentityStrategy<K>())

private class IdentityStrategy<T> : Hash.Strategy<T> {
    override fun hashCode(o: T): Int {
        return System.identityHashCode(o)
    }

    override fun equals(a: T, b: T): Boolean {
        return a === b
    }
}

internal class ObjectToObjectWeakHashMap<K, V: Any> {
    private val queue = ReferenceQueue<K>()
    private val internalMap = Object2ObjectOpenHashMap<WeakKey<K>, V>()

    val size: Int get() = internalMap.size
    operator fun get(key: K): V? {
        cleanUp()
        return internalMap[WeakKey(key)]
    }

    operator fun set(key: K, value: V): V? {
        cleanUp()
        return internalMap.put(WeakKey(key, queue), value)
    }

    fun computeIfAbsent(key: K, mappingFunction: (K) -> V): V {
        cleanUp()
        val weakKey = WeakKey(key, queue)
        var value = internalMap[weakKey]
        if (value == null) {
            value = mappingFunction(key)
            internalMap[weakKey] = value
        }
        return value
    }

    @Suppress("ControlFlowWithEmptyBody")
    fun clear() {
        internalMap.clear()
        while (queue.poll() != null) {
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun cleanUp() {
        val key = queue.poll() as? WeakKey<K> ?: return
        internalMap.remove(key)
    }

    private class WeakKey<T>(key: T, queue: ReferenceQueue<T>? = null) : WeakReference<T>(key, queue) {
        private val hash = key.hashCode()

        override fun hashCode() = hash

        override fun equals(other: Any?) = when {
            this === other -> true
            other !is WeakKey<*> -> false
            get() == other.get() -> true
            else -> false
        }
    }
}
