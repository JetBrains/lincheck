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

import org.jetbrains.kotlinx.lincheck.*
import java.lang.reflect.*
import java.util.*
import java.util.concurrent.atomic.*

/**
 * Assigns identifiers to every shared memory location
 * accessed directly or through reflections (e.g., through AFU or VarHandle).
*/
internal class MemoryLocationLabeler {
    private val fieldLocationByReflectionMap = IdentityHashMap<Any, AtomicReflectionAccessDescriptor>()
    private val fieldNameByOffsetMap = mutableMapOf<UnsafeFieldAccessDescriptor, String>()
    private val unsafeArrayDescriptorByClassMap = mutableMapOf<Class<*>, UnsafeArrayAccessDescriptor>()

    fun labelStaticField(strategy: ManagedStrategy, className: String, fieldName: String): MemoryLocation =
        StaticFieldMemoryLocation(strategy, className, fieldName)

    fun labelObjectField(strategy: ManagedStrategy, obj: Any, className: String, fieldName: String): MemoryLocation {
        val id = strategy.getValueID(obj) as ObjectID
        return ObjectFieldMemoryLocation(strategy, obj.javaClass, id, className, fieldName)
    }

    fun labelArrayElement(strategy: ManagedStrategy, array: Any, position: Int): MemoryLocation {
        val id = strategy.getValueID(array) as ObjectID
        return ArrayElementMemoryLocation(strategy, array.javaClass, id, position)
    }

    fun labelAtomicPrimitive(strategy: ManagedStrategy, primitive: Any): MemoryLocation {
        val id = strategy.getValueID(primitive) as ObjectID
        return AtomicPrimitiveMemoryLocation(strategy, primitive.javaClass, id)
    }

    fun labelAtomicReflectionFieldAccess(strategy: ManagedStrategy, reflection: Any, obj: Any): MemoryLocation {
        val id = strategy.getValueID(obj) as ObjectID
        val descriptor = lookupAtomicReflectionDescriptor(reflection) as AtomicReflectionFieldAccessDescriptor
        return ObjectFieldMemoryLocation(strategy, obj.javaClass, id, descriptor.className, descriptor.fieldName)
    }

    fun labelAtomicReflectionArrayAccess(strategy: ManagedStrategy, reflection: Any, array: Any, index: Int): MemoryLocation {
        check(lookupAtomicReflectionDescriptor(reflection) is AtomicReflectionArrayAccessDescriptor)
        val id = strategy.getValueID(array) as ObjectID
        return ArrayElementMemoryLocation(strategy, array.javaClass, id, index)
    }

    fun labelUnsafeAccess(strategy: ManagedStrategy, unsafe: Any, obj: Any, offset: Long): MemoryLocation {
        val id = strategy.getValueID(obj) as ObjectID
        if (isArrayObject(obj)) {
            val descriptor = lookupUnsafeArrayDescriptor(strategy, obj)
            val index = (offset - descriptor.baseOffset) shr descriptor.indexShift
            return ArrayElementMemoryLocation(strategy, obj.javaClass, id, index.toInt())
        }
        val className = normalizeClassName(strategy, obj.javaClass.name)
        val fieldName = lookupFieldNameByOffset(strategy, obj, offset)
        return ObjectFieldMemoryLocation(strategy, obj.javaClass, id, className, fieldName)
    }

    fun getAtomicReflectionName(reflection: Any): String {
        val descriptor = lookupAtomicReflectionDescriptor(reflection)
        return when (descriptor) {
            is AtomicReflectionFieldAccessDescriptor -> descriptor.fieldName
            is AtomicReflectionArrayAccessDescriptor -> "" // TODO: what name we should put here?
        }
    }

    fun registerAtomicFieldReflection(strategy: ManagedStrategy, reflection: Any, clazz: Class<*>, fieldName: String) {
        val className = normalizeClassName(strategy, clazz.name)
        val descriptor = AtomicReflectionFieldAccessDescriptor(className, fieldName)
        registerAtomicReflectionDescriptor(descriptor, reflection)
    }

    fun registerAtomicArrayReflection(strategy: ManagedStrategy, reflection: Any, elemClazz: Class<*>) {
        val elemClassName = normalizeClassName(strategy, elemClazz.name)
        val descriptor = AtomicReflectionArrayAccessDescriptor(elemClassName)
        registerAtomicReflectionDescriptor(descriptor, reflection)
    }

    fun registerUnsafeFieldOffsetByReflection(strategy: ManagedStrategy, field: Field, offset: Long) {
        registerUnsafeFieldOffsetByName(strategy, field.declaringClass, field.name, offset)
    }

    fun registerUnsafeFieldOffsetByName(strategy: ManagedStrategy, clazz: Class<*>, fieldName: String, offset: Long) {
        val className = normalizeClassName(strategy, clazz.name)
        val descriptor = UnsafeFieldAccessDescriptor(className, offset)
        registerUnsafeFieldAccessDescriptor(descriptor, fieldName)
    }

