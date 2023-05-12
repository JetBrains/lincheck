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

    abstract fun writeValue(iThread: Int, location: MemoryLocation, kClass: KClass<*>, value: OpaqueValue?)

    abstract fun readValue(iThread: Int, location: MemoryLocation, kClass: KClass<*>): OpaqueValue?

    abstract fun compareAndSet(iThread: Int, location: MemoryLocation, kClass: KClass<*>, expected: OpaqueValue?, desired: OpaqueValue?): Boolean

    abstract fun addAndGet(iThread: Int, location: MemoryLocation, kClass: KClass<*>, delta: Number): OpaqueValue?

    abstract fun getAndAdd(iThread: Int, location: MemoryLocation, kClass: KClass<*>, delta: Number): OpaqueValue?

    abstract fun getAndSet(iThread: Int, location: MemoryLocation, kClass: KClass<*>, value: OpaqueValue?): OpaqueValue?

    abstract fun dumpMemory()

    abstract fun reset()

}

typealias MemoryInitializer = (MemoryLocation) -> OpaqueValue?

/**
 * Simple straightforward implementation of memory tracking.
 * Represents the shared memory as a map MemoryLocation -> Value.
 *
 * TODO: do not use this class for interleaving-based model checking?.
 * TODO: add dynamic type-checks (via kClass)
 */
internal class PlainMemoryTracker : MemoryTracker() {
    private val memory = HashMap<MemoryLocation, OpaqueValue?>()

    override fun writeValue(iThread: Int, location: MemoryLocation, kClass: KClass<*>, value: OpaqueValue?) =
        memory.set(location, value)

    override fun readValue(iThread: Int, location: MemoryLocation, kClass: KClass<*>): OpaqueValue? =
        memory.getOrElse(location) { OpaqueValue.default(kClass) }

    override fun compareAndSet(iThread: Int, location: MemoryLocation, kClass: KClass<*>, expected: OpaqueValue?, desired: OpaqueValue?): Boolean {
        if (expected == readValue(iThread, location, kClass)) {
            writeValue(iThread, location, kClass, desired)
            return true
        }
        return false
    }

    override fun addAndGet(iThread: Int, location: MemoryLocation, kClass: KClass<*>, delta: Number): OpaqueValue? =
        (readValue(iThread, location, kClass)!! + delta)
            .also { value -> writeValue(iThread, location, kClass, value) }

    override fun getAndAdd(iThread: Int, location: MemoryLocation, kClass: KClass<*>, delta: Number): OpaqueValue? =
        readValue(iThread, location, kClass)!!
            .also { value -> writeValue(iThread, location, kClass, value + delta) }

    override fun getAndSet(iThread: Int, location: MemoryLocation, kClass: KClass<*>, value: OpaqueValue?): OpaqueValue? {
        val result = readValue(iThread, location, kClass)
        writeValue(iThread, location, kClass, value)
        return result
    }

    override fun reset() {
        memory.clear()
    }

    override fun dumpMemory() {
        for ((location, value) in memory.entries) {
            location.write(value?.unwrap())
        }
    }

    fun copy(): PlainMemoryTracker =
        PlainMemoryTracker().also { it.memory += memory }

    override fun equals(other: Any?): Boolean {
        return (other is PlainMemoryTracker) && (memory == other.memory)
    }

    override fun hashCode(): Int {
        return memory.hashCode()
    }
}