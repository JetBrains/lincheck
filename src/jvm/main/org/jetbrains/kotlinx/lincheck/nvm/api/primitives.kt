/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */
@file:Suppress("unused")

package org.jetbrains.kotlinx.lincheck.nvm.api

import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.nvm.NVMStateHolder


/**
 * This primitive is emulating NVM by separate storage of persisted and unpersisted values.
 *
 * A user may persist a value by calling [flush] method. Also, a value may be flushed randomly be a system.
 * While a system may reset an unpersisted value to the last persisted state in case of a crash.
 */
abstract class AbstractNonVolatilePrimitive {
    /**
     * Move value to a persisted storage, do not operate with a cache.
     * After a successful invocation of this method the value can be seen even after a crash.
     */
    protected abstract fun flushInternal()

    /**
     * Reset the value to the last persisted state. This method is called automatically by a system after a system crash.
     */
    internal abstract fun systemCrash()

    /**
     * Make the value persistent.
     * After a successful invocation of this method the value can be seen even after a crash.
     */
    fun flush() {
        flushInternal()
        val state = state()
        state.cache.remove(state.currentThreadId(), this)
    }

    /**
     * Random flush may occur on write to NVM, so the value is flushed or added to the cache.
     */
    protected fun addToCache() {
        val state = state()
        if (state.probability.shouldFlush()) {
            flushInternal()
        } else {
            state.cache.add(state.currentThreadId(), this)
        }
    }

    private fun state() = NVMStateHolder.state ?: error("NVM primitives must be used only in test context.")
}

/**
 * Create non-volatile integer.
 * @param value initial value
 */
fun nonVolatile(value: Int) = NonVolatileInt(value)

/**
 * Create non-volatile long.
 * @param value initial value
 */
fun nonVolatile(value: Long) = NonVolatileLong(value)

/**
 * Create non-volatile boolean.
 * @param value initial value
 */
fun nonVolatile(value: Boolean) = NonVolatileBoolean(value)

/**
 * Create non-volatile reference.
 * @param value initial value
 */
fun <T> nonVolatile(value: T) = NonVolatileRef(value)

/** Persistent reference emulates non-volatile memory variable with volatile cached value. */
class NonVolatileRef<T> internal constructor(initialValue: T) : AbstractNonVolatilePrimitive() {
    /**
     * A persisted value. The value is stored here after a [flush] call.
     */
    @Volatile
    private var nonVolatileValue = initialValue

    /**
     * Non-persisted value. All read or write operations are done with this value.
     */
    private val volatileValue = atomic(initialValue)

    var value: T
        /** Read volatile value. */
        get() = volatileValue.value
        /** Write to volatile value. */
        set(_value) {
            volatileValue.value = _value
            addToCache()
        }

    @Synchronized
    override fun flushInternal() {
        nonVolatileValue = volatileValue.value
    }

    override fun systemCrash() {
        volatileValue.value = nonVolatileValue
    }

    /**
     * Set value directly to NVM.
     * This method could be useful in NRL model.
     */
    @Synchronized
    fun setToNVM(value: T) {
        volatileValue.value = value
        flushInternal()
    }

    /**
     * Atomically sets the volatile value updated value if the current value equals to the
     * expected value.
     *
     * @param expect the expected value
     * @param update the new value
     * @return {@code true} if successful
     */
    fun compareAndSet(expect: T, update: T): Boolean =
        volatileValue.compareAndSet(expect, update).also { if (it) addToCache() }

    /**
     * Atomically sets the volatile value to the provided value and returns the previously
     * stored value.
     *
     * @param value the new value
     * @@return the previously stored value
     */
    fun getAndSet(value: T): T = volatileValue.getAndSet(value).also { addToCache() }

    /**
     * Lazy set the volatile value.
     * @see kotlinx.atomicfu.AtomicRef.lazySet
     */
    fun lazySet(value: T) {
        volatileValue.lazySet(value)
        addToCache()
    }
}

