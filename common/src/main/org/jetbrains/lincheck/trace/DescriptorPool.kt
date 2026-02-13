/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

import org.jetbrains.lincheck.descriptors.Descriptor

/**
 * Pool for interning descriptors and providing id-based and key-based lookups.
 *
 * Thread-safety note: for now it uses a regular mutable list and map.
 * Reads are not guaranteed to be race-free. The array can be replaced with
 * an atomic array later to guarantee thread-safe reads.
 */
class DescriptorPool<D : Descriptor> {
    // TODO: make _descriptors threads for direct retrieval
    private val _descriptors = mutableListOf<D?>()
    val descriptors: List<D?> get() = _descriptors
    private val byKey = hashMapOf<Descriptor.Key, Int>()

    @Synchronized
    operator fun get(id: Int): D {
        val item = _descriptors[id]
        check(item != null) { "Element $id is not found in pool" }
        return item
    }

    @Synchronized
    operator fun get(key: Descriptor.Key): D? = byKey[key]?.let { _descriptors[it] }

    @Synchronized
    operator fun contains(key: Descriptor.Key): Boolean = byKey.containsKey(key)

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
            check(current != null) { "Invariant violation: id $existing has null item for key $key" }
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

    @Synchronized
    fun clear() {
        _descriptors.clear()
        byKey.clear()
    }

    /** Restores descriptor under a concrete [id]. Used by deserializers. */
    @Synchronized
    fun restore(id: Int, value: D) {
        check(id >= _descriptors.size || _descriptors[id] == null || _descriptors[id] == value) {
            "Item with id $id is already present in pool and differs from $value"
        }
        while (_descriptors.size <= id) _descriptors.add(null)
        _descriptors[id] = value
        value.id = id
        byKey[value.key] = id
    }
}
