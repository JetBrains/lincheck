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
import java.lang.reflect.*
import java.util.concurrent.atomic.*
import org.jetbrains.kotlinx.lincheck.*

interface MemoryLocation {
    // TODO: rename?
    val recipient: Any?

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
    private val fieldLocationByReflection = IdentityHashMap<Any, AtomicReflectionAccessDescriptor>()

    fun labelStaticField(strategy: ManagedStrategy, className: String, fieldName: String): MemoryLocation =
        StaticFieldMemoryLocation(strategy, className, fieldName)

    fun labelObjectField(strategy: ManagedStrategy, obj: Any, className: String, fieldName: String): MemoryLocation =
        ObjectFieldMemoryLocation(strategy, obj, className, fieldName)

    fun labelArrayElement(strategy: ManagedStrategy, array: Any, position: Int): MemoryLocation =
        ArrayElementMemoryLocation(strategy, array, position)

    fun labelAtomicPrimitive(strategy: ManagedStrategy, primitive: Any): MemoryLocation =
        AtomicPrimitiveMemoryLocation(strategy, primitive)

    fun labelAtomicReflectionFieldAccess(strategy: ManagedStrategy, reflection: Any, obj: Any): MemoryLocation {
        val descriptor = getAtomicReflectionDescriptor(reflection) as AtomicReflectionFieldAccessDescriptor
        return ObjectFieldMemoryLocation(strategy, obj, descriptor.className, descriptor.fieldName, isAtomic = true)
    }

    fun labelAtomicReflectionArrayAccess(strategy: ManagedStrategy, reflection: Any, array: Any, index: Int): MemoryLocation {
        check(getAtomicReflectionDescriptor(reflection) is AtomicReflectionArrayAccessDescriptor)
        return ArrayElementMemoryLocation(strategy, array, index)
    }

    fun registerAtomicFieldReflection(strategy: ManagedStrategy, reflection: Any, clazz: Class<*>, fieldName: String) {
        val className = (strategy.classLoader as TransformationClassLoader).removeRemappingPrefix(clazz.name)
        val descriptor = AtomicReflectionFieldAccessDescriptor(className, fieldName)
        registerAtomicReflectionDescriptor(reflection, descriptor)
    }

    fun registerAtomicArrayReflection(strategy: ManagedStrategy, reflection: Any, elemClazz: Class<*>) {
        val elemClassName = (strategy.classLoader as TransformationClassLoader).removeRemappingPrefix(elemClazz.name)
        val descriptor = AtomicReflectionArrayAccessDescriptor(elemClassName)
        registerAtomicReflectionDescriptor(reflection, descriptor)
    }

    private fun registerAtomicReflectionDescriptor(reflection: Any, descriptor: AtomicReflectionAccessDescriptor) {
        fieldLocationByReflection.put(reflection, descriptor).also {
            check(it == null) {
                "Atomic reflection object ${opaqueString(reflection)} cannot be registered to access $descriptor," +
                    "because it is already registered to access $it!"
            }
        }
    }

    private fun getAtomicReflectionDescriptor(reflection: Any): AtomicReflectionAccessDescriptor {
        val descriptor = fieldLocationByReflection[reflection]
        check(descriptor != null) {
            "Cannot access memory via unregistered reflection object ${opaqueString(reflection)}"
        }
        return descriptor
    }

}

internal class StaticFieldMemoryLocation(
    private val strategy: ManagedStrategy,
    val className: String,
    val fieldName: String
) : MemoryLocation {

    override val recipient = null

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

internal class ObjectFieldMemoryLocation(
    private val strategy: ManagedStrategy,
    private var _obj: Any,
    val className: String,
    val fieldName: String,
    override val isAtomic: Boolean = false
) : MemoryLocation {

    val obj: Any get() = _obj

    override val recipient get() = obj

    private val field by lazy {
        val clazz = getClass(strategy, obj, className = className)
        getField(clazz, className, fieldName)
            .apply { isAccessible = true }
    }

    override fun read(): Any? = field.get(obj)

    override fun write(value: Any?) {
        field.set(obj, value)
    }

    override fun replay(location: MemoryLocation, remapping: Remapping) {
        check(location is ObjectFieldMemoryLocation &&
            obj::class == location.obj::class &&
            className == location.className &&
            fieldName == location.fieldName) {
            "Memory location $this cannot be replayed by ${location}."
        }
        remapping[obj] = location.obj
        _obj = location.obj
    }

    override fun remap(remapping: Remapping) {
        remapping[obj]?.also { _obj = it }
    }

    override fun equals(other: Any?): Boolean =
        other is ObjectFieldMemoryLocation && (obj === other.obj && className == other.className && fieldName == other.fieldName)

    override fun hashCode(): Int =
        Objects.hash(System.identityHashCode(obj), fieldName)

    override fun toString(): String =
        // TODO: also print className if it does not match actual name of the obj's class
        "${opaqueString(obj)}::$fieldName"

}

internal class ArrayElementMemoryLocation(
    private val strategy: ManagedStrategy,
    private var _array: Any,
    val index: Int
) : MemoryLocation {

    val array: Any get() = _array

    override val recipient get() = array

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

    override val recipient get() = primitive

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