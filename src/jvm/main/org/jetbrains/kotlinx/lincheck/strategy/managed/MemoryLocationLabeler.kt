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

interface MemoryLocation

/**
 * Assigns identifiers to every shared memory location
 * accessed directly or through reflections (e.g., through AFU or VarHandle).
*/
internal class MemoryLocationLabeler {
    private val fieldNameByReflection =  IdentityHashMap<Any, String>()

    fun labelStaticField(className: String, fieldName: String): MemoryLocation =
        StaticFieldMemoryLocation(className, fieldName)

    fun labelObjectField(obj: Any, fieldName: String): MemoryLocation =
        ObjectFieldMemoryLocation(obj, fieldName)

    fun labelArrayElement(array: Any, position: Int): MemoryLocation =
        ArrayElementMemoryLocation(array, position)

    fun labelAtomicPrimitive(primitive: Any): MemoryLocation =
        AtomicPrimitiveMemoryLocation(primitive)

    fun labelReflectionAccess(obj: Any, reflection: Any): MemoryLocation {
        require(fieldNameByReflection.contains(reflection)) { "AFU is used but was not registered. Do you create AFU not with AFU.newUpdater(...)?" }
        return ObjectFieldMemoryLocation(obj, fieldNameByReflection[reflection]!!)
    }

    fun registerAtomicFieldReflection(reflection: Any, fieldName: String) {
        check(!fieldNameByReflection.contains(reflection)) { "The same AFU should not be registered twice" }
        fieldNameByReflection[reflection] = fieldName
    }
    
    internal class StaticFieldMemoryLocation(val className: String, val fieldName: String) : MemoryLocation {
        override fun equals(other: Any?): Boolean =
                other is StaticFieldMemoryLocation && (className == other.className && fieldName == other.fieldName)

        override fun hashCode(): Int = Objects.hash(className, fieldName)

        override fun toString(): String = "[static access] $className.$fieldName"
    }

    internal class ObjectFieldMemoryLocation(val obj: Any, val fieldName: String) : MemoryLocation {
        override fun equals(other: Any?): Boolean =
                other is ObjectFieldMemoryLocation && (obj === other.obj && fieldName == other.fieldName)

        override fun hashCode(): Int = Objects.hash(System.identityHashCode(obj), fieldName)

        override fun toString(): String = "[object access] ${obj::class.simpleName}.$fieldName to object ${System.identityHashCode(obj)}"
    }

    internal class ArrayElementMemoryLocation(val array: Any, val index: Int) : MemoryLocation {
        override fun equals(other: Any?): Boolean =
                other is ArrayElementMemoryLocation && (array === other.array && index == other.index)

        override fun hashCode(): Int = Objects.hash(System.identityHashCode(array), index)

        override fun toString(): String = "[array access] ${array::class.simpleName}[$index] for array ${System.identityHashCode(array)}"
    }

    internal class AtomicPrimitiveMemoryLocation(val primitive: Any) : MemoryLocation {
        override fun equals(other: Any?): Boolean =
                other is AtomicPrimitiveMemoryLocation && primitive === other.primitive

        override fun hashCode(): Int = System.identityHashCode(primitive)

        override fun toString(): String = "[atomic primitive access] ${primitive::class.simpleName} (primitive ${System.identityHashCode(primitive)}) "
    }
}
