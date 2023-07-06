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

interface MemoryLocation {
    val obj: Any

    val isAtomic: Boolean

    fun read(): Any?
    fun write(value: Any?)

    fun replay(location: MemoryLocation, remapping: Remapping)

    fun remap(remapping: Remapping)
}

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

    fun labelObjectField(strategy: ManagedStrategy, obj: Any, className: String, fieldName: String): MemoryLocation =
        ObjectFieldMemoryLocation(strategy, obj, className, fieldName)

    fun labelArrayElement(strategy: ManagedStrategy, array: Any, position: Int): MemoryLocation =
        ArrayElementMemoryLocation(strategy, array, position)

    fun labelAtomicPrimitive(strategy: ManagedStrategy, primitive: Any): MemoryLocation =
        AtomicPrimitiveMemoryLocation(strategy, primitive)

    fun labelAtomicReflectionFieldAccess(strategy: ManagedStrategy, reflection: Any, obj: Any): MemoryLocation {
        val descriptor = lookupAtomicReflectionDescriptor(reflection) as AtomicReflectionFieldAccessDescriptor
        return ObjectFieldMemoryLocation(strategy, obj, descriptor.className, descriptor.fieldName, isAtomic = true)
    }

    fun labelAtomicReflectionArrayAccess(strategy: ManagedStrategy, reflection: Any, array: Any, index: Int): MemoryLocation {
        check(lookupAtomicReflectionDescriptor(reflection) is AtomicReflectionArrayAccessDescriptor)
        return ArrayElementMemoryLocation(strategy, array, index)
    }

    fun labelUnsafeAccess(strategy: ManagedStrategy, unsafe: Any, obj: Any, offset: Long): MemoryLocation {
        if (isArrayObject(obj)) {
            val descriptor = lookupUnsafeArrayDescriptor(strategy, obj)
            val index = (offset - descriptor.baseOffset) shr descriptor.indexShift
            return ArrayElementMemoryLocation(strategy, obj, index.toInt())
        }
        val className = normalizeClassName(strategy, obj.javaClass.name)
        val fieldName = lookupFieldNameByOffset(strategy, obj, offset)
        return ObjectFieldMemoryLocation(strategy, obj, className, fieldName)
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
            "Atomic reflection object ${opaqueString(reflection)} cannot be registered to access $descriptor," +
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
            "Cannot access memory via unregistered reflection object ${opaqueString(reflection)}!"
        }

    private fun lookupFieldNameByOffset(strategy: ManagedStrategy, obj: Any, offset: Long): String =
        lookupFieldNameByOffset(strategy, obj.javaClass, offset).ensureNotNull {
            "Cannot access object ${opaqueString(obj)} via unregistered offset $offset!"
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
            "Cannot unsafely access array ${opaqueString(obj)} via unregistered class ${obj.javaClass}!"
        }.ensure { it.isValid() }
    }

    private fun normalizeClassName(strategy: ManagedStrategy, className: String): String =
        (strategy.classLoader as TransformationClassLoader).removeRemappingPrefix(className)

}

internal class StaticFieldMemoryLocation(
    private val strategy: ManagedStrategy,
    val className: String,
    val fieldName: String
) : MemoryLocation {

    override val obj: Any = STATIC_OBJECT

    override val isAtomic = false

    private val field by lazy {
        getClass(strategy, className = className).getDeclaredField(fieldName)
            .apply { isAccessible = true }
    }

    override fun read(): Any? = field.get(null)

    override fun write(value: Any?) {
        field.set(null, value)
    }

    override fun replay(location: MemoryLocation, remapping: Remapping) {
        check(location is StaticFieldMemoryLocation &&
            className == location.className &&
            fieldName == location.fieldName) {
            "Memory location $this cannot be replayed by $location"
        }
    }

    override fun remap(remapping: Remapping) {}

    override fun equals(other: Any?): Boolean =
        other is StaticFieldMemoryLocation && (className == other.className && fieldName == other.fieldName)

    override fun hashCode(): Int =
        Objects.hash(className, fieldName)

    override fun toString(): String = "$className.$fieldName"

}

// TODO: override `toString` ?
internal val STATIC_OBJECT = Any()

