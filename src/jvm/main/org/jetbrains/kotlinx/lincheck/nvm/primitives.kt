/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.nvm

import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.runner.RecoverableStateContainer


private fun thread() = RecoverableStateContainer.threadId()

abstract class AbstractNonVolatilePrimitive {
    protected val empty = BooleanArray(NVMCache.MAX_THREADS_NUMBER) { true }

    abstract fun flushInternal(threadId: Int = thread())
    fun flush(threadId: Int = thread()) {
        flushInternal(threadId)
        NVMCache.remove(threadId, this)
    }

    internal fun crash(threadId: Int) {
        if (Probability.shouldFlush()) {
            flushInternal(threadId)
        }
        empty[threadId] = true
    }
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

    fun read(threadId: Int = thread()) = if (empty[threadId]) nonVolatileValue else volatileValue.value

    fun write(value: T, threadId: Int = thread()) {
        empty[threadId] = false
        volatileValue.value = value
        NVMCache.add(threadId, this)
    }

    override fun flushInternal(threadId: Int) {
        if (empty[threadId]) return
        nonVolatileValue = volatileValue.value
    }

    fun writeAndFlush(value: T, threadId: Int = RecoverableStateContainer.threadId()) {
        empty[threadId] = false
        volatileValue.value = value
        nonVolatileValue = value
    }

    fun compareAndSet(expect: T, update: T, threadId: Int = RecoverableStateContainer.threadId()): Boolean {
        if (empty[threadId]) {
            volatileValue.value = nonVolatileValue
        }
        NVMCache.add(threadId, this)
        return volatileValue.compareAndSet(expect, update)
    }

    fun getAndSet(value: T, threadId: Int = RecoverableStateContainer.threadId()): T {
        if (empty[threadId]) {
            volatileValue.value = nonVolatileValue
        }
        NVMCache.add(threadId, this)
        return volatileValue.getAndSet(value)
    }
}

class NonVolatileInt internal constructor(initialValue: Int) : AbstractNonVolatilePrimitive() {
    @Volatile
    private var nonVolatileValue = initialValue
    private val volatileValue = atomic(initialValue)

    fun read(threadId: Int = thread()) = if (empty[threadId]) nonVolatileValue else volatileValue.value

    fun write(value: Int, threadId: Int = thread()) {
        empty[threadId] = false
        volatileValue.value = value
        NVMCache.add(threadId, this)
    }

    override fun flushInternal(threadId: Int) {
        if (empty[threadId]) return
        nonVolatileValue = volatileValue.value
    }

    fun writeAndFlush(value: Int, threadId: Int = RecoverableStateContainer.threadId()) {
        empty[threadId] = false
        volatileValue.value = value
        nonVolatileValue = value
    }

    fun compareAndSet(expect: Int, update: Int, threadId: Int = RecoverableStateContainer.threadId()): Boolean {
        if (empty[threadId]) {
            volatileValue.value = nonVolatileValue
        }
        NVMCache.add(threadId, this)
        return volatileValue.compareAndSet(expect, update)
    }

    fun getAndSet(value: Int, threadId: Int = RecoverableStateContainer.threadId()): Int {
        if (empty[threadId]) {
            volatileValue.value = nonVolatileValue
        }
        NVMCache.add(threadId, this)
        return volatileValue.getAndSet(value)
    }
}


class NonVolatileLong internal constructor(initialValue: Long) : AbstractNonVolatilePrimitive() {
    @Volatile
    private var nonVolatileValue = initialValue
    private val volatileValue = atomic(initialValue)

    fun read(threadId: Int = thread()) = if (empty[threadId]) nonVolatileValue else volatileValue.value

    fun write(value: Long, threadId: Int = thread()) {
        empty[threadId] = false
        volatileValue.value = value
        NVMCache.add(threadId, this)
    }

    override fun flushInternal(threadId: Int) {
        if (empty[threadId]) return
        nonVolatileValue = volatileValue.value
    }

    fun writeAndFlush(value: Long, threadId: Int = RecoverableStateContainer.threadId()) {
        empty[threadId] = false
        volatileValue.value = value
        nonVolatileValue = value
    }

    fun compareAndSet(expect: Long, update: Long, threadId: Int = RecoverableStateContainer.threadId()): Boolean {
        if (empty[threadId]) {
            volatileValue.value = nonVolatileValue
        }
        NVMCache.add(threadId, this)
        return volatileValue.compareAndSet(expect, update)
    }

    fun getAndSet(value: Long, threadId: Int = RecoverableStateContainer.threadId()): Long {
        if (empty[threadId]) {
            volatileValue.value = nonVolatileValue
        }
        NVMCache.add(threadId, this)
        return volatileValue.getAndSet(value)
    }
}

class NonVolatileBoolean internal constructor(initialValue: Boolean) : AbstractNonVolatilePrimitive() {
    @Volatile
    private var nonVolatileValue = initialValue
    private val volatileValue = atomic(initialValue)

    fun read(threadId: Int = thread()) = if (empty[threadId]) nonVolatileValue else volatileValue.value

    fun write(value: Boolean, threadId: Int = thread()) {
        empty[threadId] = false
        volatileValue.value = value
        NVMCache.add(threadId, this)
    }

    override fun flushInternal(threadId: Int) {
        if (empty[threadId]) return
        nonVolatileValue = volatileValue.value
    }

    fun writeAndFlush(value: Boolean, threadId: Int = RecoverableStateContainer.threadId()) {
        empty[threadId] = false
        volatileValue.value = value
        nonVolatileValue = value
    }

    fun compareAndSet(expect: Boolean, update: Boolean, threadId: Int = RecoverableStateContainer.threadId()): Boolean {
        if (empty[threadId]) {
            volatileValue.value = nonVolatileValue
        }
        NVMCache.add(threadId, this)
        return volatileValue.compareAndSet(expect, update)
    }

    fun getAndSet(value: Boolean, threadId: Int = RecoverableStateContainer.threadId()): Boolean {
        if (empty[threadId]) {
            volatileValue.value = nonVolatileValue
        }
        NVMCache.add(threadId, this)
        return volatileValue.getAndSet(value)
    }
}

