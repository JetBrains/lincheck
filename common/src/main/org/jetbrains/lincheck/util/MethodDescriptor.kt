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

import org.jetbrains.lincheck.descriptors.MethodDescriptor
import org.jetbrains.lincheck.descriptors.Types

fun MethodDescriptor.isArraysCopyOfIntrinsic(): Boolean {
    return (
        className == "java.util.Arrays" &&
        methodName == "copyOf" &&
        (
            returnType == ARRAY_OF_OBJECTS_TYPE && argumentTypes == listOf(ARRAY_OF_OBJECTS_TYPE, Types.INT_TYPE) ||
            returnType == ARRAY_OF_OBJECTS_TYPE && argumentTypes == listOf(ARRAY_OF_OBJECTS_TYPE, Types.INT_TYPE, CLASS_TYPE) ||
            ARRAY_OF_PRIMITIVE_TYPES.any { returnType == it && argumentTypes == listOf(it, Types.INT_TYPE) }
        )
    )
}

fun MethodDescriptor.isArraysCopyOfRangeIntrinsic(): Boolean {
    return (
        className == "java.util.Arrays" &&
        methodName.contains("copyOfRange") &&
        (
            returnType == ARRAY_OF_OBJECTS_TYPE && argumentTypes == listOf(ARRAY_OF_OBJECTS_TYPE,
                Types.INT_TYPE,
                Types.INT_TYPE
            ) ||
            returnType == ARRAY_OF_OBJECTS_TYPE && argumentTypes == listOf(ARRAY_OF_OBJECTS_TYPE,
                Types.INT_TYPE,
                Types.INT_TYPE, CLASS_TYPE) ||
            ARRAY_OF_PRIMITIVE_TYPES.any { returnType == it && argumentTypes == listOf(it,
                Types.INT_TYPE,
                Types.INT_TYPE
            ) }
        )
    )
}

fun MethodDescriptor.isArrayNewInstance(): Boolean {
    return (
        className == "java.lang.reflect.Array" &&
        methodName == "newInstance"
    )
}

fun MethodDescriptor.isSystemArrayCopy() : Boolean {
    return className == "java.lang.System" && methodName == "arraycopy"
}

/** Checks for native object cloning or the synthetic clone method owned by an array type. */
fun MethodDescriptor.isCloneMethod(): Boolean =
    methodName == "clone" && (className == "java.lang.Object" || className.startsWith("["))

/**
 * Whether this is a call to one of `java.lang.reflect.Field`'s setter methods
 * (`set`, `setInt`, `setLong`, etc.), which write to an existing object's field
 * without going through the instrumented `putfield`/`putstatic` bytecode.
 */
fun MethodDescriptor.isFieldReflectiveSet(): Boolean {
    return className == "java.lang.reflect.Field" && methodName in FIELD_SETTER_METHOD_NAMES
}

/** Whether this call reads a field through the reflection API. */
fun MethodDescriptor.isFieldReflectiveGet(): Boolean {
    return className == "java.lang.reflect.Field" && methodName in FIELD_GETTER_METHOD_NAMES
}

private val FIELD_GETTER_METHOD_NAMES = setOf(
    "get", "getBoolean", "getByte", "getChar", "getShort", "getInt", "getLong", "getFloat", "getDouble",
)

private val FIELD_SETTER_METHOD_NAMES = setOf(
    "set", "setBoolean", "setByte", "setChar", "setShort", "setInt", "setLong", "setFloat", "setDouble",
)

// TODO: java 8 does not have `@HotSpotIntrinsicCandidate`/`@IntrinsicCandidate` annotations
//  add all tracked intrinsics here
fun MethodDescriptor.isTrackedIntrinsic(): Boolean =
    isArraysCopyOfIntrinsic() ||
    isArraysCopyOfRangeIntrinsic()

private val ARRAY_OF_OBJECTS_TYPE = Types.ArrayType(Types.ObjectType("java.lang.Object"))
private val ARRAY_OF_INT_TYPE = Types.ArrayType(Types.INT_TYPE)
private val ARRAY_OF_LONG_TYPE = Types.ArrayType(Types.LONG_TYPE)
private val ARRAY_OF_DOUBLE_TYPE = Types.ArrayType(Types.DOUBLE_TYPE)
private val ARRAY_OF_FLOAT_TYPE = Types.ArrayType(Types.FLOAT_TYPE)
private val ARRAY_OF_BOOLEAN_TYPE = Types.ArrayType(Types.BOOLEAN_TYPE)
private val ARRAY_OF_BYTE_TYPE = Types.ArrayType(Types.BYTE_TYPE)
private val ARRAY_OF_SHORT_TYPE = Types.ArrayType(Types.SHORT_TYPE)
private val ARRAY_OF_CHAR_TYPE = Types.ArrayType(Types.CHAR_TYPE)
private val ARRAY_OF_PRIMITIVE_TYPES = listOf(
    ARRAY_OF_INT_TYPE, ARRAY_OF_LONG_TYPE, ARRAY_OF_DOUBLE_TYPE,
    ARRAY_OF_FLOAT_TYPE, ARRAY_OF_BOOLEAN_TYPE, ARRAY_OF_BYTE_TYPE,
    ARRAY_OF_SHORT_TYPE, ARRAY_OF_CHAR_TYPE
)
private val CLASS_TYPE = Types.ObjectType("java.lang.Class")