class NonVolatileInt internal constructor(initialValue: Int) : AbstractNonVolatilePrimitive() {
    /**
     * A persisted value. The value is stored here after a [flush] call.
     */
    @Volatile
    private var nonVolatileValue = initialValue

    /**
     * Non-persisted value. All read or write operations are done with this value.
     */
    private val volatileValue = atomic(initialValue)

    var value: Int
        /** Read volatile value. */
        get() = volatileValue.value
        /** Write to volatile value. */
        set(_value) {
            volatileValue.value = _value
            addToCache()
        }

    @Synchronized
    override fun flushInternal() {
        nonVolatileValue = volatileValue.value
    }

    override fun systemCrash() {
        volatileValue.value = nonVolatileValue
    }

    /**
     * Set value directly to NVM.
     * This method could be useful in NRL model.
     */
    @Synchronized
    fun setToNVM(value: Int) {
        volatileValue.value = value
        flushInternal()
    }

    /**
     * Atomically sets the volatile value updated value if the current value equals to the
     * expected value.
     *
     * @param expect the expected value
     * @param update the new value
     * @return {@code true} if successful
     */
    fun compareAndSet(expect: Int, update: Int): Boolean =
        volatileValue.compareAndSet(expect, update).also { if (it) addToCache() }

    /**
     * Atomically sets the volatile value to the provided value and returns the previously
     * stored value.
     *
     * @param value the new value
     * @@return the previously stored value
     */
    fun getAndSet(value: Int) = volatileValue.getAndSet(value).also { addToCache() }

    /**
     * Lazy set the volatile value.
     * @see kotlinx.atomicfu.AtomicInt.lazySet
     */
    fun lazySet(value: Int) {
        volatileValue.lazySet(value)
        addToCache()
    }

    /** Atomically increment the volatile value and return the previously stored value. */
    fun getAndIncrement(): Int = volatileValue.getAndIncrement().also { addToCache() }

    /** Atomically decrement the volatile value and return the previously stored value. */
    fun getAndDecrement(): Int = volatileValue.getAndDecrement().also { addToCache() }

    /** Atomically increment the volatile value and return the new value. */
    fun incrementAndGet(): Int = volatileValue.incrementAndGet().also { addToCache() }

    /** Atomically decrement the volatile value and return the new value. */
    fun decrementAndGet(): Int = volatileValue.decrementAndGet().also { addToCache() }

    /** Atomically add [delta] to the volatile value and return the previously stored value. */
    fun getAndAdd(delta: Int): Int = volatileValue.getAndAdd(delta).also { addToCache() }

    /** Atomically add [delta] to the volatile value and return the new value. */
    fun addAndGet(delta: Int) = volatileValue.addAndGet(delta).also { addToCache() }

    /** Atomically add [delta] to the volatile value. */
    operator fun plusAssign(delta: Int) {
        volatileValue.plusAssign(delta)
        addToCache()
    }

    /** Atomically subtract [delta] from the volatile value. */
    operator fun minusAssign(delta: Int) {
        volatileValue.minusAssign(delta)
        addToCache()
    }
}


class NonVolatileLong internal constructor(initialValue: Long) : AbstractNonVolatilePrimitive() {
    /**
     * A persisted value. The value is stored here after a [flush] call.
     */
    @Volatile
    private var nonVolatileValue = initialValue

    /**
     * Non-persisted value. All read or write operations are done with this value.
     */
    private val volatileValue = atomic(initialValue)

    var value: Long
        /** Read volatile value. */
        get() = volatileValue.value
        /** Write to volatile value. */
        set(_value) {
            volatileValue.value = _value
            addToCache()
        }

    @Synchronized
    override fun flushInternal() {
        nonVolatileValue = volatileValue.value
    }

    override fun systemCrash() {
        volatileValue.value = nonVolatileValue
    }

    /**
     * Set value directly to NVM.
     * This method could be useful in NRL model.
     */
    @Synchronized
    fun setToNVM(value: Long) {
        volatileValue.value = value
        flushInternal()
    }

