/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import org.jetbrains.kotlinx.lincheck.transformation.toSimpleClassName
import org.jetbrains.kotlinx.lincheck.util.AtomicApiKind
import org.jetbrains.kotlinx.lincheck.util.AtomicMethodDescriptor
import org.jetbrains.kotlinx.lincheck.util.getAtomicArrayAccessInfo
import org.jetbrains.kotlinx.lincheck.util.getAtomicFieldUpdaterAccessInfo
import org.jetbrains.kotlinx.lincheck.util.getUnsafeAccessInfo
import org.jetbrains.kotlinx.lincheck.util.getVarHandleAccessInfo
import org.jetbrains.lincheck.analysis.ShadowStackFrame
import org.jetbrains.lincheck.analysis.findCurrentReceiverFieldReferringTo
import org.jetbrains.lincheck.analysis.findLocalVariableFieldReferringTo
import org.jetbrains.lincheck.analysis.findLocalVariableReferringTo
import org.jetbrains.lincheck.analysis.isThisName
import org.jetbrains.lincheck.descriptors.AccessLocation
import org.jetbrains.lincheck.descriptors.ArrayElementByIndexAccessLocation
import org.jetbrains.lincheck.descriptors.FieldAccessLocation

/**
 * Finds the owner name of a given object. The owner name can originate from various sources
 * such as local variable names, instance field names, constants referencing the object,
 * or as a last resort, the object's string representation.
 *
 * @param obj The object whose owner name needs to be determined.

 * @return A string representing the owner name of the object, or null if no owner is applicable.
 */

/**
 * Finds the owner name of a given object. The owner name can originate from various sources
 * such as local variable names, instance field names, constants referencing the object,
 * or as a last resort, the object's string representation.
 *
 * @param obj The object whose owner name needs to be determined, can be null.
 * @param className The name of the class, required when obj is null. Used in case of static field/method accesses.
 * @param shadowStackFrame the current shadow stack frame, representing the program's stack state during execution.
 * @param objectTracker the object tracker, used to provide representations of tracked objects.
 * @param constants a mapping from objects to their associated constant names.
 * @return the determined owner name as a string, or null if no explicit owner name is required.
 */
internal fun findOwnerName(
    obj: Any?,
    className: String?,
    shadowStackFrame: ShadowStackFrame,
    objectTracker: ObjectTracker,
    constants: Map<Any, String>,
): String? {
    if (obj == null) {
        require(className != null) {
            "Class name must be provided for null object"
        }
        // If the class name matches the current instance's class in the shadow stack,
        // return null since the field belongs to the current class context and doesn't
        // need an explicit owner prefix
        if (className == shadowStackFrame.instance?.javaClass?.name) {
            return null
        }
        return className.toSimpleClassName()
    }
    // do not prettify thread names
    if (obj is Thread) {
        return objectTracker.getObjectRepresentation(obj)
    }
    // if the current owner is `this` - no owner needed
    if (obj === shadowStackFrame.instance) return null
    // lookup for the object in local variables and use the local variable name if found
    shadowStackFrame.findLocalVariableReferringTo(obj)?.let { access ->
        return if (isThisName(access.variableName)) null else access.variableName
    }
    // lookup for a field name in the current stack frame `this`
    shadowStackFrame.findCurrentReceiverFieldReferringTo(obj)?.let { access ->
        return if (isThisName(access.fieldName)) null else access.fieldName
    }
    // lookup for the constant referencing the object
    constants[obj]?.let { return it }
    // otherwise return object's string representation
    return objectTracker.getObjectRepresentation(obj)
}

internal fun findAtomicOwnerName(
    atomic: Any,
    arguments: Array<Any?>,
    atomicMethodDescriptor: AtomicMethodDescriptor,
    shadowStackFrame: ShadowStackFrame,
    objectTracker: ObjectTracker,
    constants: Map<Any, String>,
): Pair<String, List<Any?>> {
    val apiKind = atomicMethodDescriptor.apiKind
    var ownerName: String? = null
    var params: List<Any?>? = null

    var arrayAccess = ""
    if (apiKind == AtomicApiKind.ATOMIC_ARRAY) {
        val info = atomicMethodDescriptor.getAtomicArrayAccessInfo(atomic, arguments)
        arrayAccess = "[${(info.location as ArrayElementByIndexAccessLocation).index}]"
        params = info.arguments
    } else if (apiKind == AtomicApiKind.ATOMIC_OBJECT) {
        params = arguments.asList()
    }

    fun getOwnerName(owner: Any?, className: String?, location: AccessLocation): String {
        val owner = findOwnerName(owner, className, shadowStackFrame, objectTracker, constants)
        return when (location) {
            is ArrayElementByIndexAccessLocation -> {
                (owner ?: "this") + "[${location.index}]"
            }
            is FieldAccessLocation -> {
                (if (owner != null) "$owner." else "") + location.fieldName
            }
            else -> {
                error("Unexpected location type: $location")
            }
        }
    }

    if (apiKind == AtomicApiKind.ATOMIC_OBJECT ||
        apiKind == AtomicApiKind.ATOMIC_ARRAY
    ) {
        ownerName = // if (shadowStack.isCurrentStackFrameReceiver(atomic)) null else
            // first try to find the receiver field name
            shadowStackFrame.findCurrentReceiverFieldReferringTo(atomic)
            ?.let { fieldAccess ->
                getOwnerName(
                    owner = shadowStackFrame.instance,
                    className = fieldAccess.className,
                    location = fieldAccess,
                )
            }
            // then try to search in local variables
            ?: shadowStackFrame.findLocalVariableReferringTo(atomic)?.toString()
            // then try to search in local variables' fields
            ?: shadowStackFrame.findLocalVariableFieldReferringTo(atomic)?.toString()
    }

    if (apiKind == AtomicApiKind.ATOMIC_FIELD_UPDATER ||
        apiKind == AtomicApiKind.VAR_HANDLE ||
        apiKind == AtomicApiKind.UNSAFE
    ) {
        val info = when (apiKind) {
            AtomicApiKind.ATOMIC_FIELD_UPDATER ->
                atomicMethodDescriptor.getAtomicFieldUpdaterAccessInfo(atomic, arguments)
            AtomicApiKind.VAR_HANDLE ->
                atomicMethodDescriptor.getVarHandleAccessInfo(atomic, arguments)
            AtomicApiKind.UNSAFE ->
                atomicMethodDescriptor.getUnsafeAccessInfo(atomic, arguments)
            else ->
                error("")
        }
        ownerName = getOwnerName(
            owner = info.obj,
            className = (info.location as? FieldAccessLocation)?.className,
            location = info.location!!,
        )
        params = info.arguments
    }

    return if (ownerName != null) {
        // return the owner name if it was found
        ownerName + arrayAccess to params!!
    } else {
        // otherwise return atomic object representation
        objectTracker.getObjectRepresentation(atomic) + arrayAccess to params!!
    }
}