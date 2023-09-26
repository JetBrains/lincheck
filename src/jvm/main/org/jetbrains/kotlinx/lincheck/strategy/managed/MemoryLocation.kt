/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
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

import java.util.concurrent.atomic.*
import java.lang.reflect.*

typealias ObjectID = Int

// TODO: override `toString` ?
internal val INVALID_OBJECT_ID = -1
internal val NULL_OBJECT_ID = 0
internal val STATIC_OBJECT_ID = 1

typealias ObjectIDMapper = (ObjectID) -> OpaqueValue?

interface MemoryLocation {
    val objID: ObjectID

    fun read(objMapper: ObjectIDMapper): Any?
    fun write(objMapper: ObjectIDMapper, value: Any?)
}

internal class StaticFieldMemoryLocation(
    strategy: ManagedStrategy,
    val className: String,
    val fieldName: String,
) : MemoryLocation {

    override val objID: Int = STATIC_OBJECT_ID

    private val field: Field by lazy {
        resolveClass(strategy, className = className)
            .getDeclaredField(fieldName)
            .apply { isAccessible = true }
    }

    override fun read(objMapper: ObjectIDMapper): Any? {
        return field.get(null)
    }

    override fun write(objMapper: ObjectIDMapper, value: Any?) {
        field.set(null, value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        return (other is StaticFieldMemoryLocation)
                && (className == other.className)
                && (fieldName == other.fieldName)
    }

    override fun hashCode(): Int {
        var result = className.hashCode()
        result = 31 * result + fieldName.hashCode()
        return result
    }

    override fun toString(): String =
        "$className::$fieldName"

}

internal class ObjectFieldMemoryLocation(
    strategy: ManagedStrategy,
    clazz: Class<*>,
    override val objID: ObjectID,
    val className: String,
    val fieldName: String,
) : MemoryLocation {

    private val field: Field by lazy {
        val resolvedClass = resolveClass(strategy, clazz, className = className)
        resolveField(resolvedClass, className, fieldName)
            .apply { isAccessible = true }
    }


    override fun read(objMapper: ObjectIDMapper): Any? {
        return field.get(objMapper(objID))
    }

    override fun write(objMapper: ObjectIDMapper, value: Any?) {
        field.set(objMapper(objID), value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        return (other is ObjectFieldMemoryLocation)
                && (objID == other.objID)
                && (className == other.className)
                && (fieldName == other.fieldName)
    }

    override fun hashCode(): Int {
        var result = objID
        result = 31 * result + className.hashCode()
        result = 31 * result + fieldName.hashCode()
        return result
    }

    override fun toString(): String {
        check(objID != NULL_OBJECT_ID)
        return "${objRepr(className, objID)}::$fieldName"
    }

}

internal class ArrayElementMemoryLocation(
    strategy: ManagedStrategy,
    clazz: Class<*>,
    override val objID: ObjectID,
    val index: Int,
) : MemoryLocation {

    init {
        require(clazz.isArrayClass())
    }

    val className: String = clazz.simpleName

    private val getMethod by lazy {
        // TODO: can we use getOpaque() for atomic arrays here?
        resolveClass(strategy, clazz).methods
            .first { it.name == "get" }
            .apply { isAccessible = true }
    }

    private val setMethod by lazy {
        // TODO: can we use setOpaque() for atomic arrays here?
        resolveClass(strategy, clazz).methods
            .first { it.name == "set" }
            .apply { isAccessible = true }
    }

    override fun read(objMapper: ObjectIDMapper): Any? {
        return getMethod.invoke(objMapper(objID), index)
    }

    override fun write(objMapper: ObjectIDMapper, value: Any?) {
        setMethod.invoke(objMapper(objID), index, value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        return (other is ArrayElementMemoryLocation)
                && (objID == other.objID)
                && (index == other.index)
    }

    override fun hashCode(): Int {
        var result = objID
        result = 31 * result + index
        return result
    }

    override fun toString(): String {
        check(objID != NULL_OBJECT_ID)
        return "${objRepr(className, objID)}[$index]"
    }

}

internal class AtomicPrimitiveMemoryLocation(
    strategy: ManagedStrategy,
    clazz: Class<*>,
    override val objID: ObjectID,
) : MemoryLocation {

    init {
        require(clazz.isAtomicPrimitiveClass())
    }

    val className: String = clazz.simpleName

    private val getMethod by lazy {
        // TODO: can we use getOpaque() here?
        resolveClass(strategy, clazz).methods
            .first { it.name == "get" }
            .apply { isAccessible = true }
    }

    private val setMethod by lazy {
        // TODO: can we use setOpaque() here?
        resolveClass(strategy, clazz).methods
            .first { it.name == "set" }
            .apply { isAccessible = true }
    }

    override fun read(objMapper: ObjectIDMapper): Any? {
        return getMethod.invoke(objMapper(objID))
    }

    override fun write(objMapper: ObjectIDMapper, value: Any?) {
        setMethod.invoke(objMapper(objID), value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        return (other is AtomicPrimitiveMemoryLocation)
                && (objID == other.objID)
    }

    override fun hashCode(): Int {
        return objID
    }

    override fun toString(): String {
        check(objID != NULL_OBJECT_ID)
        return objRepr(className, objID)
    }

}

private fun objRepr(className: String, objID: ObjectID): String {
    return when (objID) {
        NULL_OBJECT_ID -> "null"
        STATIC_OBJECT_ID -> className
        else -> "$className@$objID"
    }
}

private fun matchClassName(clazz: Class<*>, className: String) =
    clazz.name.endsWith(className) || (clazz.canonicalName?.endsWith(className) ?: false)

private fun resolveClass(strategy: ManagedStrategy, clazz: Class<*>? = null, className: String? = null): Class<*> {
    if (className == null) {
        check(clazz != null)
        return clazz
    }
    if (clazz == null) {
        return strategy.classLoader.loadClass(className)
    }
    if (matchClassName(clazz, className)) {
        return clazz
    }
    var superClass = clazz.superclass
    while (superClass != null) {
        if (matchClassName(superClass, className))
            return superClass
        superClass = superClass.superclass
    }
    throw IllegalStateException("Cannot find class $className for object of class ${clazz.name}!")
}

private fun resolveField(clazz: Class<*>, className: String, fieldName: String): Field {
    var currentClass: Class<*>? = clazz
    do {
        currentClass?.fields?.firstOrNull { it.name == fieldName }?.let { return it }
        currentClass?.declaredFields?.firstOrNull { it.name == fieldName }?.let { return it }
        currentClass = currentClass?.superclass
    } while (currentClass != null)
    throw IllegalStateException("Cannot find field $className::$fieldName for class $clazz!")
}

private fun Class<*>.isArrayClass(): Boolean = when (this) {
    ByteArray::class.java,
    ShortArray::class.java,
    IntArray::class.java,
    LongArray::class.java,
    FloatArray::class.java,
    DoubleArray::class.java,
    CharArray::class.java,
    BooleanArray::class.java,
    Array::class.java,
    AtomicIntegerArray::class.java,
    AtomicLongArray::class.java,
    AtomicReferenceArray::class.java
            -> true
    else    -> false
}

private fun Class<*>.isAtomicPrimitiveClass(): Boolean = when (this) {
    AtomicBoolean::class.java,
    AtomicInteger::class.java,
    AtomicLong::class.java,
    AtomicReference::class.java
            -> true
    else    -> false
}