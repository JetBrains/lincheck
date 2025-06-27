/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

internal class IndexedPool<T> {
    private val items = mutableListOf<T?>()
    private val index = hashMapOf<T, Int>()

    @Synchronized
    operator fun get(id: Int): T {
        val item = items[id]
        check(item != null) { "Element $id is not found in pool"}
        return item
    }

    @Synchronized
    fun getOrCreateId(item: T): Int = index.getOrPut(item) {
        items.add(item)
        items.lastIndex
    }

    val content: List<T?> get() = items

    @Synchronized
    fun clear() {
        items.clear()
        index.clear()
    }

    @Synchronized
    internal fun restore(id: Int, value: T) {
        check (id >= items.size || items[id] == null) {
            "Item with id $id is already present in poo"
        }
        while (items.size <= id) {
            items.add(null)
        }
        items[id] = value
        index[value] = id
    }
}

internal fun <T> IndexedPool<T>.getInterned(item: T) = get(getOrCreateId(item))
