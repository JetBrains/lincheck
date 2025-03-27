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

import org.objectweb.asm.commons.Method
import sun.nio.ch.lincheck.MethodSignature
import sun.nio.ch.lincheck.Types.*
import sun.nio.ch.lincheck.Types.convertAsmMethodType

internal fun Method.toMethodSignature() = MethodSignature(this.name, convertAsmMethodType(this.descriptor))
internal fun java.lang.reflect.Method.toMethodSignature() = Method.getMethod(this).toMethodSignature()

internal data class MethodDescriptor(
    val className: String,
    val methodSignature: MethodSignature
) {
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
        returnType == ARRAY_OF_OBJECTS_TYPE &&
        argumentTypes == listOf(ARRAY_OF_OBJECTS_TYPE, INT_TYPE, CLASS_TYPE)
    )
}

internal fun MethodDescriptor.isArraysCopyOfRangeIntrinsic(): Boolean {
    return (
        className == "java.util.Arrays" &&
        methodName == "copyOfRange" &&
        returnType == ARRAY_OF_OBJECTS_TYPE &&
        argumentTypes == listOf(ARRAY_OF_OBJECTS_TYPE, INT_TYPE, INT_TYPE, CLASS_TYPE)
    )
}

// TODO: java 8 does not have `@HotSpotIntrinsicCandidate`/`@IntrinsicCandidate` annotations
//  add all tracked intrinsics here
internal fun MethodDescriptor.isTrackedIntrinsic(): Boolean =
    isArraysCopyOfIntrinsic() ||
    isArraysCopyOfIntrinsic()

private val ARRAY_OF_OBJECTS_TYPE = ArrayType(ObjectType("java.lang.Object"))
private val CLASS_TYPE = ObjectType("java.lang.Class")