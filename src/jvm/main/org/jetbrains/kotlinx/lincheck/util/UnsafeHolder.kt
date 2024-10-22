/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util

import sun.misc.Unsafe
import java.lang.reflect.Field
import java.lang.reflect.Modifier

internal object UnsafeHolder {
    val UNSAFE: Unsafe = try {
        val unsafeField = Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        unsafeField.get(null) as Unsafe
    } catch (ex: Exception) {
        throw RuntimeException("Can't get the Unsafe instance, please report it to the Lincheck team", ex)
    }
}

@Suppress("DEPRECATION")
internal inline fun <T> readFieldViaUnsafe(obj: Any?, field: Field, getter: Unsafe.(Any?, Long) -> T): T {
    if (Modifier.isStatic(field.modifiers)) {
        val base = UnsafeHolder.UNSAFE.staticFieldBase(field)
        val offset = UnsafeHolder.UNSAFE.staticFieldOffset(field)
        return UnsafeHolder.UNSAFE.getter(base, offset)
    } else {
        val offset = UnsafeHolder.UNSAFE.objectFieldOffset(field)
        return UnsafeHolder.UNSAFE.getter(obj, offset)
    }
}

internal fun readField(obj: Any?, field: Field): Any? {
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