    fun registerUnsafeArrayBaseOffset(strategy: ManagedStrategy, clazz: Class<*>, baseOffset: Int) {
        unsafeArrayDescriptorByClassMap.compute(clazz) { _, descriptor -> when {
            descriptor == null          -> UnsafeArrayAccessDescriptor(baseOffset = baseOffset)
            descriptor.baseOffset <  0L -> descriptor.copy(baseOffset = baseOffset)
            descriptor.baseOffset >= 0L -> descriptor.ensure { it.baseOffset == baseOffset }
            else -> unreachable()
        }}
    }

    fun registerUnsafeArrayIndexScale(strategy: ManagedStrategy, clazz: Class<*>, indexScale: Int) {
        unsafeArrayDescriptorByClassMap.compute(clazz) { _, descriptor -> when {
            descriptor == null          -> UnsafeArrayAccessDescriptor(indexScale = indexScale)
            descriptor.indexScale <= 0L -> descriptor.copy(indexScale = indexScale)
            descriptor.baseOffset >  0L -> descriptor.ensure { it.indexScale == indexScale }
            else -> unreachable()
        }}
    }

    private fun registerAtomicReflectionDescriptor(descriptor: AtomicReflectionAccessDescriptor, reflection: Any) {
        fieldLocationByReflectionMap.put(reflection, descriptor).ensureNull {
            "Atomic reflection object ${reflection.toOpaqueString()} cannot be registered to access $descriptor," +
                "because it is already registered to access $it!"
        }
    }

    private fun registerUnsafeFieldAccessDescriptor(descriptor: UnsafeFieldAccessDescriptor, fieldName: String) {
        fieldNameByOffsetMap.put(descriptor, fieldName).ensure({ it == null || it == fieldName }) {
            "Offset ${descriptor.offset} for class ${descriptor.className} cannot be registered to access field $fieldName " +
                "because it is already registered to access field $it!"
        }
    }

    private fun lookupAtomicReflectionDescriptor(reflection: Any): AtomicReflectionAccessDescriptor =
        fieldLocationByReflectionMap[reflection].ensureNotNull {
            "Cannot access memory via unregistered reflection object ${reflection.toOpaqueString()}!"
        }

    private fun lookupFieldNameByOffset(strategy: ManagedStrategy, obj: Any, offset: Long): String =
        lookupFieldNameByOffset(strategy, obj.javaClass, offset).ensureNotNull {
            "Cannot access object ${obj.toOpaqueString()} via unregistered offset $offset!"
        }

    private fun lookupFieldNameByOffset(strategy: ManagedStrategy, clazz: Class<*>, offset: Long): String? {
        val className = normalizeClassName(strategy, clazz.name)
        val descriptor = UnsafeFieldAccessDescriptor(className, offset)
        fieldNameByOffsetMap[descriptor]?.let { return it }
        return clazz.superclass
            ?.let { lookupFieldNameByOffset(strategy, it, offset) }
            ?.also { registerUnsafeFieldAccessDescriptor(descriptor, it) }
    }

    private fun lookupUnsafeArrayDescriptor(strategy: ManagedStrategy, obj: Any): UnsafeArrayAccessDescriptor {
        return unsafeArrayDescriptorByClassMap[obj.javaClass].ensureNotNull {
            "Cannot unsafely access array ${obj.toOpaqueString()} via unregistered class ${obj.javaClass}!"
        }.ensure { it.isValid() }
    }

    private fun normalizeClassName(strategy: ManagedStrategy, className: String): String =
        (strategy.classLoader as TransformationClassLoader).removeRemappingPrefix(className)

}

private sealed class AtomicReflectionAccessDescriptor

private data class AtomicReflectionFieldAccessDescriptor(
    val className: String,
    val fieldName: String
): AtomicReflectionAccessDescriptor()

private data class AtomicReflectionArrayAccessDescriptor(
    val elementClassName: String,
): AtomicReflectionAccessDescriptor()

private data class UnsafeFieldAccessDescriptor(
    val className: String,
    val offset: Long,
)

private data class UnsafeArrayAccessDescriptor(
    val baseOffset: Int = -1,
    val indexScale: Int = -1,
) {
    fun isValid() = (baseOffset >= 0) && (indexScale > 0)
        // check scale is power of two
        && ((indexScale and indexScale - 1) == 0)

    val indexShift: Int
        get() = Integer.numberOfLeadingZeros(indexScale)
}

private fun isArrayObject(obj: Any): Boolean = when (obj) {
    is ByteArray,
    is ShortArray,
    is IntArray,
    is LongArray,
    is FloatArray,
    is DoubleArray,
    is CharArray,
    is BooleanArray,
    is Array<*>,
    is AtomicIntegerArray,
    is AtomicLongArray,
    is AtomicReferenceArray<*>
    -> true
    else    -> false
}