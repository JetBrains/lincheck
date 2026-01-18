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

import org.jetbrains.lincheck.trace.TraceContext
import org.jetbrains.lincheck.descriptors.*
import org.jetbrains.lincheck.util.*
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.*
import kotlin.reflect.KClass

typealias ValueMapper = (Types.Type, ValueID) -> OpaqueValue?

interface MemoryLocation {
    val objID: ObjectID
    val type: Types.Type

    fun read(valueMapper: ValueMapper): Any?
    fun write(value: Any?, valueMapper: ValueMapper)
}

val MemoryLocation.kClass: KClass<*>
    get() = type.getKClass()

fun ObjectTracker.getFieldAccessMemoryLocation(
    obj: Any?,
    className: String,
    fieldName: String,
    type: Types.Type,
    isStatic: Boolean,
    isFinal: Boolean,
): MemoryLocation {
    if (isStatic) {
        return StaticFieldMemoryLocation(className, fieldName, type)
    }
    val clazz = obj!!.javaClass
    // TODO: can this be null?
    val id = get(obj)!!.objectId
    return ObjectFieldMemoryLocation(clazz, id, clazz.name, fieldName, type)
}

fun ObjectTracker.getArrayAccessMemoryLocation(array: Any, index: Int, type: Types.Type): MemoryLocation {
    val clazz = array.javaClass
    // TODO: can this be null?
    val id = get(array)!!.objectId
    return ArrayElementMemoryLocation(clazz, id, index, type)
}

internal fun ObjectTracker.getAtomicAccessMemoryLocation(
    context: TraceContext,
    className: String,
    methodName: String,
    receiver: Any,
    params: Array<Any?>,
    atomicMethodDescriptor: AtomicMethodDescriptor,
): MemoryLocation? {
    val info = atomicMethodDescriptor.getAtomicAccessInfo(context, receiver, params)
    val accessLocation = info.location
    return when (atomicMethodDescriptor.apiKind) {
        AtomicApiKind.ATOMIC_OBJECT -> {
            AtomicPrimitiveMemoryLocation(
                clazz = info.clazz!!,
                objID = get(info.obj)!!.objectId,
                type = getAtomicType(receiver)!!,
            )
        }
        AtomicApiKind.ATOMIC_ARRAY -> {
            check(accessLocation is ArrayElementByIndexAccessLocation)
            getArrayAccessMemoryLocation(
                array = info.obj!!,
                index = accessLocation.index,
                type = getAtomicArrayType(receiver)!!,
            )
        }
        AtomicApiKind.ATOMIC_FIELD_UPDATER -> {
            check(accessLocation is FieldAccessLocation)
            getFieldAccessMemoryLocation(
                obj = info.obj,
                className = accessLocation.className,
                fieldName = accessLocation.fieldName,
                type = Types.INT_TYPE,
                isStatic = (accessLocation is StaticFieldAccessLocation),
                isFinal = false, // TODO: fixme?
            )
        }
        AtomicApiKind.VAR_HANDLE -> when (accessLocation) {
            is FieldAccessLocation -> {
                getFieldAccessMemoryLocation(
                    obj = info.obj,
                    className = accessLocation.className,
                    fieldName = accessLocation.fieldName,
                    type = getVarHandleAccessType(receiver)!!,
                    isStatic = (accessLocation is StaticFieldAccessLocation),
                    isFinal = false, // TODO: fixme?
                )
            }
            is ArrayElementByIndexAccessLocation -> {
                getArrayAccessMemoryLocation(
                    array = info.obj!!,
                    index = accessLocation.index,
                    type = getVarHandleAccessType(receiver)!!,
                )
            }
            else -> unreachable("Unexpected access location: $accessLocation")
        }
        AtomicApiKind.UNSAFE -> when (accessLocation) {
            is FieldAccessLocation -> {
                getFieldAccessMemoryLocation(
                    obj = info.obj,
                    className = accessLocation.className,
                    fieldName = accessLocation.fieldName,
                    type = parseUnsafeMethodAccessType(methodName)!!,
                    isStatic = (accessLocation is StaticFieldAccessLocation),
                    isFinal = false, // TODO: fixme?
                )
            }
            is ArrayElementByIndexAccessLocation -> {
                getArrayAccessMemoryLocation(
                    array = info.obj!!,
                    index = accessLocation.index,
                    type = parseUnsafeMethodAccessType(methodName)!!,
                )
            }
            else -> unreachable("Unexpected access location: $accessLocation")
        }
    }
}

