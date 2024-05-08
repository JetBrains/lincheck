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
import org.jetbrains.kotlinx.lincheck.allDeclaredFieldWithSuperclasses
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicReferenceMethodType.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicReferenceNames.AtomicReferenceOwnerWithName.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.AtomicReferenceNames.TraverseResult.*
import java.lang.reflect.Modifier
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
        val receiverAndName = getAtomicReferenceReceiverAndName(testObject, atomicReference)
        return if (receiverAndName != null) {
            if (isAtomicArrayIndexMethodCall(atomicReference, parameters)) {
                when (receiverAndName) {
                    is InstanceOwnerWithName -> InstanceFieldAtomicArrayMethod(receiverAndName.receiver, receiverAndName.fieldName, parameters[0] as Int)
                    is StaticOwnerWithName -> StaticFieldAtomicArrayMethod(receiverAndName.clazz, receiverAndName.fieldName, parameters[0] as Int)
                }
            } else {
                when (receiverAndName) {
                    is InstanceOwnerWithName -> AtomicReferenceInstanceMethod(receiverAndName.receiver, receiverAndName.fieldName)
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

    private fun getAtomicReferenceReceiverAndName(testObject: Any, reference: Any): AtomicReferenceOwnerWithName? =
        runCatching {
            val visitedObjects: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
            return when (val result = findObjectField(testObject, reference, visitedObjects)) {
                is FieldName -> result.fieldName
                MultipleFieldsMatching, NotFound -> null
            }
        }.getOrElse { exception ->
            exception.printStackTrace()
            null
        }

    private sealed interface TraverseResult {
        data object NotFound : TraverseResult
        data class FieldName(val fieldName: AtomicReferenceOwnerWithName) : TraverseResult
        data object MultipleFieldsMatching : TraverseResult
    }

    private fun findObjectField(testObject: Any?, value: Any, visitedObjects: MutableSet<Any>): TraverseResult {
        if (testObject == null) return NotFound
        var fieldName: AtomicReferenceOwnerWithName? = null
        for (field in testObject::class.java.allDeclaredFieldWithSuperclasses) {
            if (field.type.isPrimitive || !field.trySetAccessible()) continue
            val fieldValue = field.get(testObject)

            if (fieldValue in visitedObjects) continue
            visitedObjects += testObject

            if (fieldValue === value) {
                if (fieldName != null) return MultipleFieldsMatching

                fieldName = if (Modifier.isStatic(field.modifiers)) {
                    StaticOwnerWithName(field.name, testObject::class.java)
                } else {
                    InstanceOwnerWithName(field.name, testObject)
                }
                continue
            }
            when (val result = findObjectField(fieldValue, value, visitedObjects)) {
                is FieldName -> {
                    if (fieldName != null) {
                        return MultipleFieldsMatching
                    } else {
                        fieldName = result.fieldName
                    }
                }

                MultipleFieldsMatching -> return result
                NotFound -> {}
            }
        }
        return if (fieldName != null) FieldName(fieldName) else NotFound
    }

    private sealed class AtomicReferenceOwnerWithName(val fieldName: String) {
        class StaticOwnerWithName(fieldName: String, val clazz: Class<*>) :
            AtomicReferenceOwnerWithName(fieldName)

        class InstanceOwnerWithName(fieldName: String, val receiver: Any) :
            AtomicReferenceOwnerWithName(fieldName)
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
