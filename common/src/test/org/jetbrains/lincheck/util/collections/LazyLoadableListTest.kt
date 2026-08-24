/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2026 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util.collections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LazyLoadableListTest {

    @Test
    fun `get loads lazily and caches the result`() {
        var calls = 0
        val list = LazyLoadableList(3, load = { index -> calls++; index * 10 })

        assertEquals(0, calls)
        assertEquals(10, list[1])
        assertEquals(1, calls)
        assertEquals(10, list[1])
        assertEquals(1, calls)
    }

    @Test
    fun `loaded null is cached and not confused with an unloaded element`() {
        var calls = 0
        val list = LazyLoadableList<Int?>(1, load = { calls++; null })

        assertFalse(list.isLoaded(0))
        assertNull(list[0])
        assertNull(list[0])
        assertEquals(1, calls)
        assertTrue(list.isLoaded(0))
    }

    @Test
    fun `unload calls the unload hook and allows reloading`() {
        val loaded = mutableListOf<Int>()
        val unloaded = mutableListOf<Int>()
        val list = LazyLoadableList(
            size = 2,
            load = { index -> loaded.add(index); index },
            unload = { index -> unloaded.add(index) },
        )

        list.load(0)
        assertTrue(list.isLoaded(0))
        assertEquals(listOf(0), loaded)

        list.unload(0)
        assertFalse(list.isLoaded(0))
        assertEquals(listOf(0), unloaded)

        assertEquals(0, list[0])
        assertEquals(listOf(0, 0), loaded)
    }

    @Test
    fun `unload of a not loaded element is a no-op`() {
        var unloadCalls = 0
        val list = LazyLoadableList(1, load = { it }, unload = { unloadCalls++ })

        list.unload(0)
        assertEquals(0, unloadCalls)
    }

    @Test
    fun `loadAll and unloadAll`() {
        var calls = 0
        val list = LazyLoadableList(3, load = { index -> calls++; index })

        list.loadAll()
        assertEquals(3, calls)
        assertTrue((0 ..< 3).all { list.isLoaded(it) })

        list.unloadAll()
        assertTrue((0 ..< 3).none { list.isLoaded(it) })

        list.loadAll()
        assertEquals(6, calls)
    }

    @Test
    fun `standard List API works`() {
        val list = LazyLoadableList(4, load = { index -> index * index })

        assertEquals(4, list.size)
        assertEquals(listOf(0, 1, 4, 9), list.toList())
        assertTrue(list.contains(4))
        assertEquals(3, list.indexOf(9))
    }

    @Test
    fun `out of bounds access throws`() {
        val list = LazyLoadableList(2, load = { it })

        assertThrows(IndexOutOfBoundsException::class.java) { list[2] }
        assertThrows(IndexOutOfBoundsException::class.java) { list[-1] }
    }

    @Test
    fun `custom cache factory backs the list`() {
        val backing = mutableListOf<Any?>(null, null)
        val list = LazyLoadableList(size = 2, load = { it * 10 }, cacheFactory = { backing })

        assertEquals(10, list[1])
        assertEquals(10, backing[1])
    }

    @Test
    fun `cache factory creating a list of invalid size fails`() {
        assertThrows(IllegalArgumentException::class.java) {
            LazyLoadableList(size = 3, load = { it }, cacheFactory = { mutableListOf() })
        }
    }

    @Test
    fun `size is computed lazily and memoized`() {
        var sizeCalls = 0
        val list = LazyLoadableList(computeSize = { sizeCalls++; 3 }, load = { it * 10 })

        assertEquals(0, sizeCalls)
        assertEquals(3, list.size)
        assertEquals(3, list.size)
        assertEquals(1, sizeCalls)
        assertFalse(list.isLoaded(0)) // size discovery materializes no elements
    }

    @Test
    fun `element access discovers the size`() {
        var sizeCalls = 0
        val list = LazyLoadableList(computeSize = { sizeCalls++; 2 }, load = { it * 10 })

        assertEquals(10, list[1])
        assertEquals(1, sizeCalls)
        assertEquals(2, list.size)
        assertEquals(1, sizeCalls)
    }
}

class BatchedLazyLoadableListTest {

    @Test
    fun `the whole batch is loaded lazily on first access`() {
        var calls = 0
        val list = LazyLoadableList(loadAll = { calls++; listOf(10, 20, 30) })

        assertEquals(0, calls)
        assertEquals(3, list.size) // size discovery triggers the batch load
        assertEquals(1, calls)
        assertTrue((0 ..< 3).all { list.isLoaded(it) })
        assertEquals(listOf(10, 20, 30), list.toList())
        assertEquals(1, calls)
    }

    @Test
    fun `unloadAll drops the whole batch and access reloads it`() {
        var calls = 0
        var unloadCalls = 0
        val list = LazyLoadableList(
            loadAll = { calls++; listOf(1, 2) },
            unloadAll = { unloadCalls++ },
        )

        // Unloading before anything is loaded is a no-op.
        list.unloadAll()
        assertEquals(0, unloadCalls)

        list.loadAll()
        assertEquals(1, calls)

        list.unloadAll()
        assertEquals(1, unloadCalls)
        assertFalse(list.isLoaded(0))
        assertFalse(list.isLoaded(1))

        // Unloading an already unloaded batch is a no-op.
        list.unloadAll()
        assertEquals(1, unloadCalls)

        assertEquals(2, list[1]) // any access reloads the whole batch
        assertEquals(2, calls)
        assertTrue(list.isLoaded(0))
    }

    @Test
    fun `unload of a single element does not release the batch`() {
        var calls = 0
        var unloadCalls = 0
        val list = LazyLoadableList(
            loadAll = { calls++; listOf(1, 2) },
            unloadAll = { unloadCalls++ },
        )

        list.loadAll()
        list.unload(0)
        assertEquals(0, unloadCalls)
        assertFalse(list.isLoaded(0))
        assertTrue(list.isLoaded(1))

        assertEquals(1, list[0]) // access reloads the whole batch
        assertEquals(2, calls)
    }

    @Test
    fun `isEmpty is answered by the provided computation without loading the batch`() {
        var calls = 0
        var isEmptyCalls = 0
        val list = LazyLoadableList(
            loadAll = { calls++; listOf(1, 2) },
            computeIsEmpty = { isEmptyCalls++; false },
        )

        assertFalse(list.isEmpty())
        assertTrue(list.isNotEmpty())
        assertEquals(2, isEmptyCalls)
        assertEquals(0, calls)

        // Once the size is discovered, isEmpty is answered by the cache instead.
        list.loadAll()
        assertFalse(list.isEmpty())
        assertEquals(2, isEmptyCalls)
    }

    @Test
    fun `isEmpty without the provided computation loads the batch`() {
        var calls = 0
        val list = LazyLoadableList(loadAll = { calls++; emptyList<Int>() })

        assertTrue(list.isEmpty())
        assertEquals(1, calls)
    }

    @Test
    fun `reloading with a different batch size fails`() {
        var first = true
        val list = LazyLoadableList(loadAll = {
            if (first) { first = false; listOf(1, 2) } else listOf(1)
        })

        list.loadAll()
        list.unloadAll()
        assertThrows(IllegalStateException::class.java) { list[0] }
    }

    @Test
    fun `cache factory receives the discovered batch size`() {
        var requestedSize = -1
        val list = LazyLoadableList(
            loadAll = { listOf(1, 2, 3) },
            cacheFactory = { size -> requestedSize = size; MutableList(size) { null } },
        )

        assertEquals(-1, requestedSize) // cache is not created until the first load
        assertEquals(2, list[1])
        assertEquals(3, requestedSize)
    }
}
