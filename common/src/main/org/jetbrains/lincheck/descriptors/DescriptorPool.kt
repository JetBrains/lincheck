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

import org.jetbrains.lincheck.util.ConcurrentSingleWriterList
import org.jetbrains.lincheck.util.ensureNotNull
import org.jetbrains.lincheck.util.expandTo

/**
 * Pool for interning descriptors and providing id-based and key-based lookups.
 *
 * Thread-safety note: for now it uses a regular mutable list and map.
 * Reads are not guaranteed to be race-free. The array can be replaced with
 * an atomic array later to guarantee thread-safe reads.
 */
class DescriptorPool<D : Descriptor> {
    // because `_descriptors` are exposed for reading, we use thread-safe list implementation,
    // which does not allow read-write races between our write-code and user's read-code
    private val _descriptors = ConcurrentSingleWriterList<D?>()

    /**
     * List of all descriptors in the current pool.
     */
    val descriptors: List<D?> get() = _descriptors

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
        byKey[key]?.let { _descriptors[it] }

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
            val current = _descriptors[existing]
            check(current != null) {
                "Invariant violation: id $existing has null item for key $key"
            }
            check(current == descriptor) {
                "Attempt to register a different descriptor for the same key: old=$current, new=$descriptor"
            }
            return existing
        }
        _descriptors.add(descriptor)
        val id = _descriptors.lastIndex
        descriptor.id = id
        byKey[key] = id
        return id
    }

    /**
     * Clears the pool. Used when [org.jetbrains.lincheck.trace.TraceContext] is cleared.
     */
    @Synchronized
    fun clear() {
        _descriptors.clear()
        byKey.clear()
    }

    /**
     * Restores descriptor under a concrete [id].
     * Used by deserializers.
     */
    @Synchronized
    fun restore(id: Int, value: D) {
        check(id >= _descriptors.size || _descriptors[id] == null || _descriptors[id] == value) {
            "Item with id $id is already present in pool and differs from $value"
        }
        _descriptors.expandTo(id + 1, null)
        _descriptors[id] = value
        value.id = id
        byKey[value.key] = id
    }
}
