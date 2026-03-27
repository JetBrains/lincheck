/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.descriptors

import org.jetbrains.lincheck.util.ensureNotNull
import org.jetbrains.lincheck.util.expandTo

interface DescriptorPool<K, D> {
    operator fun get(id: Int): D
    operator fun get(key: K): D?
    fun getId(key: K): Int
    operator fun contains(key: K): Boolean

    fun restore(id: Int, descriptor: D)
    fun clear()
    fun register(descriptor: D): Int
}

/**
 * Pool for interning descriptors and providing id-based and key-based lookups.
 */
private abstract class AbstractDescriptorPool<K, D> : DescriptorPool<K, D> {
    private val descriptors = mutableListOf<D?>()
    private val byKey = hashMapOf<K, Int>()

    /**
     * @return descriptor by index [id] in the [descriptors] field.
     * @throws IllegalStateException if no descriptor with the specified id is present in the pool.
     */
    @Synchronized
    override operator fun get(id: Int): D =
        descriptors[id].ensureNotNull {
            "Element $id is not found in pool"
        }

    /**
     * @return descriptor by its [key], or null if no descriptor with the specified key is present in the pool.
     */
    @Synchronized
    override operator fun get(key: K): D? =
        byKey[key]?.let { descriptors[it] }

    /**
     * @return id of the descriptor with the specified [key],
     *   or [Descriptor.INVALID_ID] if no descriptor with the specified key is present in the pool.
     */
    @Synchronized
    override fun getId(key: K): Int =
        byKey[key] ?: Descriptor.INVALID_ID

    /**
     * @return true if a descriptor with the specified [key] is present in the pool, false otherwise.
     */
    @Synchronized
    override operator fun contains(key: K): Boolean =
        byKey.containsKey(key)

    /**
     * Registers [descriptor] and assigns a unique id if it is not yet present.
     * If a descriptor with the same key already exists, checks for equality
     * and returns existing id; throws if they differ.
     */
    @Synchronized
    protected fun registerImpl(key: K, descriptor: D): Int {
        val existing = byKey[key]
        if (existing != null) {
            val current = descriptors[existing]
            check(current != null) {
                "Invariant violation: id $existing has null item for key $key"
            }
            check(current == descriptor) {
                "Attempt to register a different descriptor for the same key: old=$current, new=$descriptor"
            }
            return existing
        }
        descriptors.add(descriptor)
        val id = descriptors.lastIndex
        byKey[key] = id
        return id
    }

    /**
     * Clears the pool. Used when [org.jetbrains.lincheck.trace.TraceContext] is cleared.
     */
    @Synchronized
    override fun clear() {
        descriptors.clear()
        byKey.clear()
    }

    /**
     * Restores descriptor under a concrete [id].
     * Used by deserializers.
     */
    @Synchronized
    protected fun restoreImpl(id: Int, key: K, value: D) {
        check(id >= descriptors.size || descriptors[id] == null || descriptors[id] == value) {
            "Item with id $id is already present in pool and differs from $value"
        }
        descriptors.expandTo(id + 1, null)
        descriptors[id] = value
        byKey[key] = id
    }
}

fun <D : Descriptor> DescriptorPool(): DescriptorPool<Descriptor.Key, D> = DescriptorPoolImpl()

private class DescriptorPoolImpl<D : Descriptor> : AbstractDescriptorPool<Descriptor.Key, D>() {
    override fun register(descriptor: D) = registerImpl(descriptor.key, descriptor)
    override fun restore(id: Int, descriptor: D) = restoreImpl(id, descriptor.key, descriptor)
}

fun StringPool(): DescriptorPool<String, String> = StringPoolImpl()

private class StringPoolImpl : AbstractDescriptorPool<String, String>() {
    override fun register(descriptor: String) = registerImpl(descriptor, descriptor)
    override fun restore(id: Int, descriptor: String) = restoreImpl(id, descriptor, descriptor)
}