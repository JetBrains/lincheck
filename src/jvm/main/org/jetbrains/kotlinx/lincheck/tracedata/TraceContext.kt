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

val TRACE_CONTEXT = TraceContext()

class TraceContext {
    private val classes = IndexedPool<ClassDescriptor>()
    private val methods = IndexedPool<MethodDescriptor>()
    private val fields = IndexedPool<FieldDescriptor>()
    private val variables = IndexedPool<VariableDescriptor>()

    internal val classDescriptors: List<ClassDescriptor> get() = classes.content

    fun getOrCreateClassId(className: String): Int {
        return classes.getOrCreateId(ClassDescriptor(className))
    }

    fun getClassDescriptor(classId: Int): ClassDescriptor = classes[classId]

    internal fun restoreClassDescriptor(value: ClassDescriptor) {
        classes.getOrCreateId(value)
    }

    internal val methodDescriptors: List<MethodDescriptor> get() = methods.content

    fun getOrCreateMethodId(className: String, methodName: String, desc: String): Int {
        return methods.getOrCreateId(MethodDescriptor(
            context = this,
            classId = getOrCreateClassId(className),
            methodSignature = MethodSignature(
                name = methodName,
                methodType = Types.convertAsmMethodType(desc)
            )
        ))
    }

    fun getMethodDescriptor(methodId: Int): MethodDescriptor = methods[methodId]

    internal fun restoreMethodDescriptor(value: MethodDescriptor) {
        methods.getOrCreateId(value)
    }

    internal val fieldDescriptors: List<FieldDescriptor> get() = fields.content

    fun getOrCreateFieldId(className: String, fieldName: String, isStatic: Boolean, isFinal: Boolean): Int {
        return fields.getOrCreateId(FieldDescriptor(
            context = this,
            classId = getOrCreateClassId(className),
            fieldName = fieldName,
            isStatic = isStatic,
            isFinal = isFinal
        ))
    }

    fun getFieldDescriptor(fieldId: Int): FieldDescriptor = fields[fieldId]

    internal fun restoreFieldDescriptor(value: FieldDescriptor) {
        fields.getOrCreateId(value)
    }

    internal val variableDescriptors: List<VariableDescriptor> get() = variables.content

    fun getOrCreateVariableId(variableName: String): Int {
        return variables.getOrCreateId(VariableDescriptor(variableName))
    }

    fun getVariableDescriptor(variableId: Int): VariableDescriptor = variables[variableId]

    internal fun restoreVariableDescriptor(value: VariableDescriptor) {
        variables.getOrCreateId(value)
    }

    fun clear() {
        classes.clear()
        methods.clear()
        fields.clear()
        variables.clear()
    }
}
