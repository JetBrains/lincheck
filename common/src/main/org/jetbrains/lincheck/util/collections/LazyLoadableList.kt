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

/**
 * A fixed-size [List] view with lazy loading semantics:
 * elements are loaded on demand on first access,
 * and the user has explicit control over the loading/unloading lifecycle.
 */
interface LazyLoadableList<out T> : List<T> {
    /**
     * Returns `true` if the element at [index] is currently loaded.
     */
    fun isLoaded(index: Int): Boolean

    /**
     * Loads the element at [index] if it is not loaded yet.
     */
    fun load(index: Int)

    /**
     * Unloads the element at [index]; a subsequent [get] will load it again.
     */
    fun unload(index: Int)

    /**
     * Loads all elements in one batch.
     */
    fun loadAll() {
        for (index in indices) load(index)
    }

    /**
     * Unloads all elements.
     */
    fun unloadAll() {
        for (index in indices) unload(index)
    }

    companion object {
        /**
         * Default cache factory for [LazyLoadableList]s.
         * Creates a regular mutable list filled with `null`s.
         */
        val defaultCacheFactory: (Int) -> MutableList<Any?> = { size -> MutableList(size) { null } }
    }
}

/**
 * Creates a [LazyLoadableList] that loads elements one by one.
 *
 * Queries [load] for an element on first access and caches the result,
 * with the ability to later unload and reload it.
 *
 * @param size the fixed number of elements in the list.
 * @param load computes the element at the given index; invoked lazily on first access and after unloading.
 * @param unload optional hook invoked when a previously loaded element is unloaded,
 *   allowing the underlying resource to be released.
 * @param cacheFactory creates the backing cache list of the requested size;
 *   allows configuring the cache implementation.
 */
fun <T> LazyLoadableList(
    size: Int,
    load: (Int) -> T,
    unload: (Int) -> Unit = {},
    cacheFactory: (Int) -> MutableList<Any?> = LazyLoadableList.defaultCacheFactory,
): LazyLoadableList<T> = LazyLoadableListImpl(
    size = size,
    load = load,
    unload = unload,
    cacheFactory = cacheFactory,
)

/**
 * Creates a [LazyLoadableList] that loads elements one by one, discovering its size lazily.
 *
 * The size is computed and memoized on the first access to either the size or any element,
 * so creating the list costs nothing until it is actually queried.
 *
 * @param computeSize computes the number of elements; invoked once, on the first size or element access.
 * @param load computes the element at the given index; invoked lazily on first access and after unloading.
 * @param unload optional hook invoked when a previously loaded element is unloaded,
 *   allowing the underlying resource to be released.
 * @param cacheFactory creates the backing cache list of the requested size;
 *   allows configuring the cache implementation.
 */
fun <T> LazyLoadableList(
    computeSize: () -> Int,
    load: (Int) -> T,
    unload: (Int) -> Unit = {},
    cacheFactory: (Int) -> MutableList<Any?> = LazyLoadableList.defaultCacheFactory,
): LazyLoadableList<T> = LazyLoadableListImpl(
    size = null,
    computeSize = computeSize,
    load = load,
    unload = unload,
    cacheFactory = cacheFactory,
)

/**
 * Creates a [LazyLoadableList] that loads all elements in one batch.
 *
 * The whole batch is loaded lazily on the first access, which also discovers the list size;
 * after [LazyLoadableList.unloadAll], accessing any element reloads the whole batch again.
 * Unloading a single element drops only that element without releasing the batch resource;
 * a subsequent access reloads the whole batch.
 *
 * @param loadAll produces all elements at once; must always return the same number of elements.
 * @param unloadAll optional hook invoked when the loaded batch is dropped,
 *   allowing the underlying resource to be released.
 * @param computeIsEmpty optional emptiness computation used by [List.isEmpty]
 *   while the size is not yet discovered, so that the check does not load the batch;
 *   must agree with the number of elements [loadAll] produces.
 * @param cacheFactory creates the backing cache list of the requested size;
 *   allows configuring the cache implementation.
 */
