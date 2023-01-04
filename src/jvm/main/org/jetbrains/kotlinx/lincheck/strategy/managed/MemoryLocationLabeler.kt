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
import java.util.concurrent.atomic.*

interface MemoryLocation {
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
    private val fieldLocationByReflection =  IdentityHashMap<Any, Pair<String, String>>()

    fun labelStaticField(className: String, fieldName: String): MemoryLocation =
        StaticFieldMemoryLocation(className, fieldName)

    fun labelObjectField(obj: Any, className: String, fieldName: String): MemoryLocation =
        ObjectFieldMemoryLocation(obj, className, fieldName)

    fun labelArrayElement(array: Any, position: Int): MemoryLocation =
        ArrayElementMemoryLocation(array, position)

    fun labelAtomicPrimitive(primitive: Any): MemoryLocation =
        AtomicPrimitiveMemoryLocation(primitive)

    fun labelAtomicReflectionAccess(reflection: Any, obj: Any): MemoryLocation {
        val (className, fieldName) = fieldLocationByReflection[reflection].let {
            check(it != null) {
                "AFU is used but was not registered. Do you create AFU not with AFU.newUpdater(...)?"
            }
            it
        }
        return ObjectFieldMemoryLocation(obj, className, fieldName, isAtomic = true)
    }

    fun registerAtomicFieldReflection(reflection: Any, clazz: Class<*>, fieldName: String) {
        fieldLocationByReflection.put(reflection, Pair(clazz.canonicalName, fieldName)).also {
            check(it == null) {
                "The same AFU should not be registered twice"
            }
        }
    }

}

internal class StaticFieldMemoryLocation(
    val className: String,
    val fieldName: String
) : MemoryLocation {

    override val isAtomic: Boolean = false

    private val field by lazy {
        // TODO: is it correct to use this class loader here?
        val classLoader = (ManagedStrategyStateHolder.strategy as ManagedStrategy).classLoader
        classLoader.loadClass(className).getDeclaredField(fieldName).apply { isAccessible = true }
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
    private var _obj: Any,
    val className: String,
    val fieldName: String,
    override val isAtomic: Boolean = false
) : MemoryLocation {

    val obj: Any get() = _obj

    private val field by lazy {
        // TODO: is it correct to use this class loader here?
        val classLoader = (ManagedStrategyStateHolder.strategy as ManagedStrategy).classLoader
        classLoader.loadClass(className).getDeclaredField(fieldName).apply { isAccessible = true }
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

    override fun toString(): String = "${opaqueString(obj)}.$className::$fieldName"

}

internal class ArrayElementMemoryLocation(
    private var _array: Any,
    val index: Int
) : MemoryLocation {

    val array: Any get() = _array

    override val isAtomic: Boolean = when (array) {
        is AtomicIntegerArray,
        is AtomicLongArray,
        is AtomicReferenceArray<*> -> true
        else -> false
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
        // TODO: can we use getOpaque() here?
        is AtomicIntegerArray       -> (array as AtomicIntegerArray)[index]
        is AtomicLongArray          -> (array as AtomicLongArray)[index]
        is AtomicReferenceArray<*>  -> (array as AtomicReferenceArray<*>)[index]

        else -> throw IllegalStateException("Object $array is not array!")
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
        // TODO: can we use setOpaque() here?
        is AtomicIntegerArray       -> (array as AtomicIntegerArray)[index]        = (value as Int)
        is AtomicLongArray          -> (array as AtomicLongArray)[index]           = (value as Long)
        is AtomicReferenceArray<*>  -> (array as AtomicReferenceArray<Any>)[index] = value

        else -> throw IllegalStateException("Object $array is not array!")
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
    private var _primitive: Any
) : MemoryLocation {

    val primitive: Any get() = _primitive

    override val isAtomic: Boolean = true

    override fun read(): Any? = when (primitive) {
        // TODO: can we use getOpaque() here?
        is AtomicBoolean        -> (primitive as AtomicBoolean).get()
        is AtomicInteger        -> (primitive as AtomicInteger).get()
        is AtomicLong           -> (primitive as AtomicLong).get()
        is AtomicReference<*>   -> (primitive as AtomicReference<*>).get()
        else                    -> throw IllegalStateException("Primitive $primitive is not atomic!")
    }

    override fun write(value: Any?) = when (primitive) {
        // TODO: can we use setOpaque() here?
        is AtomicBoolean        -> (primitive as AtomicBoolean).set(value as Boolean)
        is AtomicInteger        -> (primitive as AtomicInteger).set(value as Int)
        is AtomicLong           -> (primitive as AtomicLong).set(value as Long)
        is AtomicReference<*>   -> (primitive as AtomicReference<Any>).set(value)
        else                    -> throw IllegalStateException("Primitive $primitive is not atomic!")
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

// TODO: move to another place
// TODO: make value class?
// TODO: remapping should work with OpaqueValue?
class Remapping {

    private val map = mutableMapOf<Any, Any>()

    operator fun get(from: Any): Any? = map[from]

    operator fun set(from: Any?, to: Any?) {
        if (from === to) return
        check(from != null && to != null) {
            "Value ${opaqueString(from)} cannot be remapped to ${opaqueString(to)} because one of them is null but not the other!"
        }
        map.put(from, to).also { old -> check(old == null || old == to) {
            "Value ${opaqueString(from)} cannot be remapped to ${opaqueString(to)} because it is already mapped to ${opaqueString(old!!)}!"
        }}
    }

    fun reset() {
        map.clear()
    }

}