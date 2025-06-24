/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.tracedata

@ConsistentCopyVisibility
data class ClassDescriptor internal constructor(
    val name: String,
)

data class MethodSignature(val name: String, val methodType: Types.MethodType) {
    override fun toString(): String {
        return "$name$methodType"
    }
}

@ConsistentCopyVisibility
data class MethodDescriptor internal constructor(
    private val context: TraceContext,
    val classId: Int,
    val methodSignature: MethodSignature
) {
    var isIntrinsic: Boolean = false

    val className: String get() = context.getClassDescriptor(classId).name
    val methodName: String get() = methodSignature.name
    val returnType: Types.Type get() = methodSignature.methodType.returnType
    val argumentTypes: List<Types.Type> get() = methodSignature.methodType.argumentTypes

    override fun toString(): String = "$className.$methodSignature"
}

@ConsistentCopyVisibility
data class FieldDescriptor internal constructor(
    private val context: TraceContext,
    val classId: Int,
    val fieldName: String,
    val isStatic: Boolean,
    val isFinal: Boolean,
) {
    val className: String get() = context.getClassDescriptor(classId).name
}

@ConsistentCopyVisibility
data class VariableDescriptor internal constructor(
    val name: String,
)
