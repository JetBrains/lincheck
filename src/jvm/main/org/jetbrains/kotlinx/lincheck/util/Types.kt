/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util

import org.jetbrains.kotlinx.lincheck.transformation.OptimizedString
import org.jetbrains.kotlinx.lincheck.transformation.optimized
import org.objectweb.asm.commons.Method
import sun.nio.ch.lincheck.MethodSignature
import sun.nio.ch.lincheck.Types.*
import sun.nio.ch.lincheck.Types.convertAsmMethodType

internal fun Method.toMethodSignature() = MethodSignature(this.name, convertAsmMethodType(this.descriptor))
internal fun java.lang.reflect.Method.toMethodSignature() = Method.getMethod(this).toMethodSignature()

internal data class MethodDescriptor(
    val optimizedClassName: OptimizedString,
    val methodSignature: MethodSignature
) {
    constructor(className: String, methodSignature: MethodSignature) : this(className.optimized(), methodSignature)
    val className: String get() = optimizedClassName.toString()

    var isIntrinsic: Boolean = false
    
    constructor(className: String, methodName: String, desc: String) :
        this(className, MethodSignature(methodName, convertAsmMethodType(desc)))

    val methodName: String get() = methodSignature.name
    val returnType: Type get() = methodSignature.methodType.returnType
    val argumentTypes: List<Type> get() = methodSignature.methodType.argumentTypes

    override fun toString(): String = "$className.$methodSignature"
}

internal fun MethodDescriptor.isArraysCopyOfIntrinsic(): Boolean {
    return (
        className == "java.util.Arrays" &&
        methodName == "copyOf" &&
        (
            returnType == ARRAY_OF_OBJECTS_TYPE && argumentTypes == listOf(ARRAY_OF_OBJECTS_TYPE, INT_TYPE) ||
            returnType == ARRAY_OF_OBJECTS_TYPE && argumentTypes == listOf(ARRAY_OF_OBJECTS_TYPE, INT_TYPE, CLASS_TYPE) ||
            ARRAY_OF_PRIMITIVE_TYPES.any { returnType == it && argumentTypes == listOf(it, INT_TYPE) }
        )
    )
}

internal fun MethodDescriptor.isArraysCopyOfRangeIntrinsic(): Boolean {
    return (
        className == "java.util.Arrays" &&
        methodName.contains("copyOfRange") &&
        (
            returnType == ARRAY_OF_OBJECTS_TYPE && argumentTypes == listOf(ARRAY_OF_OBJECTS_TYPE, INT_TYPE, INT_TYPE) ||
            returnType == ARRAY_OF_OBJECTS_TYPE && argumentTypes == listOf(ARRAY_OF_OBJECTS_TYPE, INT_TYPE, INT_TYPE, CLASS_TYPE) ||
            ARRAY_OF_PRIMITIVE_TYPES.any { returnType == it && argumentTypes == listOf(it, INT_TYPE, INT_TYPE) }
        )
    )
}

// TODO: java 8 does not have `@HotSpotIntrinsicCandidate`/`@IntrinsicCandidate` annotations
//  add all tracked intrinsics here
internal fun MethodDescriptor.isTrackedIntrinsic(): Boolean =
    isArraysCopyOfIntrinsic() ||
    isArraysCopyOfRangeIntrinsic()

private val ARRAY_OF_OBJECTS_TYPE = ArrayType(ObjectType("java.lang.Object"))
private val ARRAY_OF_INT_TYPE = ArrayType(INT_TYPE)
private val ARRAY_OF_LONG_TYPE = ArrayType(LONG_TYPE)
private val ARRAY_OF_DOUBLE_TYPE = ArrayType(DOUBLE_TYPE)
private val ARRAY_OF_FLOAT_TYPE = ArrayType(FLOAT_TYPE)
private val ARRAY_OF_BOOLEAN_TYPE = ArrayType(BOOLEAN_TYPE)
private val ARRAY_OF_BYTE_TYPE = ArrayType(BYTE_TYPE)
private val ARRAY_OF_SHORT_TYPE = ArrayType(SHORT_TYPE)
private val ARRAY_OF_CHAR_TYPE = ArrayType(CHAR_TYPE)
private val ARRAY_OF_PRIMITIVE_TYPES = listOf(
    ARRAY_OF_INT_TYPE, ARRAY_OF_LONG_TYPE, ARRAY_OF_DOUBLE_TYPE,
    ARRAY_OF_FLOAT_TYPE, ARRAY_OF_BOOLEAN_TYPE, ARRAY_OF_BYTE_TYPE,
    ARRAY_OF_SHORT_TYPE, ARRAY_OF_CHAR_TYPE
)
private val CLASS_TYPE = ObjectType("java.lang.Class")