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
import org.jetbrains.lincheck.descriptors.ActiveLocal
import org.jetbrains.lincheck.descriptors.ClassDescriptor
import org.jetbrains.lincheck.descriptors.CodeLocation
import org.jetbrains.lincheck.descriptors.FieldDescriptor
import org.jetbrains.lincheck.descriptors.MethodDescriptor
import org.jetbrains.lincheck.descriptors.MethodSignature
import org.jetbrains.lincheck.descriptors.VariableDescriptor
import org.jetbrains.lincheck.descriptors.Types
import java.util.concurrent.ConcurrentHashMap

const val UNKNOWN_CODE_LOCATION_ID = -1
// This method type corresponds to the following method descriptor '(V)V'
// which is invalid jvm method descriptor, but it is only used to mark some method type as unknown
// or impossible to properly track.
val UNKNOWN_METHOD_TYPE = Types.MethodType(Types.VOID_TYPE, Types.VOID_TYPE)

private val EMPTY_STACK_TRACE = StackTraceElement("", "", "", 0)

class TraceContext {
    private val threadNames = ConcurrentHashMap<Int, String>()
    private val accessPaths = ArrayList<AccessPath?>()
    private val locations = ArrayList<CodeLocation?>()
    // Descriptor pools
    val classPool = DescriptorPool<ClassDescriptor>()
    val methodPool = DescriptorPool<MethodDescriptor>()
    val fieldPool = DescriptorPool<FieldDescriptor>()
    val variablePool = DescriptorPool<VariableDescriptor>()

    fun setThreadName(id: Int, name: String) { threadNames[id] = name }

    fun getThreadName(id: Int): String = threadNames[id] ?: ""

    fun getThreadId(name: String): Int = threadNames.filter { (_, value) -> value == name }.keys.firstOrNull() ?: -1

    fun threadNames(): List<String> = threadNames.values.toList()

    val classDescriptors: List<ClassDescriptor?> get() = classPool.descriptors

    fun getOrCreateClassId(className: String): Int {
        return classPool.register(ClassDescriptor(className))
    }

    fun getClassDescriptor(classId: Int): ClassDescriptor = classPool[classId]

    fun restoreClassDescriptor(id: Int, value: ClassDescriptor) {
        classPool.restore(id, value)
    }

    val methodDescriptors: List<MethodDescriptor?> get() = methodPool.descriptors

    fun getOrCreateMethodId(className: String, methodName: String, methodType: Types.MethodType): Int {
        return methodPool.register(
            MethodDescriptor(
                context = this,
                classId = getOrCreateClassId(className),
                methodSignature = MethodSignature(
                    name = methodName,
                    methodType = methodType
                )
            )
        )
    }

    fun getMethodDescriptor(className: String, methodName: String, methodType: Types.MethodType): MethodDescriptor =
        getMethodDescriptor(getOrCreateMethodId(className, methodName, methodType))

    fun getMethodDescriptor(methodId: Int): MethodDescriptor = methodPool[methodId]

    fun restoreMethodDescriptor(id: Int, value: MethodDescriptor) {
        methodPool.restore(id, value)
    }

    val fieldDescriptors: List<FieldDescriptor?> get() = fieldPool.descriptors

    fun hasFieldDescriptor(field: FieldDescriptor): Boolean {
        return fieldPool.contains(field.key)
    }

    fun getOrCreateFieldId(className: String, fieldName: String, type: Types.Type, isStatic: Boolean, isFinal: Boolean): Int {
        return getOrCreateFieldId(
            FieldDescriptor(
                context = this,
                classId = getOrCreateClassId(className),
                fieldName = fieldName,
                type = type,
                isStatic = isStatic,
                isFinal = isFinal
            )
        )
    }

    fun getOrCreateFieldId(field: FieldDescriptor): Int {
        return fieldPool.register(field)
    }

    fun getFieldDescriptor(className: String, fieldName: String, type: Types.Type, isStatic: Boolean, isFinal: Boolean): FieldDescriptor =
        getFieldDescriptor(getOrCreateFieldId(className, fieldName, type, isStatic, isFinal))

    fun getFieldDescriptor(fieldId: Int): FieldDescriptor = fieldPool[fieldId]

    fun restoreFieldDescriptor(id: Int, value: FieldDescriptor) {
        fieldPool.restore(id, value)
    }

    val variableDescriptors: List<VariableDescriptor?> get() = variablePool.descriptors

    fun hasVariableDescriptor(variable: VariableDescriptor): Boolean {
        return variablePool.contains(variable.key)
    }

    fun getOrCreateVariableId(variableName: String, type: Types.Type): Int {
        return getOrCreateVariableId(VariableDescriptor(variableName, type))
    }

    fun getOrCreateVariableId(variableDescriptor: VariableDescriptor): Int {
        return variablePool.register(variableDescriptor)
    }

    fun getVariableDescriptor(variableName: String, type: Types.Type): VariableDescriptor =
        getVariableDescriptor(getOrCreateVariableId(variableName, type))

    fun getVariableDescriptor(variableId: Int): VariableDescriptor = variablePool[variableId]

    fun restoreVariableDescriptor(id: Int, value: VariableDescriptor) {
        variablePool.restore(id, value)
    }

    val codeLocations: List<CodeLocation?> get() = locations

    fun newCodeLocation(
        stackTraceElement: StackTraceElement,
        accessPath: AccessPath? = null,
        argumentNames: List<AccessPath?>? = null,
        activeLocals: List<ActiveLocal>? = null
    ): Int {
        val id = locations.size
        val location = CodeLocation(stackTraceElement, accessPath, argumentNames, activeLocals)
        locations.add(location)
        return id
    }

    fun codeLocation(codeLocationId: Int): CodeLocation? =
        locations[codeLocationId]

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
    
    fun methodCallArgumentNames(codeLocationId: Int): List<AccessPath?>? {
        if (codeLocationId == UNKNOWN_CODE_LOCATION_ID) return null
        val loc = locations[codeLocationId]
        if (loc == null) {
            error("Invalid code location id $codeLocationId")
        }
        return loc.argumentNames
    }

    fun activeLocals(codeLocationId: Int): List<ActiveLocal>? {
        if (codeLocationId == UNKNOWN_CODE_LOCATION_ID) return null
        val loc = locations[codeLocationId] ?: error("Invalid code location id $codeLocationId")
        return loc.activeLocals
    }

    fun getAccessPath(id: Int): AccessPath = accessPaths[id] ?: error("Referenced access path $id not loaded")

    fun restoreAccessPath(id: Int, accessPath: AccessPath) {
        check(id >= accessPaths.size || accessPaths[id] == null || accessPaths[id] == accessPath) {
            "AccessPath with id $id is already present in context and differs from $accessPath"
        }
        while (accessPaths.size <= id) {
            accessPaths.add(null)
        }
        accessPaths[id] = accessPath
    }

    fun restoreCodeLocation(id: Int, location: CodeLocation) {
        check(id >= locations.size || locations[id] == null || locations[id] == location) {
            "CodeLocation with id $id is already present in context and differs from $location"
        }
        while (locations.size <= id) {
            locations.add(null)
        }
        locations[id] = location
    }

    fun clear() {
        accessPaths.clear()
        locations.clear()
        classPool.clear()
        methodPool.clear()
        fieldPool.clear()
        variablePool.clear()
    }
}
