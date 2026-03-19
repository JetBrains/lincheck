/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.util

import org.jetbrains.lincheck.descriptors.FieldKind
import java.util.concurrent.ConcurrentHashMap
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Modifier
import java.lang.reflect.Field
import sun.misc.Unsafe

object UnsafeHolder {
    val UNSAFE: Unsafe = try {
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        unsafeField.get(null) as Unsafe
    } catch (ex: Exception) {
        throw RuntimeException("Can't get the Unsafe instance, please report it to the Lincheck team", ex)
    }
}

val fieldOffsetCache = ConcurrentHashMap<Field, Long>()
val fieldBaseObjectCache = ConcurrentHashMap<Field, Any>()

@Suppress("DEPRECATION")
inline fun <T> readFieldViaUnsafe(obj: Any?, field: Field, getter: Unsafe.(Any?, Long) -> T): T {
    if (Modifier.isStatic(field.modifiers)) {
        val base = fieldBaseObjectCache.computeIfAbsent(field) {
            UnsafeHolder.UNSAFE.staticFieldBase(it)
        }
        val offset = fieldOffsetCache.computeIfAbsent(field) {
            UnsafeHolder.UNSAFE.staticFieldOffset(it)
        }
        return UnsafeHolder.UNSAFE.getter(base, offset)
    } else {
        val offset = fieldOffsetCache.computeIfAbsent(field) {
            UnsafeHolder.UNSAFE.objectFieldOffset(it)
        }
        return UnsafeHolder.UNSAFE.getter(obj, offset)
    }
}

fun readFieldViaUnsafe(obj: Any?, field: Field): Any? {
    if (!field.type.isPrimitive) {
        return readFieldViaUnsafe(obj, field, Unsafe::getObject)
    }
    return when (field.type) {
        Boolean::class.javaPrimitiveType    -> readFieldViaUnsafe(obj, field, Unsafe::getBoolean)
        Byte::class.javaPrimitiveType       -> readFieldViaUnsafe(obj, field, Unsafe::getByte)
        Char::class.javaPrimitiveType       -> readFieldViaUnsafe(obj, field, Unsafe::getChar)
        Short::class.javaPrimitiveType      -> readFieldViaUnsafe(obj, field, Unsafe::getShort)
        Int::class.javaPrimitiveType        -> readFieldViaUnsafe(obj, field, Unsafe::getInt)
        Long::class.javaPrimitiveType       -> readFieldViaUnsafe(obj, field, Unsafe::getLong)
        Double::class.javaPrimitiveType     -> readFieldViaUnsafe(obj, field, Unsafe::getDouble)
        Float::class.javaPrimitiveType      -> readFieldViaUnsafe(obj, field, Unsafe::getFloat)
        else                                -> error("No more types expected")
    }
}

/**
 * Reads a [field] of the owner object [obj] via Unsafe,
 * in case of failure fallbacks into reading the field via reflection.
 */
fun readFieldSafely(obj: Any?, field: Field): Result<Any?> =
    // we wrap an unsafe read into `runCatching` to handle `UnsupportedOperationException`,
    // which can be thrown, for instance, when attempting to read
    // a field of a hidden or record class (starting from Java 15);
    // in this case we fall back to read via reflection
    runCatching {
        readFieldViaUnsafe(obj, field)
    }
    .onFailure { exception ->
        Logger.debug(exception) { "Failed to read field ${field.name} via Unsafe" }
    }
    .recoverCatching {
        field.apply { isAccessible = true }.get(obj)
    }
    .onFailure { exception ->
        Logger.debug(exception) { "Failed to read field ${field.name} via reflection." }
    }

fun readArrayElementViaUnsafe(arr: Any, index: Int): Any? {
    val offset = getArrayElementOffsetViaUnsafe(arr, index)
    val componentType = arr::class.java.componentType

    if (!componentType.isPrimitive) {
        return UnsafeHolder.UNSAFE.getObject(arr, offset)
    }

    return when (componentType) {
        Boolean::class.javaPrimitiveType    -> UnsafeHolder.UNSAFE.getBoolean(arr, offset)
        Byte::class.javaPrimitiveType       -> UnsafeHolder.UNSAFE.getByte(arr, offset)
        Char::class.javaPrimitiveType       -> UnsafeHolder.UNSAFE.getChar(arr, offset)
        Short::class.javaPrimitiveType      -> UnsafeHolder.UNSAFE.getShort(arr, offset)
        Int::class.javaPrimitiveType        -> UnsafeHolder.UNSAFE.getInt(arr, offset)
        Long::class.javaPrimitiveType       -> UnsafeHolder.UNSAFE.getLong(arr, offset)
        Double::class.javaPrimitiveType     -> UnsafeHolder.UNSAFE.getDouble(arr, offset)
        Float::class.javaPrimitiveType      -> UnsafeHolder.UNSAFE.getFloat(arr, offset)
        else                                -> error("No more primitive types expected")
    }
}

