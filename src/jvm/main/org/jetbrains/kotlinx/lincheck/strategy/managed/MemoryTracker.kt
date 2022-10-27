/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.strategy.managed

import java.util.HashMap
import kotlin.reflect.KClass

/**
 * Tracks memory operations with shared variables.
 */
abstract class MemoryTracker {

    abstract fun writeValue(iThread: Int, memoryLocationId: MemoryLocation, value: OpaqueValue?, kClass: KClass<*>)

    abstract fun readValue(iThread: Int, memoryLocationId: MemoryLocation, kClass: KClass<*>): OpaqueValue?

    abstract fun compareAndSet(iThread: Int, memoryLocationId: MemoryLocation, expected: OpaqueValue?, desired: OpaqueValue?,
                               kClass: KClass<*>): Boolean

    abstract fun addAndGet(iThread: Int, memoryLocationId: MemoryLocation, delta: Number, kClass: KClass<*>): OpaqueValue?

    abstract fun getAndAdd(iThread: Int, memoryLocationId: MemoryLocation, delta: Number, kClass: KClass<*>): OpaqueValue?

    abstract fun getAndSet(iThread: Int, memoryLocationId: MemoryLocation, value: OpaqueValue?, kClass: KClass<*>): OpaqueValue?
}

/**
 * Sequentially consistent memory tracking.
 * Represents the shared memory as a map MemoryLocation -> Value.
 *
 * TODO: do not use this class for interleaving-based model checking?.
 * TODO: move to interleaving-based model checking directory?
 * TODO: add dynamic type-checks (via kClass)
 */
internal class SeqCstMemoryTracker : MemoryTracker() {
    private val values = HashMap<MemoryLocation, OpaqueValue?>()

    override fun writeValue(iThread: Int, memoryLocationId: MemoryLocation, value: OpaqueValue?, kClass: KClass<*>) =
            values.set(memoryLocationId, value)

    override fun readValue(iThread: Int, memoryLocationId: MemoryLocation, kClass: KClass<*>): OpaqueValue? =
            values.getOrElse(memoryLocationId) { OpaqueValue.default(kClass) }

    override fun compareAndSet(iThread: Int, memoryLocationId: MemoryLocation, expected: OpaqueValue?, desired: OpaqueValue?,
                               kClass: KClass<*>): Boolean {
        if (expected == readValue(iThread, memoryLocationId, kClass)) {
            writeValue(iThread, memoryLocationId, desired, kClass)
            return true
        }
        return false
    }

    override fun addAndGet(iThread: Int, memoryLocationId: MemoryLocation, delta: Number, kClass: KClass<*>): OpaqueValue? =
            (readValue(iThread, memoryLocationId, kClass)!! + delta)
                    .also { value -> writeValue(iThread, memoryLocationId, value, kClass) }

    override fun getAndAdd(iThread: Int, memoryLocationId: MemoryLocation, delta: Number, kClass: KClass<*>): OpaqueValue? =
            readValue(iThread, memoryLocationId, kClass)!!
                    .also { value -> writeValue(iThread, memoryLocationId, value + delta, kClass) }

    override fun getAndSet(iThread: Int, memoryLocationId: MemoryLocation, value: OpaqueValue?, kClass: KClass<*>): OpaqueValue? =
            readValue(iThread, memoryLocationId, kClass)!!
                    .also { writeValue(iThread, memoryLocationId, value, kClass) }

    fun copy(): SeqCstMemoryTracker =
            SeqCstMemoryTracker().also { it.values += values }

    override fun equals(other: Any?): Boolean {
        return (other is SeqCstMemoryTracker) && (values == other.values)
    }

    override fun hashCode(): Int {
        return values.hashCode()
    }
}