    /**
     * Atomically sets the volatile value updated value if the current value equals to the
     * expected value.
     *
     * @param expect the expected value
     * @param update the new value
     * @return {@code true} if successful
     */
    fun compareAndSet(expect: Long, update: Long): Boolean =
        volatileValue.compareAndSet(expect, update).also { if (it) addToCache() }

    /**
     * Atomically sets the volatile value to the provided value and returns the previously
     * stored value.
     *
     * @param value the new value
     * @@return the previously stored value
     */
    fun getAndSet(value: Long) = volatileValue.getAndSet(value).also { addToCache() }

    /**
     * Lazy set the volatile value.
     * @see kotlinx.atomicfu.AtomicLong.lazySet
     */
    fun lazySet(value: Long) {
        addToCache()
        volatileValue.lazySet(value)
    }

    /** Atomically increment the volatile value and return the previously stored value. */
    fun getAndIncrement(): Long = volatileValue.getAndIncrement().also { addToCache() }

    /** Atomically decrement the volatile value and return the previously stored value. */
    fun getAndDecrement(): Long = volatileValue.getAndDecrement().also { addToCache() }

    /** Atomically increment the volatile value and return the new value. */
    fun incrementAndGet(): Long = volatileValue.incrementAndGet().also { addToCache() }

    /** Atomically decrement the volatile value and return the new value. */
    fun decrementAndGet(): Long = volatileValue.decrementAndGet().also { addToCache() }

    /** Atomically add [delta] to the volatile value and return the previously stored value. */
    fun getAndAdd(delta: Long): Long = volatileValue.getAndAdd(delta).also { addToCache() }

    /** Atomically add [delta] to the volatile value and return the new value. */
    fun addAndGet(delta: Long): Long = volatileValue.addAndGet(delta).also { addToCache() }

    /** Atomically add [delta] to the volatile value. */
    operator fun plusAssign(delta: Long) {
        volatileValue.plusAssign(delta)
        addToCache()
    }

    /** Atomically subtract [delta] from the volatile value. */
    operator fun minusAssign(delta: Long) {
        volatileValue.minusAssign(delta)
        addToCache()
    }
}

class NonVolatileBoolean internal constructor(initialValue: Boolean) : AbstractNonVolatilePrimitive() {
    /**
     * A persisted value. The value is stored here after a [flush] call.
     */
    @Volatile
    private var nonVolatileValue = initialValue

    /**
     * Non-persisted value. All read or write operations are done with this value.
     */
    private val volatileValue = atomic(initialValue)

    var value: Boolean
        /** Read volatile value. */
        get() = volatileValue.value
        /** Write to volatile value. */
        set(_value) {
            volatileValue.value = _value
            addToCache()
        }

    @Synchronized
    override fun flushInternal() {
        nonVolatileValue = volatileValue.value
    }

    override fun systemCrash() {
        volatileValue.value = nonVolatileValue
    }

    /**
     * Set value directly to NVM.
     * This method could be useful in NRL model.
     */
    @Synchronized
    fun setToNVM(value: Boolean) {
        volatileValue.value = value
        flushInternal()
    }

    /**
     * Atomically sets the volatile value updated value if the current value equals to the
     * expected value.
     *
     * @param expect the expected value
     * @param update the new value
     * @return {@code true} if successful
     */
    fun compareAndSet(expect: Boolean, update: Boolean): Boolean =
        volatileValue.compareAndSet(expect, update).also { if (it) addToCache() }

    /**
     * Atomically sets the volatile value to the provided value and returns the previously
     * stored value.
     *
     * @param value the new value
     * @@return the previously stored value
     */
    fun getAndSet(value: Boolean) = volatileValue.getAndSet(value).also { addToCache() }

    /**
     * Lazy set the volatile value.
     * @see kotlinx.atomicfu.AtomicBoolean.lazySet
     */
    fun lazySet(value: Boolean) {
        volatileValue.lazySet(value)
        addToCache()
    }
}