fun getArrayElementOffsetViaUnsafe(arr: Any, index: Int): Long {
    val clazz = arr::class.java
    val baseOffset = UnsafeHolder.UNSAFE.arrayBaseOffset(clazz).toLong()
    val indexScale = UnsafeHolder.UNSAFE.arrayIndexScale(clazz).toLong()
    return baseOffset + index * indexScale
}

@Suppress("DEPRECATION")
inline fun writeFieldViaUnsafe(obj: Any?, field: Field, value: Any?, setter: Unsafe.(Any?, Long, Any?) -> Unit) {
    if (Modifier.isStatic(field.modifiers)) {
        val base = UnsafeHolder.UNSAFE.staticFieldBase(field)
        val offset = UnsafeHolder.UNSAFE.staticFieldOffset(field)
        return UnsafeHolder.UNSAFE.setter(base, offset, value)
    } else {
        val offset = UnsafeHolder.UNSAFE.objectFieldOffset(field)
        return UnsafeHolder.UNSAFE.setter(obj, offset, value)
    }
}

@Suppress("NAME_SHADOWING")
fun writeFieldViaUnsafe(obj: Any?, field: Field, value: Any?) {
    if (!field.type.isPrimitive) {
        return writeFieldViaUnsafe(obj, field, value, Unsafe::putObject)
    }
    return when (field.type) {
        Boolean::class.javaPrimitiveType    -> writeFieldViaUnsafe(obj, field, value) { obj, field, value -> putBoolean(obj, field, value as Boolean) }
        Byte::class.javaPrimitiveType       -> writeFieldViaUnsafe(obj, field, value) { obj, field, value -> putByte(obj, field, value as Byte) }
        Char::class.javaPrimitiveType       -> writeFieldViaUnsafe(obj, field, value) { obj, field, value -> putChar(obj, field, value as Char) }
        Short::class.javaPrimitiveType      -> writeFieldViaUnsafe(obj, field, value) { obj, field, value -> putShort(obj, field, value as Short) }
        Int::class.javaPrimitiveType        -> writeFieldViaUnsafe(obj, field, value) { obj, field, value -> putInt(obj, field, value as Int) }
        Long::class.javaPrimitiveType       -> writeFieldViaUnsafe(obj, field, value) { obj, field, value -> putLong(obj, field, value as Long) }
        Double::class.javaPrimitiveType     -> writeFieldViaUnsafe(obj, field, value) { obj, field, value -> putDouble(obj, field, value as Double) }
        Float::class.javaPrimitiveType      -> writeFieldViaUnsafe(obj, field, value) { obj, field, value -> putFloat(obj, field, value as Float) }
        else                                -> error("No more types expected")
    }
}

fun writeArrayElementViaUnsafe(arr: Any, index: Int, value: Any?): Any? {
    val offset = getArrayElementOffsetViaUnsafe(arr, index)
    val componentType = arr::class.java.componentType

    if (!componentType.isPrimitive) {
        return UnsafeHolder.UNSAFE.putObject(arr, offset, value)
    }

    return when (componentType) {
        Boolean::class.javaPrimitiveType    -> UnsafeHolder.UNSAFE.putBoolean(arr, offset, value as Boolean)
        Byte::class.javaPrimitiveType       -> UnsafeHolder.UNSAFE.putByte(arr, offset, value as Byte)
        Char::class.javaPrimitiveType       -> UnsafeHolder.UNSAFE.putChar(arr, offset, value as Char)
        Short::class.javaPrimitiveType      -> UnsafeHolder.UNSAFE.putShort(arr, offset, value as Short)
        Int::class.javaPrimitiveType        -> UnsafeHolder.UNSAFE.putInt(arr, offset, value as Int)
        Long::class.javaPrimitiveType       -> UnsafeHolder.UNSAFE.putLong(arr, offset, value as Long)
        Double::class.javaPrimitiveType     -> UnsafeHolder.UNSAFE.putDouble(arr, offset, value as Double)
        Float::class.javaPrimitiveType      -> UnsafeHolder.UNSAFE.putFloat(arr, offset, value as Float)
        else                                -> error("No more primitive types expected")
    }
}

