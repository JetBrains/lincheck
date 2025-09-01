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

import org.jetbrains.lincheck.descriptors.*
import org.jetbrains.lincheck.analysis.*
import org.jetbrains.lincheck.trace.isThisName
import org.jetbrains.lincheck.jvm.agent.toSimpleClassName
import org.jetbrains.lincheck.util.*

/**
 * Determines the name of the owner, if needed, based on the class name and the shadow stack frame.
 *
 * @param className the fully qualified class name of the field or method.
 * @param shadowStackFrame the current shadow stack frame, representing the program's stack state during execution.
 * @return the determined owner name as a string, or null if no explicit owner name is required.
 */
internal fun findOwnerName(
    className: String,
    shadowStackFrame: ShadowStackFrame,
): String? {
    // If the class name matches the current instance's class in the shadow stack,
    // return null since the field belongs to the current class context and doesn't
    // need an explicit owner prefix
    if (className == shadowStackFrame.instanceClassName) {
        return null
    }
    return className.toSimpleClassName()
}

/**
 * Finds the owner name of a given object.
 * The owner name can originate from various sources such as local variable names,
 * constants referencing the object, instance field names,
 * or as a last resort, the object's string representation.
 *
 * @param obj The object whose owner name needs to be determined. Can be null.
 * @param className The name of the class, required when obj is null. Used in case of static field/method accesses.
 * @param codeLocationId the ID of the code location, used to look up the code location's access path.
 * @param shadowStackFrame the current shadow stack frame, representing the program's stack state during execution.
 * @param objectTracker the object tracker, used to provide representations of tracked objects.
 * @return the determined owner name as a string, or null if no explicit owner name is required.
 */
fun findOwnerName(
    obj: Any?,
    className: String?,
    codeLocationId: Int,
    shadowStackFrame: ShadowStackFrame,
    objectTracker: ObjectTracker,
): String? {
    if (obj == null) {
        return findOwnerName(className!!, shadowStackFrame)
    }
    // do not prettify thread names
    if (obj is Thread) {
        return objectTracker.getObjectRepresentation(obj)
    }
    // if the current owner is `this` - no owner needed
    if (shadowStackFrame.isCurrentStackFrameReceiver(obj)) return null
    // lookup for a statically inferred owner name
    val ownerName = CodeLocations.accessPath(codeLocationId)?.toString()
    if (ownerName != null) {
        return if (isThisName(ownerName)) null else ownerName
    }
    // lookup for a field name in the current stack frame `this`
    shadowStackFrame.findCurrentReceiverFieldReferringTo(obj)?.let { access ->
        return if (isThisName(access.fieldName)) null else access.fieldName
    }
    // otherwise return object's string representation
    return objectTracker.getObjectRepresentation(obj)
}

/**
 * Finds the owner name of a given atomic API method call.
 * Handles the following APIs:
 * - `Atomic[Reference|Integer|Long|Boolean]`;
 * - `Atomic[Reference|Integer|Long]Array`;
 * - `Atomic[Reference|Integer|Long]FieldUpdater`;
 * - `VarHandle`;
 * - `Unsafe`.
 *
 * @param atomic the atomic API method receiver for which the owner name is to be found.
 * @param arguments an array of arguments provided for the atomic operation.
 * @param atomicMethodDescriptor a descriptor providing metadata about the atomic method being analyzed.
 * @param shadowStackFrame the current shadow stack frame, representing the program's stack state during execution.
 * @param objectTracker the object tracker, used to provide representations of tracked objects.
 * @param constants a mapping from objects to their associated constant names.
 * @return a pair consisting of the determined owner name as a string,
 *   and a list of parameters associated with the atomic operation.
 */
internal fun findAtomicOwnerName(
    atomic: Any,
    arguments: Array<Any?>,
    atomicMethodDescriptor: AtomicMethodDescriptor,
    shadowStackFrame: ShadowStackFrame,
    objectTracker: ObjectTracker,
): Pair<String, List<Any?>> {
    val apiKind = atomicMethodDescriptor.apiKind
    var ownerName: String? = null
    var params: List<Any?>? = null

    fun getOwnerName(owner: Any?, className: String?, location: AccessLocation): String {
        val owner = findOwnerName(owner, className, UNKNOWN_CODE_LOCATION, shadowStackFrame, objectTracker)
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
        ownerName =
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

    var arrayAccess = "" // will contain `[i]` string denoting the accessed element index
                         // in case we are dealing with atomic arrays API
    if (apiKind == AtomicApiKind.ATOMIC_ARRAY) {
        val info = atomicMethodDescriptor.getAtomicArrayAccessInfo(atomic, arguments)
        arrayAccess = "[${(info.location as ArrayElementByIndexAccessLocation).index}]"
        params = info.arguments
    } else if (apiKind == AtomicApiKind.ATOMIC_OBJECT) {
        params = arguments.asList()
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