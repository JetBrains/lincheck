/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.descriptors

import org.jetbrains.lincheck.trace.TraceContext

data class ClassDescriptor(
    val name: String,
)

data class MethodSignature(val name: String, val methodType: Types.MethodType) {
    override fun toString(): String {
        return "$name$methodType"
    }
}

data class MethodDescriptor(
    private val context: TraceContext,
    val classId: Int,
    val methodSignature: MethodSignature
) {
    var isIntrinsic: Boolean = false

    val classDescriptor: ClassDescriptor = context.getClassDescriptor(classId)
    val className: String get() = classDescriptor.name
    val methodName: String get() = methodSignature.name
    val returnType: Types.Type get() = methodSignature.methodType.returnType
    val argumentTypes: List<Types.Type> get() = methodSignature.methodType.argumentTypes

    override fun toString(): String = "$className.$methodSignature"
}

data class FieldDescriptor(
    private val context: TraceContext,
    val classId: Int,
    val fieldName: String,
    val isStatic: Boolean,
    val isFinal: Boolean,
) {
    val classDescriptor: ClassDescriptor = context.getClassDescriptor(classId)
    val className: String get() = classDescriptor.name
}

data class VariableDescriptor(
    val name: String,
)