internal class ObjectFieldMemoryLocation(
    private val strategy: ManagedStrategy,
    private var _obj: Any,
    val className: String,
    val fieldName: String,
    override val isAtomic: Boolean = false
) : MemoryLocation {

    override val obj: Any
        get() = _obj

    private val field by lazy {
        val clazz = getClass(strategy, this.obj, className = className)
        getField(clazz, className, fieldName)
            .apply { isAccessible = true }
    }

    override fun read(): Any? = field.get(this.obj)

    override fun write(value: Any?) {
        field.set(this.obj, value)
    }

    override fun replay(location: MemoryLocation, remapping: Remapping) {
        check(location is ObjectFieldMemoryLocation &&
            this.obj::class == location.obj::class &&
            className == location.className &&
            fieldName == location.fieldName) {
            "Memory location $this cannot be replayed by ${location}."
        }
        remapping[this.obj] = location.obj
        _obj = location.obj
    }

    override fun remap(remapping: Remapping) {
        remapping[this.obj]?.also { _obj = it }
    }

    override fun equals(other: Any?): Boolean =
        other is ObjectFieldMemoryLocation && (this.obj === other.obj && className == other.className && fieldName == other.fieldName)

    override fun hashCode(): Int =
        Objects.hash(System.identityHashCode(this.obj), fieldName)

    override fun toString(): String =
        // TODO: also print className if it does not match actual name of the obj's class
        "${opaqueString(this.obj)}::$fieldName"

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

internal class ArrayElementMemoryLocation(
    private val strategy: ManagedStrategy,
    private var _array: Any,
    val index: Int
) : MemoryLocation {

    val array: Any get() = _array

    override val obj: Any get() = array

    override val isAtomic: Boolean = when (array) {
        is AtomicIntegerArray,
        is AtomicLongArray,
        is AtomicReferenceArray<*> -> true
        else -> false
    }

    private val getMethod by lazy {
        // TODO: can we use getOpaque() for atomic arrays here?
        getClass(strategy, array).methods.first { it.name == "get" }
            .apply { isAccessible = true }
    }

    private val setMethod by lazy {
        // TODO: can we use setOpaque() for atomic arrays here?
        getClass(strategy, array).methods.first { it.name == "set" }
            .apply { isAccessible = true }
    }

    override fun read(): Any? = when (array) {
        is IntArray     -> (array as IntArray)[index]
        is ByteArray    -> (array as ByteArray)[index]
        is ShortArray   -> (array as ShortArray)[index]
        is LongArray    -> (array as LongArray)[index]
        is FloatArray   -> (array as FloatArray)[index]
        is DoubleArray  -> (array as DoubleArray)[index]
        is CharArray    -> (array as CharArray)[index]
        is BooleanArray -> (array as BooleanArray)[index]
        is Array<*>     -> (array as Array<*>)[index]

        // TODO: can we use getOpaque() here?
        is AtomicIntegerArray       -> (array as AtomicIntegerArray)[index]
        is AtomicLongArray          -> (array as AtomicLongArray)[index]
        is AtomicReferenceArray<*>  -> (array as AtomicReferenceArray<*>)[index]

        else -> getMethod.invoke(array, index)
    }

    override fun write(value: Any?) = when (array) {
        is IntArray     -> (array as IntArray)[index]     = (value as Int)
        is ByteArray    -> (array as ByteArray)[index]    = (value as Byte)
        is ShortArray   -> (array as ShortArray)[index]   = (value as Short)
        is LongArray    -> (array as LongArray)[index]    = (value as Long)
        is FloatArray   -> (array as FloatArray)[index]   = (value as Float)
        is DoubleArray  -> (array as DoubleArray)[index]  = (value as Double)
        is CharArray    -> (array as CharArray)[index]    = (value as Char)
        is BooleanArray -> (array as BooleanArray)[index] = (value as Boolean)
        is Array<*>     -> (array as Array<Any?>)[index]  = value

        // TODO: can we use setOpaque() here?
        is AtomicIntegerArray       -> (array as AtomicIntegerArray)[index]         = (value as Int)
        is AtomicLongArray          -> (array as AtomicLongArray)[index]            = (value as Long)
        is AtomicReferenceArray<*>  -> (array as AtomicReferenceArray<Any?>)[index] = value

        else -> { setMethod.invoke(array, index, value); Unit }
    }

    override fun replay(location: MemoryLocation, remapping: Remapping) {
        check(location is ArrayElementMemoryLocation &&
            array::class == location.array::class &&
            index == location.index) {
            "Memory location $this cannot be replayed by ${location}."
        }
        remapping[array] = location.array
        _array = location.array
    }

    override fun remap(remapping: Remapping) {
        remapping[array]?.also { _array = it }
    }

    override fun equals(other: Any?): Boolean =
        other is ArrayElementMemoryLocation && (array === other.array && index == other.index)

    override fun hashCode(): Int =
        Objects.hash(System.identityHashCode(array), index)

    override fun toString(): String = "${opaqueString(array)}[$index]"
}

internal class AtomicPrimitiveMemoryLocation(
    private val strategy: ManagedStrategy,
    private var _primitive: Any
) : MemoryLocation {

    val primitive: Any get() = _primitive

    override val obj: Any get() = primitive

    override val isAtomic = true

    private val getMethod by lazy {
        // TODO: can we use getOpaque() here?
        getClass(strategy, primitive).methods.first { it.name == "get" }
            .apply { isAccessible = true }
    }

    private val setMethod by lazy {
        // TODO: can we use setOpaque() here?
        getClass(strategy, primitive).methods.first { it.name == "set" }
            .apply { isAccessible = true }
    }

    override fun read(): Any? = when (primitive) {
        // TODO: can we use getOpaque() here?
        is AtomicBoolean        -> (primitive as AtomicBoolean).get()
        is AtomicInteger        -> (primitive as AtomicInteger).get()
        is AtomicLong           -> (primitive as AtomicLong).get()
        is AtomicReference<*>   -> (primitive as AtomicReference<*>).get()
        else                    -> getMethod.invoke(primitive)
    }

    override fun write(value: Any?) = when (primitive) {
        // TODO: can we use setOpaque() here?
        is AtomicBoolean        -> (primitive as AtomicBoolean).set(value as Boolean)
        is AtomicInteger        -> (primitive as AtomicInteger).set(value as Int)
        is AtomicLong           -> (primitive as AtomicLong).set(value as Long)
        is AtomicReference<*>   -> (primitive as AtomicReference<Any>).set(value)
        else                    -> { setMethod.invoke(primitive, value); Unit }
    }

    override fun replay(location: MemoryLocation, remapping: Remapping) {
        check(location is AtomicPrimitiveMemoryLocation &&
            primitive::class == location.primitive::class) {
            "Memory location $this cannot be replayed by ${location}."
        }
        remapping[primitive] = location.primitive
        _primitive = location.primitive
    }

    override fun remap(remapping: Remapping) {
        remapping[primitive]?.also { _primitive = it }
    }

    override fun equals(other: Any?): Boolean =
        other is AtomicPrimitiveMemoryLocation && primitive === other.primitive

    override fun hashCode(): Int = System.identityHashCode(primitive)

    override fun toString(): String = opaqueString(primitive)

}

private fun matchClassName(clazz: Class<*>, className: String) =
    clazz.name.endsWith(className) || (clazz.canonicalName?.endsWith(className) ?: false)

private fun getClass(strategy: ManagedStrategy, obj: Any? = null, className: String? = null): Class<*> {
    if (className == null) {
        return obj!!.javaClass
    }
    if (obj == null) {
        return strategy.classLoader.loadClass(className)
    }
    if (matchClassName(obj.javaClass, className)) {
        return obj.javaClass
    }
    var superClass = obj.javaClass.superclass
    while (superClass != null) {
        if (matchClassName(superClass, className))
            return superClass
        superClass = superClass.superclass
    }
    throw IllegalStateException("Cannot find class $className for object $obj!")
}

private fun getField(clazz: Class<*>, className: String, fieldName: String): Field {
    var currentClass: Class<*>? = clazz
    do {
        currentClass?.fields?.firstOrNull { it.name == fieldName }?.let { return it }
        currentClass?.declaredFields?.firstOrNull { it.name == fieldName }?.let { return it }
        currentClass = currentClass?.superclass
    } while (currentClass != null)
    throw IllegalStateException("Cannot find field $className::$fieldName for class $clazz!")
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

// TODO: move to another place
// TODO: make value class?
// TODO: remapping should work with OpaqueValue?
class Remapping {

    private val map = IdentityHashMap<Any, Any>()

    operator fun get(from: Any): Any? = map[from]

    operator fun set(from: Any?, to: Any?) {
        if (from === to) return
        check(from != null && to != null) {
            "Value ${opaqueString(from)} cannot be remapped to ${opaqueString(to)} because one of them is null but not the other!"
        }
        map.put(from, to).also { old -> check(old == null || old === to) {
            "Value ${opaqueString(from)} cannot be remapped to ${opaqueString(to)} because it is already mapped to ${opaqueString(old!!)}!"
        }}
    }

    fun reset() {
        map.clear()
    }

}