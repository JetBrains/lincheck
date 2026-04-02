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
import org.jetbrains.lincheck.descriptors.AccessPathPool
import org.jetbrains.lincheck.descriptors.ActiveLocal
import org.jetbrains.lincheck.descriptors.ClassDescriptor
import org.jetbrains.lincheck.descriptors.CodeLocation
import org.jetbrains.lincheck.descriptors.CodeLocationPool
import org.jetbrains.lincheck.descriptors.DescriptorPool
import org.jetbrains.lincheck.descriptors.FieldDescriptor
import org.jetbrains.lincheck.descriptors.FieldKind
import org.jetbrains.lincheck.descriptors.MethodDescriptor
import org.jetbrains.lincheck.descriptors.MethodSignature
import org.jetbrains.lincheck.descriptors.StringPool
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
    val accessPathPool = AccessPathPool()
    val codeLocationsPool = CodeLocationPool()
    // Descriptor pools
    val classPool = DescriptorPool<ClassDescriptor>()
    val methodPool = DescriptorPool<MethodDescriptor>()
    val fieldPool = DescriptorPool<FieldDescriptor>()
    val variablePool = DescriptorPool<VariableDescriptor>()
    val stringPool = StringPool()

    fun setThreadName(id: Int, name: String) { threadNames[id] = name }

    fun getThreadName(id: Int): String = threadNames[id] ?: ""

    fun getThreadId(name: String): Int = threadNames.filter { (_, value) -> value == name }.keys.firstOrNull() ?: -1

    fun getThreadIds(name: String): Set<Int> = threadNames.filter { (_, value) -> value == name }.keys

    fun threadNames(): List<String> = threadNames.values.toList()

    fun newCodeLocation(
        stackTraceElement: StackTraceElement,
        accessPath: AccessPath? = null,
        argumentNames: List<AccessPath?>? = null,
        activeLocals: List<ActiveLocal>? = null
    ): Int {
        val location = CodeLocation(stackTraceElement, accessPath, argumentNames, activeLocals)
        val id = codeLocationsPool.register(location)
        return id
    }

    fun codeLocation(codeLocationId: Int): CodeLocation? = codeLocationsPool.getOrNull(codeLocationId)

    fun stackTrace(codeLocationId: Int): StackTraceElement {
        if (codeLocationId == UNKNOWN_CODE_LOCATION_ID) return EMPTY_STACK_TRACE
        val loc = codeLocationsPool[codeLocationId]
        return loc.stackTraceElement
    }

    fun accessPath(codeLocationId: Int): AccessPath? {
        if (codeLocationId == UNKNOWN_CODE_LOCATION_ID) return null
        val loc = codeLocationsPool[codeLocationId]
        return loc.accessPath
    }
    
    fun methodCallArgumentNames(codeLocationId: Int): List<AccessPath?>? {
        if (codeLocationId == UNKNOWN_CODE_LOCATION_ID) return null
        val loc = codeLocationsPool[codeLocationId]
        return loc.argumentNames
    }

    fun activeLocals(codeLocationId: Int): List<ActiveLocal>? {
        if (codeLocationId == UNKNOWN_CODE_LOCATION_ID) return null
        val loc = codeLocationsPool[codeLocationId]
        return loc.activeLocals
    }

    fun getAccessPath(id: Int): AccessPath = accessPathPool[id]

    fun restoreAccessPath(id: Int, accessPath: AccessPath) = accessPathPool.restore(id, accessPath)

    fun restoreCodeLocation(id: Int, location: CodeLocation) = codeLocationsPool.restore(id, location)

    fun clear() {
        threadNames.clear()
        accessPathPool.clear()
        codeLocationsPool.clear()
        classPool.clear()
        methodPool.clear()
        fieldPool.clear()
        variablePool.clear()
        stringPool.clear()
    }
}


/**
 * Creates a method descriptor and registers it in the context receiver.
 * As side effect this function also registers class descriptor for provided [className].
 *
 * @return created method descriptor.
 */
fun TraceContext.createAndRegisterMethodDescriptor(
    className: String,
    methodName: String,
    methodType: Types.MethodType,
    isInline: Boolean = false
): MethodDescriptor {
    // If a descriptor with the same key already exists, return the existing instance (with a proper id).
    val signature = MethodSignature(methodName, methodType)
    val clazzId = createAndRegisterClassDescriptor(className).id
    val key = MethodDescriptor.Key(clazzId, signature)
    methodPool[key]?.let { return it }

    // Otherwise, create and register a new descriptor and return it (id will be assigned during registration).
    val descriptor = MethodDescriptor(
        context = this,
        classId = clazzId,
        methodSignature = signature,
        isInline = isInline
    )
    methodPool.register(descriptor)
    return descriptor
}

/**
 * Creates a field descriptor and registers it in the context receiver.
 * As side effect this function also registers class descriptor for provided [className].
 *
 * @return created field descriptor.
 */
fun TraceContext.createAndRegisterFieldDescriptor(
    className: String,
    fieldName: String,
    type: Types.Type,
    fieldKind: FieldKind,
    isFinal: Boolean
): FieldDescriptor {
    // If a descriptor with the same key already exists, return the existing instance (with a proper id).
    val clazzId = createAndRegisterClassDescriptor(className).id
    val key = FieldDescriptor.Key(clazzId, fieldName, type, fieldKind)
    fieldPool[key]?.let { return it }

    // Otherwise, create and register a new descriptor and return it (id will be assigned during registration).
    val descriptor = FieldDescriptor(
        context = this,
        classId = clazzId,
        fieldName = fieldName,
        type = type,
        fieldKind = fieldKind,
        isFinal = isFinal
    )
    fieldPool.register(descriptor)
    return descriptor
}

/**
 * Creates a class descriptor and registers it in the context receiver.
 *
 * @return created class descriptor.
 */
fun TraceContext.createAndRegisterClassDescriptor(className: String): ClassDescriptor {
    val key = ClassDescriptor.Key(className)
    classPool[key]?.let { return it }

    val descriptor = ClassDescriptor(context = this, name = className)
    classPool.register(descriptor)
    return descriptor
}

/**
 * Creates a variable descriptor and registers it in the context receiver.
 *
 * @return created variable descriptor.
 */
fun TraceContext.createAndRegisterVariableDescriptor(
    name: String,
    type: Types.Type
): VariableDescriptor {
    val key = VariableDescriptor.Key(name, type)
    variablePool[key]?.let { return it }

    val descriptor = VariableDescriptor(context = this, name, type)
    variablePool.register(descriptor)
    return descriptor
}