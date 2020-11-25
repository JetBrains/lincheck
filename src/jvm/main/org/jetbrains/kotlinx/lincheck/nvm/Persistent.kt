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

import org.jetbrains.kotlinx.lincheck.runner.RecoverableStateContainer


/** Persistent reference emulates non-volatile memory variable with volatile cache. */
class Persistent<T>() {
    @Volatile
    private var persistedValue: T? = null

    private var localValue: T? = null

    private val empty = BooleanArray(NVMCache.MAX_THREADS_NUMBER) { true }

    constructor(initialValue: T) : this() {
        persistedValue = initialValue
    }

    fun read(threadId: Int = RecoverableStateContainer.threadId()) = if (empty[threadId]) {
        persistedValue
    } else {
        localValue
    }

    fun write(threadId: Int = RecoverableStateContainer.threadId(), value: T) {
        empty[threadId] = false
        localValue = value
        NVMCache.add(threadId, this)
    }

    fun flush(threadId: Int = RecoverableStateContainer.threadId()) {
        if (empty[threadId]) return
        persistedValue = localValue
        NVMCache.remove(threadId, this)
    }

    fun writeAndFlush(threadId: Int = RecoverableStateContainer.threadId(), value: T) {
        empty[threadId] = false
        localValue = value
        persistedValue = localValue
    }

    internal fun crash(threadId: Int) {
        if (Probability.shouldFlush()) {
            flush(threadId)
        }
        NVMCache.remove(threadId, this)
        empty[threadId] = true
    }
}