class StaticFieldMemoryLocation(
    val className: String,
    val fieldName: String,
    override val type: Types.Type,
) : MemoryLocation {

    override val objID: ObjectID = STATIC_OBJECT_ID

    private val field: Field by lazy {
        val resolvedClass = resolveClass(className = className)
        resolveField(resolvedClass, className, fieldName)
            // .apply { isAccessible = true }
    }

    override fun read(valueMapper: ValueMapper): Any? {
        // return field.get(null)
        return readFieldViaUnsafe(null, field)
    }

    override fun write(value: Any?, valueMapper: ValueMapper) {
        // field.set(null, value)
        writeFieldViaUnsafe(null, field, value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        return (other is StaticFieldMemoryLocation)
                && (className == other.className)
                && (fieldName == other.fieldName)
                && (kClass == other.kClass)
    }

    override fun hashCode(): Int {
        var result = className.hashCode()
        result = 31 * result + fieldName.hashCode()
        return result
    }

    override fun toString(): String =
        "$className::$fieldName"

}

class ObjectFieldMemoryLocation(
    clazz: Class<*>,
    override val objID: ObjectID,
    val className: String,
    val fieldName: String,
    override val type: Types.Type,
) : MemoryLocation {

    init {
        check(objID != NULL_OBJECT_ID)
    }

    val simpleClassName: String = clazz.simpleName

    private val field: Field by lazy {
        val resolvedClass = resolveClass(clazz, className = className)
        resolveField(resolvedClass, className, fieldName)
            // .apply { isAccessible = true }
    }

    override fun read(valueMapper: ValueMapper): Any? {
        // return field.get(valueMapper(OBJECT_TYPE, objID)?.unwrap())
        return readFieldViaUnsafe(valueMapper(Types.OBJECT_TYPE, objID)?.unwrap(), field)
    }

    override fun write(value: Any?, valueMapper: ValueMapper) {
        // field.set(valueMapper(OBJECT_TYPE, objID)?.unwrap(), value)
        writeFieldViaUnsafe(valueMapper(Types.OBJECT_TYPE, objID)?.unwrap(), field, value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        return (other is ObjectFieldMemoryLocation)
                && (objID == other.objID)
                && (className == other.className)
                && (fieldName == other.fieldName)
                && (kClass == other.kClass)
    }

    override fun hashCode(): Int {
        var result = objID.hashCode()
        result = 31 * result + className.hashCode()
        result = 31 * result + fieldName.hashCode()
        return result
    }

    override fun toString(): String {
        return "${objRepr(simpleClassName, objID)}::$fieldName"
    }

}

class ArrayElementMemoryLocation(
    clazz: Class<*>,
    override val objID: ObjectID,
    val index: Int,
    override val type: Types.Type,
) : MemoryLocation {

    init {
        check(objID != NULL_OBJECT_ID)
    }

    val className: String = clazz.simpleName

    private val isPlainArray = clazz.isArray

    private val getMethod: Method? by lazy {
        if (isPlainArray) {
            return@lazy null
        }
        val resolvedClass = resolveClass(clazz)
        return@lazy resolvedClass.methods
            // TODO: can we use getOpaque() for atomic arrays here?
            .first { it.name == "get" }
            .apply { isAccessible = true }
    }

    private val setMethod by lazy {
        if (isPlainArray) {
            return@lazy null
        }
        val resolvedClass = resolveClass(clazz)
        return@lazy resolvedClass.methods
            // TODO: can we use setOpaque() for atomic arrays here?
            .first { it.name == "set" }
            .apply { isAccessible = true }
    }

    override fun read(valueMapper: ValueMapper): Any? {
        // TODO: also use unsafe?
        if (isPlainArray) {
            return ReflectArray.get(valueMapper(Types.OBJECT_TYPE, objID)?.unwrap(), index)
        }
        return getMethod!!.invoke(valueMapper(Types.OBJECT_TYPE, objID)?.unwrap(), index)
    }

    override fun write(value: Any?, valueMapper: ValueMapper) {
        if (isPlainArray) {
            ReflectArray.set(valueMapper(Types.OBJECT_TYPE, objID)?.unwrap(), index, value)
            return
        }
        setMethod!!.invoke(valueMapper(Types.OBJECT_TYPE, objID)?.unwrap(), index, value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        return (other is ArrayElementMemoryLocation)
                && (objID == other.objID)
                && (index == other.index)
                && (kClass == other.kClass)
    }

    override fun hashCode(): Int {
        var result = objID.hashCode()
        result = 31 * result + index
        return result
    }

    override fun toString(): String {
        return "${objRepr(className, objID)}[$index]"
    }

}

class AtomicPrimitiveMemoryLocation(
    clazz: Class<*>,
    override val objID: ObjectID,
    override val type: Types.Type,
) : MemoryLocation {

    init {
        require(objID != NULL_OBJECT_ID)
    }

    val className: String = clazz.simpleName

    private val getMethod by lazy {
        // TODO: can we use getOpaque() here?
        resolveClass(clazz).methods
            .first { it.name == "get" }
            .apply { isAccessible = true }
    }

    private val setMethod by lazy {
        // TODO: can we use setOpaque() here?
        resolveClass(clazz).methods
            .first { it.name == "set" }
            .apply { isAccessible = true }
    }

    override fun read(valueMapper: ValueMapper): Any? {
        // TODO: also use unsafe?
        return getMethod.invoke(valueMapper(Types.OBJECT_TYPE, objID)?.unwrap())
    }

    override fun write(value: Any?, valueMapper: ValueMapper) {
        // TODO: also use unsafe?
        setMethod.invoke(valueMapper(Types.OBJECT_TYPE, objID)?.unwrap(), value)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other)
            return true
        return (other is AtomicPrimitiveMemoryLocation)
                && (objID == other.objID)
                && (kClass == other.kClass)
    }

    override fun hashCode(): Int {
        return objID.hashCode()
    }

    override fun toString(): String {
        check(objID != NULL_OBJECT_ID)
        return objRepr(className, objID)
    }

}

internal fun objRepr(className: String, objID: ObjectID): String {
    return when (objID) {
        NULL_OBJECT_ID -> "null"
        else -> "$className@$objID"
    }
}

private fun matchClassName(clazz: Class<*>, className: String) =
    clazz.name.endsWith(className) || (clazz.canonicalName?.endsWith(className) ?: false)

private fun resolveClass(clazz: Class<*>? = null, className: String? = null): Class<*> {
    if (className == null) {
        check(clazz != null)
        return clazz
    }
    if (clazz == null) {
        return Class.forName(className)
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