fun <T> LazyLoadableList(
    loadAll: () -> List<T>,
    unloadAll: () -> Unit = {},
    computeIsEmpty: (() -> Boolean)? = null,
    cacheFactory: (Int) -> MutableList<Any?> = LazyLoadableList.defaultCacheFactory,
): LazyLoadableList<T> = LazyLoadableListImpl(
    size = null,
    loadAll = loadAll,
    unloadAll = unloadAll,
    computeIsEmpty = computeIsEmpty,
    cacheFactory = cacheFactory,
)

/**
 * Default [LazyLoadableList] implementation backed by a fixed-size cache
 * with pluggable per-element ([load]/[unload]) and whole-list ([loadAll]/[unloadAll]) strategies.
 *
 * When a strategy is absent, the corresponding operations fall back to the other one.
 * When [size] is `null`, it is discovered lazily — via [computeSize] if provided,
 * or together with the first [loadAll] batch — and the cache is created only at that point.
 */
private class LazyLoadableListImpl<T>(
    size: Int?,
    private val computeSize: (() -> Int)? = null,
    private val load: ((Int) -> T)? = null,
    private val unload: ((Int) -> Unit)? = null,
    private val loadAll: (() -> List<T>)? = null,
    private val unloadAll: (() -> Unit)? = null,
    private val computeIsEmpty: (() -> Boolean)? = null,
    private val cacheFactory: (Int) -> MutableList<Any?>,
) : AbstractList<T>(), LazyLoadableList<T> {

    init {
        require(load != null || loadAll != null) { "Either load or loadAll must be provided" }
        require(size != null || computeSize != null || loadAll != null) {
            "Either size, computeSize, or loadAll must be provided"
        }
    }

    private var cache: MutableList<Any?>? =
        if (size != null) createCache(size) else null

    override val size: Int
        get() = ensureCache().size

    // While the size is not yet discovered, an explicit emptiness computation
    // (when provided) answers without loading the batch.
    override fun isEmpty(): Boolean {
        cache?.let { return it.isEmpty() }
        computeIsEmpty?.let { return it.invoke() }
        return super.isEmpty()
    }

    private fun createCache(size: Int): MutableList<Any?> {
        val cache = cacheFactory.invoke(size)
        require(cache.size == size) {
            "Cache factory created a list of invalid size: expected $size, got ${cache.size}"
        }
        cache.fill(TOMBSTONE)
        return cache
    }

    // The size may be discoverable only lazily: via computeSize or by loading the first batch.
    // Due to the constructor invariants, size (cache != null), computeSize, or loadAll must be provided.
    private fun ensureCache(): MutableList<Any?> {
        cache?.let { return it }
        computeSize?.let { return createCache(it.invoke()).also { created -> cache = created } }
        loadAll()
        return cache!!
    }

    override fun get(index: Int): T {
        load(index)
        @Suppress("UNCHECKED_CAST")
        return cache!![index] as T
    }

    override fun load(index: Int) {
        val cache = ensureCache()
        if (cache[index] === TOMBSTONE) {
            val load = this.load
            if (load != null) {
                cache[index] = load.invoke(index)
            } else {
                loadAll()
            }
        }
    }

    override fun isLoaded(index: Int): Boolean {
        val cache = this.cache ?: return false
        return cache[index] !== TOMBSTONE
    }

    override fun unload(index: Int) {
        val cache = this.cache ?: return
        if (cache[index] === TOMBSTONE) return
        cache[index] = TOMBSTONE
        unload?.invoke(index)
    }

    override fun loadAll() {
        val cache = this.cache
        if (cache != null && cache.none { it === TOMBSTONE }) return

        val loadAll = this.loadAll ?: return super.loadAll()
        val batch = loadAll.invoke()
        val target = cache ?: createCache(batch.size).also { this.cache = it }
        check(batch.size == target.size) {
            "Expected a batch of ${target.size} elements, got ${batch.size}"
        }
        for (index in target.indices) {
            target[index] = batch[index]
        }
    }

    override fun unloadAll() {
        val cache = this.cache ?: return
        if (cache.all { it === TOMBSTONE }) return

        val unloadAll = this.unloadAll ?: return super.unloadAll()
        cache.fill(TOMBSTONE)
        unloadAll.invoke()
    }

    // Synthetic object to mark absence of an element;
    // distinguishes "not loaded" from a loaded `null`.
    private object TOMBSTONE
}
