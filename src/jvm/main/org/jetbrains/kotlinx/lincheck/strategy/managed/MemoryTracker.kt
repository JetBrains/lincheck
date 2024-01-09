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

import org.jetbrains.kotlinx.lincheck.utils.*
import kotlin.reflect.KClass
import java.util.*

/**
 * Tracks memory operations with shared variables.
 */
abstract class MemoryTracker {

    abstract fun getValue(id: ValueID): OpaqueValue?

    abstract fun getValueID(value: OpaqueValue?): ValueID

    abstract fun computeValueID(value: OpaqueValue?): ValueID

    abstract fun objectAllocation(iThread: Int, value: OpaqueValue)

    abstract fun writeValue(iThread: Int, codeLocation: Int, location: MemoryLocation, kClass: KClass<*>, value: OpaqueValue?)

    abstract fun readValue(iThread: Int, codeLocation: Int, location: MemoryLocation, kClass: KClass<*>): OpaqueValue?

    abstract fun compareAndSet(iThread: Int, codeLocation: Int, location: MemoryLocation, kClass: KClass<*>, expected: OpaqueValue?, desired: OpaqueValue?): Boolean

    abstract fun addAndGet(iThread: Int, codeLocation: Int, location: MemoryLocation, kClass: KClass<*>, delta: Number): OpaqueValue?

    abstract fun getAndAdd(iThread: Int, codeLocation: Int, location: MemoryLocation, kClass: KClass<*>, delta: Number): OpaqueValue?

    abstract fun getAndSet(iThread: Int, codeLocation: Int, location: MemoryLocation, kClass: KClass<*>, value: OpaqueValue?): OpaqueValue?

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
    val memoryInitializer: MemoryInitializer
) : MemoryTracker() {

    private var nextObjectID = 1 + NULL_OBJECT_ID.id

    private val objectIdIndex = HashMap<ObjectID, OpaqueValue>()
    private val objectIndex = IdentityHashMap<Any, ObjectID>()

    private val memory = HashMap<MemoryLocation, ValueID>()

    private fun registerObject(id: ObjectID, value: OpaqueValue) {
        objectIdIndex.put(id, value).ensureNull()
        objectIndex.put(value.unwrap(), id).ensureNull()
    }

    override fun getValue(id: ValueID): OpaqueValue? = when (id) {
        NULL_OBJECT_ID -> null
        is PrimitiveID -> id.value.opaque()
        is ObjectID -> objectIdIndex[id]
    }

    override fun getValueID(value: OpaqueValue?): ValueID {
        if (value == null)
            return NULL_OBJECT_ID
        if (value.isPrimitive)
            return PrimitiveID(value.unwrap())
        return objectIndex[value.unwrap()] ?: INVALID_OBJECT_ID
    }

    override fun computeValueID(value: OpaqueValue?): ValueID {
        if (value == null)
            return NULL_OBJECT_ID
        if (value.isPrimitive)
            return PrimitiveID(value.unwrap())
        return objectIndex.computeIfAbsent(value.unwrap()) {
            ObjectID(nextObjectID++).also { id ->
                registerObject(id, value)
            }
        }
    }

    override fun objectAllocation(iThread: Int, value: OpaqueValue) {
        val id = ObjectID(nextObjectID++)
        registerObject(id, value)
    }

    override fun writeValue(iThread: Int, codeLocation: Int, location: MemoryLocation, kClass: KClass<*>, value: OpaqueValue?) {
        memory[location] = computeValueID(value)
    }

    override fun readValue(iThread: Int, codeLocation: Int, location: MemoryLocation, kClass: KClass<*>): OpaqueValue? {
        val valueID = memory.computeIfAbsent(location) {
            val value = memoryInitializer(it)
            computeValueID(value)
        }
        return getValue(valueID)
    }

    override fun compareAndSet(iThread: Int, codeLocation: Int, location: MemoryLocation, kClass: KClass<*>, expected: OpaqueValue?, desired: OpaqueValue?): Boolean {
        if (expected == readValue(iThread, codeLocation, location, kClass)) {
            writeValue(iThread, codeLocation, location, kClass, desired)
            return true
        }
        return false
    }

    override fun addAndGet(iThread: Int, codeLocation: Int, location: MemoryLocation, kClass: KClass<*>, delta: Number): OpaqueValue? =
        (readValue(iThread, codeLocation, location, kClass)!! + delta)
            .also { value -> writeValue(iThread, codeLocation, location, kClass, value) }

    override fun getAndAdd(iThread: Int, codeLocation: Int, location: MemoryLocation, kClass: KClass<*>, delta: Number): OpaqueValue? =
        readValue(iThread, codeLocation, location, kClass)!!
            .also { value -> writeValue(iThread, codeLocation, location, kClass, value + delta) }

    override fun getAndSet(iThread: Int, codeLocation: Int, location: MemoryLocation, kClass: KClass<*>, value: OpaqueValue?): OpaqueValue? {
        val result = readValue(iThread, codeLocation, location, kClass)
        writeValue(iThread, codeLocation, location, kClass, value)
        return result
    }

    override fun reset() {
        objectIdIndex.clear()
        objectIndex.clear()
        memory.clear()
    }

    override fun dumpMemory() {
        for ((location, valueID) in memory.entries) {
            location.write(this::getValue, valueID)
        }
    }

    fun copy(): PlainMemoryTracker =
        PlainMemoryTracker(memoryInitializer).also { it.memory += memory }

    override fun equals(other: Any?): Boolean {
        return (other is PlainMemoryTracker) && (memory == other.memory)
    }

    override fun hashCode(): Int {
        return memory.hashCode()
    }

}