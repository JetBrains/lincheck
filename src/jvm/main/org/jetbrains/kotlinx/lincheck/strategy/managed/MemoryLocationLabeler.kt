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

internal class StaticFieldMemoryLocation(val className: String, val fieldName: String) : MemoryLocation {

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

    override fun equals(other: Any?): Boolean =
        other is StaticFieldMemoryLocation && (className == other.className && fieldName == other.fieldName)

    override fun hashCode(): Int =
        Objects.hash(className, fieldName)

    override fun toString(): String = "$className.$fieldName"

}

internal class ObjectFieldMemoryLocation(val obj: Any, val className: String, val fieldName: String,
                                         override val isAtomic: Boolean = false) : MemoryLocation {

    private val field by lazy {
        // TODO: is it correct to use this class loader here?
        val classLoader = (ManagedStrategyStateHolder.strategy as ManagedStrategy).classLoader
        classLoader.loadClass(className).getDeclaredField(fieldName).apply { isAccessible = true }
    }

    override fun read(): Any? = field.get(obj)

    override fun write(value: Any?) {
        field.set(obj, value)
    }

    override fun equals(other: Any?): Boolean =
        other is ObjectFieldMemoryLocation && (obj === other.obj && className == other.className && fieldName == other.fieldName)

    override fun hashCode(): Int =
        Objects.hash(System.identityHashCode(obj), fieldName)

    override fun toString(): String =
        "${obj::class.simpleName}@${System.identityHashCode(obj)}.$fieldName"

}

internal class ArrayElementMemoryLocation(val array: Any, val index: Int) : MemoryLocation {

    override val isAtomic: Boolean = when (array) {
        is AtomicIntegerArray,
        is AtomicLongArray,
        is AtomicReferenceArray<*> -> true
        else -> false
    }

    override fun read(): Any? = when (array) {
        is IntArray     -> array[index]
        is ByteArray    -> array[index]
        is ShortArray   -> array[index]
        is LongArray    -> array[index]
        is FloatArray   -> array[index]
        is DoubleArray  -> array[index]
        is CharArray    -> array[index]
        is BooleanArray -> array[index]
        // TODO: can we use getOpaque() here?
        is AtomicIntegerArray       -> array[index]
        is AtomicLongArray          -> array[index]
        is AtomicReferenceArray<*>  -> array[index]

        else -> throw IllegalStateException("Object $array is not array!")
    }

    override fun write(value: Any?) = when (array) {
        is IntArray     -> array[index] = (value as Int)
        is ByteArray    -> array[index] = (value as Byte)
        is ShortArray   -> array[index] = (value as Short)
        is LongArray    -> array[index] = (value as Long)
        is FloatArray   -> array[index] = (value as Float)
        is DoubleArray  -> array[index] = (value as Double)
        is CharArray    -> array[index] = (value as Char)
        is BooleanArray -> array[index] = (value as Boolean)
        // TODO: can we use setOpaque() here?
        is AtomicIntegerArray       -> array[index] = (value as Int)
        is AtomicLongArray          -> array[index] = (value as Long)
        is AtomicReferenceArray<*>  -> (array as AtomicReferenceArray<Any>)[index] = value

        else -> throw IllegalStateException("Object $array is not array!")
    }

    override fun equals(other: Any?): Boolean =
        other is ArrayElementMemoryLocation && (array === other.array && index == other.index)

    override fun hashCode(): Int =
        Objects.hash(System.identityHashCode(array), index)

    override fun toString(): String =
        "${array::class.simpleName}@${System.identityHashCode(array)}[$index]"
}

internal class AtomicPrimitiveMemoryLocation(val primitive: Any) : MemoryLocation {

    override val isAtomic: Boolean = true

    override fun read(): Any? = when (primitive) {
        // TODO: can we use getOpaque() here?
        is AtomicBoolean        -> primitive.get()
        is AtomicInteger        -> primitive.get()
        is AtomicLong           -> primitive.get()
        is AtomicReference<*>   -> primitive.get()
        else                    -> throw IllegalStateException("Primitive $primitive is not atomic!")
    }

    override fun write(value: Any?) = when (primitive) {
        // TODO: can we use setOpaque() here?
        is AtomicBoolean        -> primitive.set(value as Boolean)
        is AtomicInteger        -> primitive.set(value as Int)
        is AtomicLong           -> primitive.set(value as Long)
        is AtomicReference<*>   -> (primitive as AtomicReference<Any>).set(value)
        else                    -> throw IllegalStateException("Primitive $primitive is not atomic!")
    }

    override fun equals(other: Any?): Boolean =
        other is AtomicPrimitiveMemoryLocation && primitive === other.primitive

    override fun hashCode(): Int = System.identityHashCode(primitive)

    override fun toString(): String =
        "${primitive::class.simpleName}@${System.identityHashCode(primitive)}"

}