@Suppress("DEPRECATION")
fun getFieldOffsetViaUnsafe(field: Field): Long {
    return if (Modifier.isStatic(field.modifiers)) {
        UnsafeHolder.UNSAFE.staticFieldOffset(field)
    }
    else {
        UnsafeHolder.UNSAFE.objectFieldOffset(field)
    }
}

private val fieldDescriptorByOffsetCache = ConcurrentHashMap<Pair<Class<*>, Long>, Any /* FieldDescriptor */>()
private val DESCRIPTOR_NOT_FOUND = Any() // cannot store `null` in the cache.

fun findFieldsForObject(obj: Any?): Map<String, Any?> {
    // Null fields and primitives do not have fields
    if (obj == null || obj::class.javaPrimitiveType != null) return emptyMap()

    val clazz = obj::class.java
    // Skip primitive box types and strings
    if (clazz.isPrimitive || clazz == String::class.java) return emptyMap()

    return buildMap {
        clazz.allDeclaredInstanceFields.forEach { field ->
            val result = readFieldSafely(obj, field)
            if (result.isSuccess) put(field.name, result.getOrNull())
        }
    }
}

fun findArrayLength(arr: Any?): Int = when (arr) {
    is Array<*> -> arr.size
    is IntArray -> arr.size
    is LongArray -> arr.size
    is ByteArray -> arr.size
    is ShortArray -> arr.size
    is CharArray -> arr.size
    is FloatArray -> arr.size
    is DoubleArray -> arr.size
    is BooleanArray -> arr.size
    else -> ReflectArray.getLength(arr)
}

fun findElementsForArray(arr: Any?, nElements: Int): List<Any?> {
    // Null or non-array returns empty list
    if (arr == null || !arr::class.java.isArray) return emptyList()
    return buildList {
        for (i in 0 until nElements) {
            val result = runCatching {
                readArrayElementViaUnsafe(arr, i)
            }.recoverCatching {
                ReflectArray.get(arr, i)
            }.onFailure { exception -> 
                Logger.debug { "Failed to read elements from index $i: $exception" }
                Logger.debug(exception)
            }
            
            if (result.isSuccess) {
                add(result.getOrNull())
            } else {
                add(null)
            }
        }
    }
}

@Suppress("DEPRECATION")
fun findFieldNameByOffsetViaUnsafe(targetType: Class<*>, offset: Long, kind: FieldKind): String? =
    findFieldDescriptorByOffsetViaUnsafe(targetType, offset, kind)?.name

@Suppress("DEPRECATION")
fun findFieldDescriptorByOffsetViaUnsafe(targetType: Class<*>, offset: Long, kind: FieldKind): Field? =
    fieldDescriptorByOffsetCache.getOrPut(targetType to offset) {
        findFieldNameByOffsetViaUnsafeImpl(targetType, offset, kind) ?: DESCRIPTOR_NOT_FOUND
    }.let { if (it === DESCRIPTOR_NOT_FOUND) null else (it as Field) }

private fun findFieldNameByOffsetViaUnsafeImpl(targetType: Class<*>, offset: Long, kind: FieldKind): Field? {
    for (field in targetType.allDeclaredFields) {
        try {
            val isStatic = Modifier.isStatic(field.modifiers)
            if (Modifier.isNative(field.modifiers)) continue
            if (kind == FieldKind.STATIC && !isStatic) continue
            if (kind == FieldKind.INSTANCE && isStatic) continue

            val fieldOffset = if (isStatic) {
                UnsafeHolder.UNSAFE.staticFieldOffset(field)
            } else {
                UnsafeHolder.UNSAFE.objectFieldOffset(field)
            }
            if (fieldOffset == offset) return field
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
    return null // Field not found
}

fun cleanupUnsafeCaches() {
    fieldOffsetCache.clear()
    fieldBaseObjectCache.clear()
    fieldDescriptorByOffsetCache.clear()
}