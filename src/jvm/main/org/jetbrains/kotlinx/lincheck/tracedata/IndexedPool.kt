/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.tracedata

internal class IndexedPool<T> {
    private val items = mutableListOf<T>()
    private val index = hashMapOf<T, Int>()

    @Synchronized
    operator fun get(id: Int): T = items[id]

    @Synchronized
    fun getOrCreateId(item: T): Int = index.getOrPut(item) {
        items.add(item)
        items.lastIndex
    }

    val content: List<T> = items

    @Synchronized
    fun clear() {
        items.clear()
        index.clear()
    }
}

internal fun <T> IndexedPool<T>.getInterned(item: T) = get(getOrCreateId(item))
