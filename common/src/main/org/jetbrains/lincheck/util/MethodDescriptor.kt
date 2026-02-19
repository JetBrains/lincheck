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

fun MethodDescriptor.isArraysCopyOfIntrinsic(): Boolean =
    isArraysCopyOfIntrinsic(className, methodName, methodSignature.methodType)

fun isArraysCopyOfIntrinsic(className: String, methodName: String, mt: Types.MethodType): Boolean {
    val returnType = mt.returnType
    val argumentTypes = mt.argumentTypes
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

fun MethodDescriptor.isArraysCopyOfRangeIntrinsic(): Boolean =
    isArraysCopyOfRangeIntrinsic(className, methodName, methodSignature.methodType)

fun isArraysCopyOfRangeIntrinsic(className: String, methodName: String, mt: Types.MethodType): Boolean {
    val returnType = mt.returnType
    val argumentTypes = mt.argumentTypes
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

// TODO: java 8 does not have `@HotSpotIntrinsicCandidate`/`@IntrinsicCandidate` annotations
//  add all tracked intrinsics here
fun isTrackedIntrinsic(className: String, methodName: String, mt: Types.MethodType): Boolean =
    isArraysCopyOfIntrinsic(className, methodName, mt) ||
    isArraysCopyOfRangeIntrinsic(className, methodName, mt)

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