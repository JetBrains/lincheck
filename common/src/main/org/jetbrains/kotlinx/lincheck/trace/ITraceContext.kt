/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.trace

interface ITraceContext {
    fun getOrCreateClassId(className: String): Int
    fun getClassDescriptor(classId: Int): ClassDescriptor
    fun getOrCreateMethodId(className: String, methodName: String, desc: String): Int
    fun getMethodDescriptor(methodId: Int): MethodDescriptor
    fun getOrCreateFieldId(className: String, fieldName: String, isStatic: Boolean, isFinal: Boolean): Int
    fun getFieldDescriptor(fieldId: Int): FieldDescriptor
    fun getOrCreateVariableId(variableName: String): Int
    fun getVariableDescriptor(variableId: Int): VariableDescriptor
    fun newCodeLocation(stackTraceElement: StackTraceElement): Int
    fun stackTrace(codeLocationId: Int): StackTraceElement
    fun clear(): Unit
}