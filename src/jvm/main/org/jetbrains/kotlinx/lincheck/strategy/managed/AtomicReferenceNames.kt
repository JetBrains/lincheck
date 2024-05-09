/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

import kotlinx.atomicfu.AtomicArray
import kotlinx.atomicfu.AtomicBooleanArray
import kotlinx.atomicfu.AtomicIntArray
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicReferenceMethodType.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.OwnerWithName.*
import java.util.*
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Provides method call type to create a more convenient trace point
 * with a owner of this AtomicReference field and a name if it can be found.
 * Recursively scans the test object, trying to find the provided AtomicReference
 * instance as a field. If two or more fields contain this AtomicReference field, then we
 * fall back to the default behavior.
 */
internal object AtomicReferenceNames {

    internal fun getMethodCallType(
        testObject: Any,
        atomicReference: Any,
        parameters: Array<Any?>
    ): AtomicReferenceMethodType {
        val receiverAndName = FieldSearchHelper.findFinalFieldWithOwner(testObject, atomicReference)
        return if (receiverAndName != null) {
            if (isAtomicArrayIndexMethodCall(atomicReference, parameters)) {
                when (receiverAndName) {
                    is InstanceOwnerWithName -> InstanceFieldAtomicArrayMethod(receiverAndName.owner, receiverAndName.fieldName, parameters[0] as Int)
                    is StaticOwnerWithName -> StaticFieldAtomicArrayMethod(receiverAndName.clazz, receiverAndName.fieldName, parameters[0] as Int)
                }
            } else {
                when (receiverAndName) {
                    is InstanceOwnerWithName -> AtomicReferenceInstanceMethod(receiverAndName.owner, receiverAndName.fieldName)
                    is StaticOwnerWithName -> AtomicReferenceStaticMethod(receiverAndName.clazz, receiverAndName.fieldName)
                }
            }
        } else {
            if (isAtomicArrayIndexMethodCall(atomicReference, parameters)) {
                AtomicArrayMethod(atomicReference, parameters[0] as Int)
            } else {
                TreatAsDefaultMethod
            }
        }
    }

    private fun isAtomicArrayIndexMethodCall(atomicReference: Any, parameters: Array<Any?>): Boolean {
        if (parameters.firstOrNull() !is Int) return false
        return atomicReference is AtomicReferenceArray<*> ||
                atomicReference is AtomicLongArray ||
                atomicReference is AtomicIntegerArray ||
                atomicReference is AtomicIntArray ||
                atomicReference is AtomicArray<*> ||
                atomicReference is AtomicBooleanArray
    }


}

/**
 * The type of the AtomicReference method call.
 */
internal sealed interface AtomicReferenceMethodType {
    /**
     * AtomicArray method call. In this case, we cannot find the owner of this atomic array.
     */
    data class AtomicArrayMethod(val atomicArray: Any, val index: Int) : AtomicReferenceMethodType

    /**
     * AtomicArray method call. Returned if we found the [owner] and the [field], containing this AtomicArray.
     */
    data class InstanceFieldAtomicArrayMethod(val owner: Any, val fieldName: String, val index: Int) :
        AtomicReferenceMethodType

    /**
     * Static AtomicArray method call.
     */
    data class StaticFieldAtomicArrayMethod(val ownerClass: Class<*>, val fieldName: String, val index: Int) :
        AtomicReferenceMethodType

    /**
     * AtomicReference method call. Returned if we cannot find the owner of this atomic reference.
     */
    data object TreatAsDefaultMethod : AtomicReferenceMethodType

    /**
     * Instance AtomicReference method call. Returned if we found the [owner] and the [fieldName], containing this AtomicArray
     */
    data class AtomicReferenceInstanceMethod(val owner: Any, val fieldName: String) : AtomicReferenceMethodType

    /**
     *  Static AtomicReference method call. Returned if we found the [ownerClass] and the [fieldName], containing this AtomicArray
     */
    data class AtomicReferenceStaticMethod(val ownerClass: Class<*>, val fieldName: String) : AtomicReferenceMethodType
}
