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

import org.jetbrains.lincheck.util.*
import org.jetbrains.lincheck.util.collections.*

/**
 * Pool for interning descriptors and providing id-based and key-based lookups.
 *
 * Note: we do not scope the [D] generic parameter to [Descriptor] interface because
 * we want to allow implementors of [DescriptorPool] to use pure value objects without
 * putting them in [Descriptor] wrapper. This results in better memory utilization in situations
 * when descriptor would only contain a single field. For example, this optimization is done for [StringPool].
 */
interface DescriptorPool<K, D> {
    /**
     * @return descriptor by index [id].
     * @throws IllegalStateException if no descriptor with the specified id is present in the pool.
     */
    operator fun get(id: Int): D

    /**
     * @return descriptor by index [id], or null if no descriptor with the specified id is present in the pool.
     */
    fun getOrNull(id: Int): D?

    /**
     * @return descriptor by its [key], or null if no descriptor with the specified key is present in the pool.
     */
    operator fun get(key: K): D?

    /**
     * @return id of the descriptor with the specified [key],
     *   or [Descriptor.INVALID_ID] if no descriptor with the specified key is present in the pool.
     */
    fun getId(key: K): Int

    /**
     * @return true if a descriptor with the specified [key] is present in the pool, false otherwise.
     */
    operator fun contains(key: K): Boolean

    /**
     * Registers [descriptor] and assigns a unique id if it is not yet present.
     * If a descriptor with the same key already exists, checks for equality
     * and returns existing id; throws if they differ.
     */
    fun register(descriptor: D): Int

    /**
     * Clears the pool. Used when [org.jetbrains.lincheck.trace.TraceContext] is cleared.
     */
    fun clear()

    /**
     * Restores descriptor under a concrete [id].
     * Used by deserializers.
     */
    fun restore(id: Int, descriptor: D)
}

private abstract class AbstractDescriptorPool<K, D> : DescriptorPool<K, D> {
    private val descriptors = mutableListOf<D?>()
    private val byKey = hashMapOf<K, Int>()

    @Synchronized
    override operator fun get(id: Int): D =
        descriptors[id].ensureNotNull {
            "Element $id is not found in pool"
        }

    @Synchronized
    override fun getOrNull(id: Int): D? =
        descriptors.getOrNull(id)

    @Synchronized
    override operator fun get(key: K): D? =
        byKey[key]?.let { descriptors[it] }

    @Synchronized
    override fun getId(key: K): Int =
        byKey[key] ?: Descriptor.INVALID_ID

    @Synchronized
    override operator fun contains(key: K): Boolean =
        byKey.containsKey(key)

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

    @Synchronized
    override fun clear() {
        descriptors.clear()
        byKey.clear()
    }

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

fun CodeLocationPool(): DescriptorPool<CodeLocation, CodeLocation> = CodeLocationPoolImpl()

private class CodeLocationPoolImpl : AbstractDescriptorPool<CodeLocation, CodeLocation>() {
    override fun register(descriptor: CodeLocation) = registerImpl(descriptor, descriptor)
    override fun restore(id: Int, descriptor: CodeLocation) = restoreImpl(id, descriptor, descriptor)
}

fun AccessPathPool(): DescriptorPool<AccessPath, AccessPath> = AccessPathPoolImpl()

private class AccessPathPoolImpl : AbstractDescriptorPool<AccessPath, AccessPath>() {
    override fun register(descriptor: AccessPath) = registerImpl(descriptor, descriptor)
    override fun restore(id: Int, descriptor: AccessPath) = restoreImpl(id, descriptor, descriptor)
}