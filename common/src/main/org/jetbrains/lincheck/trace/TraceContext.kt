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

import org.jetbrains.lincheck.descriptors.AccessPath
import org.jetbrains.lincheck.descriptors.ClassDescriptor
import org.jetbrains.lincheck.descriptors.CodeLocation
import org.jetbrains.lincheck.descriptors.FieldDescriptor
import org.jetbrains.lincheck.descriptors.MethodDescriptor
import org.jetbrains.lincheck.descriptors.MethodSignature
import org.jetbrains.lincheck.descriptors.VariableDescriptor
import org.jetbrains.lincheck.descriptors.Types

val TRACE_CONTEXT: TraceContext = TraceContext()

const val UNKNOWN_CODE_LOCATION_ID = -1

private val EMPTY_STACK_TRACE = StackTraceElement("", "", "", 0)

class TraceContext {
    private val locations = ArrayList<CodeLocation?>()
    private val classes = IndexedPool<ClassDescriptor>()
    private val methods = IndexedPool<MethodDescriptor>()
    private val fields = IndexedPool<FieldDescriptor>()
    private val variables = IndexedPool<VariableDescriptor>()

    val classDescriptors: List<ClassDescriptor?> get() = classes.content

    fun getOrCreateClassId(className: String): Int {
        return classes.getOrCreateId(ClassDescriptor(className))
    }

    fun getClassDescriptor(classId: Int): ClassDescriptor = classes[classId]

    fun restoreClassDescriptor(id: Int, value: ClassDescriptor) {
        classes.restore(id, value)
    }

    val methodDescriptors: List<MethodDescriptor?> get() = methods.content

    fun getOrCreateMethodId(className: String, methodName: String, desc: String): Int {
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

    fun getMethodDescriptor(className: String, methodName: String, desc: String): MethodDescriptor =
        getMethodDescriptor(getOrCreateMethodId(className, methodName, desc))

    fun getMethodDescriptor(methodId: Int): MethodDescriptor =
        methods[methodId]

    fun restoreMethodDescriptor(id: Int, value: MethodDescriptor) {
        methods.restore(id, value)
    }

    val fieldDescriptors: List<FieldDescriptor?> get() = fields.content

    fun getOrCreateFieldId(className: String, fieldName: String, isStatic: Boolean, isFinal: Boolean): Int {
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

    fun getFieldDescriptor(className: String, fieldName: String, isStatic: Boolean, isFinal: Boolean): FieldDescriptor =
        getFieldDescriptor(getOrCreateFieldId(className, fieldName, isStatic, isFinal))

    fun getFieldDescriptor(fieldId: Int): FieldDescriptor =
        fields[fieldId]

    fun restoreFieldDescriptor(id: Int, value: FieldDescriptor) {
        fields.restore(id, value)
    }

    val variableDescriptors: List<VariableDescriptor?> get() = variables.content

    fun getOrCreateVariableId(variableName: String): Int {
        return variables.getOrCreateId(VariableDescriptor(variableName))
    }

    fun getVariableDescriptor(variableName: String): VariableDescriptor =
        getVariableDescriptor(getOrCreateVariableId(variableName))

    fun getVariableDescriptor(variableId: Int): VariableDescriptor =
        variables[variableId]

    fun restoreVariableDescriptor(id: Int, value: VariableDescriptor) {
        variables.restore(id, value)
    }

    val codeLocations: List<CodeLocation?> get() = locations

    fun newCodeLocation(stackTraceElement: StackTraceElement, accessPath: AccessPath? = null): Int {
        val id = locations.size
        val location = CodeLocation(stackTraceElement, accessPath)
        locations.add(location)
        return id
    }

    fun stackTrace(codeLocationId: Int): StackTraceElement {
        if (codeLocationId == UNKNOWN_CODE_LOCATION_ID) return EMPTY_STACK_TRACE
        val loc = locations[codeLocationId]
        if (loc == null) {
            error("Invalid code location id $codeLocationId")
        }
        return loc.stackTraceElement
    }

    fun accessPath(codeLocationId: Int): AccessPath? {
        if (codeLocationId == UNKNOWN_CODE_LOCATION_ID) return null
        val loc = locations[codeLocationId]
        if (loc == null) {
            error("Invalid code location id $codeLocationId")
        }
        return loc.accessPath
    }

    fun restoreCodeLocation(id: Int, location: CodeLocation) {
        check (id >= locations.size || locations[id] == null || locations[id] == location) {
            "CodeLocation with id $id is already present in context and differs from $location"
        }
        while (locations.size <= id) {
            locations.add(null)
        }
        locations[id] = location
    }

    fun clear() {
        locations.clear()
        classes.clear()
        methods.clear()
        fields.clear()
        variables.clear()
    }
}
