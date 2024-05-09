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

import java.util.*

/**
 * Tracks memory operations with shared variables.
 */
abstract class MemoryTracker {

    abstract fun writeValue(iThread: Int, codeLocation: Int, location: MemoryLocation, value: OpaqueValue?)

    abstract fun readValue(iThread: Int, codeLocation: Int, location: MemoryLocation): OpaqueValue?

    abstract fun compareAndSet(iThread: Int, codeLocation: Int, location: MemoryLocation, expected: OpaqueValue?, desired: OpaqueValue?): Boolean

    abstract fun addAndGet(iThread: Int, codeLocation: Int, location: MemoryLocation, delta: Number): OpaqueValue?

    abstract fun getAndAdd(iThread: Int, codeLocation: Int, location: MemoryLocation, delta: Number): OpaqueValue?

    abstract fun getAndSet(iThread: Int, codeLocation: Int, location: MemoryLocation, value: OpaqueValue?): OpaqueValue?

    abstract fun dumpMemory()

    abstract fun reset()

}

typealias MemoryInitializer = (MemoryLocation) -> OpaqueValue?
typealias MemoryIDInitializer = (MemoryLocation) -> ValueID

/**
 * Simple straightforward implementation of memory tracking.
 * Represents the shared memory as a map MemoryLocation -> Value.
 *
 * TODO: do not use this class for interleaving-based model checking?.
 * TODO: add dynamic type-checks (via kClass)
 */
internal class PlainMemoryTracker(
    val objectTracker: ObjectTracker,
    val memoryInitializer: MemoryInitializer
) : MemoryTracker() {

    private val memory = HashMap<MemoryLocation, ValueID>()

    override fun writeValue(iThread: Int, codeLocation: Int, location: MemoryLocation, value: OpaqueValue?) {
        memory[location] = objectTracker.getOrRegisterValueID(location.type, value)
    }

    override fun readValue(iThread: Int, codeLocation: Int, location: MemoryLocation): OpaqueValue? {
        val valueID = memory.computeIfAbsent(location) {
            val value = memoryInitializer(it)
            objectTracker.getOrRegisterValueID(location.type, value)
        }
        return objectTracker.getValue(location.type, valueID)
    }

    override fun compareAndSet(
        iThread: Int,
        codeLocation: Int,
        location: MemoryLocation,
        expected: OpaqueValue?,
        desired: OpaqueValue?
    ): Boolean {
        if (expected == readValue(iThread, codeLocation, location)) {
            writeValue(iThread, codeLocation, location, desired)
            return true
        }
        return false
    }

    override fun addAndGet(iThread: Int, codeLocation: Int, location: MemoryLocation, delta: Number): OpaqueValue? =
        (readValue(iThread, codeLocation, location)!! + delta)
            .also { value -> writeValue(iThread, codeLocation, location, value) }

    override fun getAndAdd(iThread: Int, codeLocation: Int, location: MemoryLocation, delta: Number): OpaqueValue? =
        readValue(iThread, codeLocation, location)!!
            .also { value -> writeValue(iThread, codeLocation, location, value + delta) }

    override fun getAndSet(iThread: Int, codeLocation: Int, location: MemoryLocation, value: OpaqueValue?): OpaqueValue? {
        val result = readValue(iThread, codeLocation, location)
        writeValue(iThread, codeLocation, location, value)
        return result
    }

    override fun reset() {
        memory.clear()
    }

    override fun dumpMemory() {
        for ((location, valueID) in memory.entries) {
            location.write(valueID, objectTracker::getValue)
        }
    }

    fun copy(): PlainMemoryTracker =
        PlainMemoryTracker(objectTracker, memoryInitializer).also { it.memory += memory }

    override fun equals(other: Any?): Boolean {
        return (other is PlainMemoryTracker) && (memory == other.memory)
    }

    override fun hashCode(): Int {
        return memory.hashCode()
    }

}