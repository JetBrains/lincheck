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
package org.jetbrains.kotlinx.lincheck.nvm.api

import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.nvm.NVMCache
import org.jetbrains.kotlinx.lincheck.nvm.Probability
import org.jetbrains.kotlinx.lincheck.nvm.RecoverableStateContainer

abstract class AbstractNonVolatilePrimitive {
    internal abstract fun flushInternal()
    internal abstract fun systemCrash()

    fun flush() {
        flushInternal()
        NVMCache.remove(RecoverableStateContainer.threadId(), this)
    }

    internal fun crash() {
        if (Probability.shouldFlush()) {
            flushInternal()
        }
    }

    protected fun addToCache() = NVMCache.add(RecoverableStateContainer.threadId(), this)
}

fun nonVolatile(value: Int) = NonVolatileInt(value)
fun nonVolatile(value: Long) = NonVolatileLong(value)
fun nonVolatile(value: Boolean) = NonVolatileBoolean(value)
fun <T> nonVolatile(value: T) = NonVolatileRef(value)

/** Persistent reference emulates non-volatile memory variable with volatile cache. */
class NonVolatileRef<T> internal constructor(initialValue: T) : AbstractNonVolatilePrimitive() {
    @Volatile
    private var nonVolatileValue = initialValue
    private val volatileValue = atomic(initialValue)

    var value: T
        get() = volatileValue.value
        set(_value) {
            volatileValue.value = _value
            addToCache()
        }

    override fun flushInternal() {
        nonVolatileValue = volatileValue.value
    }

    override fun systemCrash() {
        crash()
        volatileValue.value = nonVolatileValue
    }

    fun setAndFlush(value: T) {
        volatileValue.value = value
        nonVolatileValue = value
    }

    fun compareAndSet(expect: T, update: T): Boolean {
        addToCache()
        return volatileValue.compareAndSet(expect, update)
    }

    fun getAndSet(value: T): T {
        addToCache()
        return volatileValue.getAndSet(value)
    }

    fun lazySet(value: T) {
        addToCache()
        volatileValue.lazySet(value)
    }
}

class NonVolatileInt internal constructor(initialValue: Int) : AbstractNonVolatilePrimitive() {
    @Volatile
    private var nonVolatileValue = initialValue
    private val volatileValue = atomic(initialValue)

    var value: Int
        get() = volatileValue.value
        set(_value) {
            volatileValue.value = _value
            addToCache()
        }

    override fun flushInternal() {
        nonVolatileValue = volatileValue.value
    }

    override fun systemCrash() {
        crash()
        volatileValue.value = nonVolatileValue
    }

    fun setAndFlush(value: Int) {
        volatileValue.value = value
        nonVolatileValue = value
    }

    fun compareAndSet(expect: Int, update: Int): Boolean {
        addToCache()
        return volatileValue.compareAndSet(expect, update)
    }

    fun getAndSet(value: Int): Int {
        addToCache()
        return volatileValue.getAndSet(value)
    }

    fun lazySet(value: Int) {
        addToCache()
        volatileValue.lazySet(value)
    }

    fun getAndIncrement(): Int {
        addToCache()
        return volatileValue.getAndIncrement()
    }

    fun getAndDecrement(): Int {
        addToCache()
        return volatileValue.getAndDecrement()
    }

    fun incrementAndGet(): Int {
        addToCache()
        return volatileValue.incrementAndGet()
    }

    fun decrementAndGet(): Int {
        addToCache()
        return volatileValue.decrementAndGet()
    }

    fun getAndAdd(delta: Int): Int {
        addToCache()
        return volatileValue.getAndAdd(delta)
    }

    fun addAndGet(delta: Int): Int {
        addToCache()
        return volatileValue.addAndGet(delta)
    }

    operator fun plusAssign(delta: Int) {
        addToCache()
        return volatileValue.plusAssign(delta)
    }

    operator fun minusAssign(delta: Int) {
        addToCache()
        return volatileValue.minusAssign(delta)
    }
}


class NonVolatileLong internal constructor(initialValue: Long) : AbstractNonVolatilePrimitive() {
    @Volatile
    private var nonVolatileValue = initialValue
    private val volatileValue = atomic(initialValue)

    var value: Long
        get() = volatileValue.value
        set(_value) {
            volatileValue.value = _value
            addToCache()
        }

    override fun flushInternal() {
        nonVolatileValue = volatileValue.value
    }

    override fun systemCrash() {
        crash()
        volatileValue.value = nonVolatileValue
    }

    fun setAndFlush(value: Long) {
        volatileValue.value = value
        nonVolatileValue = value
    }

    fun compareAndSet(expect: Long, update: Long): Boolean {
        addToCache()
        return volatileValue.compareAndSet(expect, update)
    }

    fun getAndSet(value: Long): Long {
        addToCache()
        return volatileValue.getAndSet(value)
    }

    fun lazySet(value: Long) {
        addToCache()
        volatileValue.lazySet(value)
    }

    fun getAndIncrement(): Long {
        addToCache()
        return volatileValue.getAndIncrement()
    }

    fun getAndDecrement(): Long {
        addToCache()
        return volatileValue.getAndDecrement()
    }

    fun incrementAndGet(): Long {
        addToCache()
        return volatileValue.incrementAndGet()
    }

    fun decrementAndGet(): Long {
        addToCache()
        return volatileValue.decrementAndGet()
    }

    fun getAndAdd(delta: Long): Long {
        addToCache()
        return volatileValue.getAndAdd(delta)
    }

    fun addAndGet(delta: Long): Long {
        addToCache()
        return volatileValue.addAndGet(delta)
    }

    operator fun plusAssign(delta: Long) {
        addToCache()
        return volatileValue.plusAssign(delta)
    }

    operator fun minusAssign(delta: Long) {
        addToCache()
        return volatileValue.minusAssign(delta)
    }
}

class NonVolatileBoolean internal constructor(initialValue: Boolean) : AbstractNonVolatilePrimitive() {
    @Volatile
    private var nonVolatileValue = initialValue
    private val volatileValue = atomic(initialValue)

    var value: Boolean
        get() = volatileValue.value
        set(_value) {
            volatileValue.value = _value
            addToCache()
        }

    override fun flushInternal() {
        nonVolatileValue = volatileValue.value
    }

    override fun systemCrash() {
        crash()
        volatileValue.value = nonVolatileValue
    }

    fun setAndFlush(value: Boolean) {
        volatileValue.value = value
        nonVolatileValue = value
    }

    fun compareAndSet(expect: Boolean, update: Boolean): Boolean {
        addToCache()
        return volatileValue.compareAndSet(expect, update)
    }

    fun getAndSet(value: Boolean): Boolean {
        addToCache()
        return volatileValue.getAndSet(value)
    }

    fun lazySet(value: Boolean) {
        addToCache()
        volatileValue.lazySet(value)
    }
}