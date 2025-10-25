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

import kotlin.math.min

private const val CHUNK_SIZE = 1024
private const val CHUNK_SHIFT = 10 // log2(CHUNK_SIZE)
private const val CHUNK_MASK = (1 shl CHUNK_SHIFT) - 1

class ChunkedList<T>: List<T?>, RandomAccess {
    private var totalSize: Int = 0
    private val chunks: MutableList<MutableList<T?>?> = ArrayList()

    // Add an element to the end
    fun add(element: T?): Boolean {
        if (element == null) {
            // Don't store tail nulls, synthesize them as needed
            totalSize += 1
            return true
        }
        // Ok, we need to add it
        val chunk = totalSize shr CHUNK_SHIFT
        val idx = totalSize and CHUNK_MASK
        val ch = prepareChunk(chunk, idx)
        ch[idx] = element
        totalSize += 1

        return true
    }

    /**
     * Remove all elements and set the size to 0.
     */
    fun clear() {
        chunks.clear()
        totalSize = 0
    }

    /**
     * Replace all elements with `null` but remember number of elements.
     */
    fun forgetAll() {
        chunks.clear()
    }

    /**
     * Replace a stored element with `null`.
     */
    fun forget(index: Int): Unit = set(index, null)

    /**
     * Replace a range of stored elements with `null`s.
     */
    fun forget(from: Int, to: Int) {
        checkRange(from)
        checkRange(to - 1)

        val fromChunk = from shr CHUNK_SHIFT
        val fromIdx = from and CHUNK_MASK

        val toChunk = (to - 1) shr CHUNK_SHIFT
        val toIdx = (to - 1) and CHUNK_MASK

        // Check if we remove from the very first element in the first chunk
        val firstEmptyChunk = if (fromIdx == 0) fromChunk else fromChunk + 1
        // Check if we remove till the very last element in the last chunk
        val lastEmptyChunk = if (toIdx + 1 >= (chunks[toChunk]?.size ?: 0)) toChunk else toChunk - 1

        for (chunk in firstEmptyChunk .. lastEmptyChunk) {
            chunks[chunk] = null
        }

        if (fromChunk == toChunk) {
            forget(fromChunk, fromIdx, toIdx)
        } else {
            forget(fromChunk, fromIdx, CHUNK_SIZE)
            forget(toChunk, 0, toIdx)
        }

        cleanupTail()
    }

    private fun forget(chunk: Int, fromIdx: Int, toIdx: Int) {
        val ch = chunks[chunk] ?: return
        ch.subList(fromIdx, min(ch.size, toIdx)).fill(null)
        if (ch.all { it == null }) {
            chunks[chunk] = null
        }
    }

    operator fun set(index: Int, element: T?) {
        checkRange(index)
        val chunk = index shr CHUNK_SHIFT
        val idx = index and CHUNK_MASK

        // Don't create a chunk to put null
        if (element == null && (chunk >= chunks.size || chunks[chunk] == null)) {
            return
        }

        val ch = prepareChunk(chunk, idx)
        ch[idx] = element

        // Maybe we can clear this chunk
        if (element == null && ch.all { it == null }) {
            chunks[chunk] = null
        }
        cleanupTail()
    }

    override val size: Int get() = totalSize

    override fun isEmpty(): Boolean = totalSize == 0

    override fun contains(element: T?): Boolean = indexOf(element) >= 0

    override fun iterator(): Iterator<T?> = ChunkedListIterator(0)

    override fun containsAll(elements: Collection<T?>): Boolean {
        elements.forEach {
            if (indexOf(it) < 0) return false
        }
        return true
    }

    override fun get(index: Int): T? {
        checkRange(index)
        val chunk = index shr CHUNK_SHIFT
        val idx = index and CHUNK_MASK
        if (chunk >= chunks.size) {
            return null
        }
        val ch = chunks[chunk]
        if (ch == null || idx >= ch.size) {
            return null
        }
        return ch[idx]
    }

    override fun indexOf(element: T?): Int {
        val totalChunks = (totalSize shr CHUNK_SHIFT)
        for (chunk in 0 .. totalChunks) {
            val ch = chunks[chunk] ?: continue
            for (idx in 0 ..< ch.size) {
                if (ch[idx] == element) {
                    return (chunk shl CHUNK_SHIFT) + idx
                }
            }
        }
        return -1
    }

    override fun lastIndexOf(element: T?): Int {
        val totalChunks = (totalSize shr CHUNK_SHIFT)
        for (chunk in totalChunks downTo  0) {
            val ch = chunks[chunk] ?: continue
            for (idx in ch.size - 1 downTo 0) {
                if (ch[idx] == element) {
                    return (chunk shl CHUNK_SHIFT) + idx
                }
            }
        }
        return -1
    }

    override fun listIterator(): ListIterator<T?> = ChunkedListIterator(0)

    override fun listIterator(index: Int): ListIterator<T?> {
        checkRange(index)
        return ChunkedListIterator(index)
    }

    override fun subList(fromIndex: Int, toIndex: Int): List<T?> {
        checkRange(fromIndex)
        if (toIndex !in 0.. totalSize)
            throw IndexOutOfBoundsException("Index: $toIndex, Size: $totalSize")
        require(fromIndex <= toIndex) { "fromIndex: $fromIndex > toIndex: $toIndex" }

        return object : AbstractList<T?>(), RandomAccess {
            override val size: Int
                get() = toIndex - fromIndex

            override fun get(index: Int): T? = this@ChunkedList.get(index + fromIndex)
        }
    }

    private fun checkRange(index: Int) {
        if (index !in 0..<totalSize)
            throw IndexOutOfBoundsException("Index: $index, Size: $totalSize")
    }

    private fun prepareChunk(chunk: Int, idx: Int): MutableList<T?> {
        while (chunk >= chunks.size) {
            chunks.add(null)
        }
        if (chunks[chunk] == null) {
            chunks[chunk] = ArrayList()
        }
        val ch = chunks[chunk]!!
        while (idx >= ch.size) {
            ch.add(null)
        }
        return ch
    }

    private fun cleanupTail() {
        while (chunks.isNotEmpty() && chunks.last() == null) {
            chunks.removeLast()
        }
    }

    private inner class ChunkedListIterator(
        var idx: Int
    ): ListIterator<T?> {
        override fun next(): T? {
            if (idx !in 0..<totalSize)
                throw NoSuchElementException("Index: $idx, Size: $totalSize")
            return get(idx++)
        }

        override fun hasNext(): Boolean = idx < totalSize

        override fun hasPrevious(): Boolean = idx > 0

        override fun previous(): T? {
            if (idx < 1)
                throw NoSuchElementException("Index: $idx, Size: $totalSize")
            return get(--idx)
        }

        override fun nextIndex(): Int = idx

        override fun previousIndex(): Int = idx - 1
    }
}