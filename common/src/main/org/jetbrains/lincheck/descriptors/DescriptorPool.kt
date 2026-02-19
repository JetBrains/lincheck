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

/**
 * Pool for interning descriptors and providing id-based and key-based lookups.
 */
class DescriptorPool<D : Descriptor> {
    private val descriptors = mutableListOf<D?>()
    private val byKey = hashMapOf<Descriptor.Key, Int>()

    /**
     * @return descriptor by index [id] in the [descriptors] field.
     * @throws IllegalStateException if no descriptor with the specified id is present in the pool.
     */
    @Synchronized
    operator fun get(id: Int): D =
        descriptors[id].ensureNotNull {
            "Element $id is not found in pool"
        }

    /**
     * @return descriptor by its [key], or null if no descriptor with the specified key is present in the pool.
     */
    @Synchronized
    operator fun get(key: Descriptor.Key): D? =
        byKey[key]?.let { descriptors[it] }

    /**
     * @return id of the descriptor with the specified [key].
     * @throws IllegalStateException if no descriptor with the specified key is present in the pool.
     */
    @Synchronized
    fun getId(key: Descriptor.Key): Int =
        byKey[key].ensureNotNull {
            "No descriptor with key $key"
        }

    /**
     * @return true if a descriptor with the specified [key] is present in the pool, false otherwise.
     */
    @Synchronized
    operator fun contains(key: Descriptor.Key): Boolean =
        byKey.containsKey(key)

    /**
     * Registers [descriptor] and assigns a unique id if it is not yet present.
     * If a descriptor with the same key already exists, checks for equality
     * and returns existing id; throws if they differ.
     */
    @Synchronized
    fun register(descriptor: D): Int {
        val key = descriptor.key
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
    fun clear() {
        descriptors.clear()
        byKey.clear()
    }

    /**
     * Restores descriptor under a concrete [id].
     * Used by deserializers.
     */
    @Synchronized
    fun restore(id: Int, value: D) {
        check(id >= descriptors.size || descriptors[id] == null || descriptors[id] == value) {
            "Item with id $id is already present in pool and differs from $value"
        }
        descriptors.expandTo(id + 1, null)
        descriptors[id] = value
        byKey[value.key] = id
    }
}
