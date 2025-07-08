/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

import org.jetbrains.kotlinx.lincheck.trace.ClassDescriptor
import org.jetbrains.kotlinx.lincheck.trace.FieldDescriptor
import org.jetbrains.kotlinx.lincheck.trace.ITraceContext
import org.jetbrains.kotlinx.lincheck.trace.MethodDescriptor
import org.jetbrains.kotlinx.lincheck.trace.MethodSignature
import org.jetbrains.kotlinx.lincheck.trace.Types
import org.jetbrains.kotlinx.lincheck.trace.VariableDescriptor

val TRACE_CONTEXT: TraceContext = TraceContext()

const val UNKNOWN_CODE_LOCATION_ID = -1

private val EMPTY_STACK_TRACE = StackTraceElement("", "", "", 0)

class TraceContext : ITraceContext {
    private val locations = ArrayList<StackTraceElement>()
    private val classes = IndexedPool<ClassDescriptor>()
    private val methods = IndexedPool<MethodDescriptor>()
    private val fields = IndexedPool<FieldDescriptor>()
    private val variables = IndexedPool<VariableDescriptor>()

    internal val classDescriptors: List<ClassDescriptor?> get() = classes.content

    override fun getOrCreateClassId(className: String): Int {
        return classes.getOrCreateId(ClassDescriptor(className))
    }

    override fun getClassDescriptor(classId: Int): ClassDescriptor = classes[classId]

    internal fun restoreClassDescriptor(id: Int, value: ClassDescriptor) {
        classes.restore(id, value)
    }

    internal val methodDescriptors: List<MethodDescriptor?> get() = methods.content

    override fun getOrCreateMethodId(className: String, methodName: String, desc: String): Int {
        return methods.getOrCreateId(
            MethodDescriptor(
                context = this,
                classId = getOrCreateClassId(className),
                methodSignature = MethodSignature(
                    name = methodName,
                    methodType = Types.convertAsmMethodType(desc)
                )
            )
        )
    }

    override fun getMethodDescriptor(methodId: Int): MethodDescriptor = methods[methodId]

    internal fun restoreMethodDescriptor(id: Int, value: MethodDescriptor) {
        methods.restore(id, value)
    }

    internal val fieldDescriptors: List<FieldDescriptor?> get() = fields.content

    override fun getOrCreateFieldId(className: String, fieldName: String, isStatic: Boolean, isFinal: Boolean): Int {
        return fields.getOrCreateId(
            FieldDescriptor(
                context = this,
                classId = getOrCreateClassId(className),
                fieldName = fieldName,
                isStatic = isStatic,
                isFinal = isFinal
            )
        )
    }

    override fun getFieldDescriptor(fieldId: Int): FieldDescriptor = fields[fieldId]

    internal fun restoreFieldDescriptor(id: Int, value: FieldDescriptor) {
        fields.restore(id, value)
    }

    internal val variableDescriptors: List<VariableDescriptor?> get() = variables.content

    override fun getOrCreateVariableId(variableName: String): Int {
        return variables.getOrCreateId(VariableDescriptor(variableName))
    }

    override fun getVariableDescriptor(variableId: Int): VariableDescriptor = variables[variableId]

    internal fun restoreVariableDescriptor(id: Int, value: VariableDescriptor) {
        variables.restore(id, value)
    }


    internal val codeLocations: List<StackTraceElement?> get() = locations

    override fun newCodeLocation(stackTraceElement: StackTraceElement): Int {
        val id = locations.size
        locations.add(stackTraceElement)
        return id
    }

    override fun stackTrace(codeLocationId: Int): StackTraceElement {
        // actors do not have a code location (for now)
        if (codeLocationId == UNKNOWN_CODE_LOCATION_ID) return EMPTY_STACK_TRACE
        var loc = locations[codeLocationId]
        if (loc == null) {
            error("Unknown code location $codeLocationId")
        }
        return loc
    }

    internal fun restoreCodeLocation(id: Int, value: StackTraceElement) {
        check (id >= locations.size || locations[id] == null || locations[id] == value) {
            "CodeLocation with id $id is already present in context and differs from $value"
        }
        while (locations.size <= id) {
            locations.add(null)
        }
        locations[id] = value
    }

    override fun clear() {
        locations.clear()
        classes.clear()
        methods.clear()
        fields.clear()
        variables.clear()
    }
}
