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

import org.jetbrains.kotlinx.lincheck.allDeclaredFieldWithSuperclasses
import org.jetbrains.kotlinx.lincheck.strategy.managed.FieldSearchHelper.TraverseResult.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.OwnerWithName.*
import java.lang.reflect.Modifier
import java.util.*


/**
 * Utility class that helps to determine if the provided field stored in only one
 * final field of a tested object.
 */
internal object FieldSearchHelper {

    /**
     * Determines if the [value] is stored in the only one field of the [testObject] and this
     * field is final.
     * In case the [value] is not found or accessible by multiple fields, the function returns `null`.
     */
    internal fun findFinalFieldWithOwner(testObject: Any, value: Any): OwnerWithName? = runCatching {
        val visitedObjects: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        return when (val result = findObjectField(testObject, value, visitedObjects)) {
            is FieldName -> result.field
            MultipleFieldsMatching, NotFound, FoundInNonFinalField -> null
        }
    }.getOrElse { exception ->
        exception.printStackTrace()
        null
    }

    private sealed interface TraverseResult {
        data object NotFound : TraverseResult
        data class FieldName(val field: OwnerWithName) : TraverseResult
        data object MultipleFieldsMatching : TraverseResult
        data object FoundInNonFinalField: TraverseResult
    }

    private fun findObjectField(testObject: Any?, value: Any, visitedObjects: MutableSet<Any>): TraverseResult {
        if (testObject == null) return NotFound
        var fieldName: OwnerWithName? = null
        // We take all the fields from the hierarchy.
        // If two or more fields match (===) the AtomicReference object, we fall back to the default behavior,
        // so there is no problem that we can receive some fields of the same name and the same type.
        for (field in testObject::class.java.allDeclaredFieldWithSuperclasses) {
            if (field.type.isPrimitive || !field.trySetAccessible()) continue
            val fieldValue = field.get(testObject)

            if (fieldValue in visitedObjects) continue
            visitedObjects += testObject

            if (fieldValue === value) {
                if (fieldName != null) return MultipleFieldsMatching
                if (!Modifier.isFinal(field.modifiers)) return FoundInNonFinalField

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
                        fieldName = result.field
                    }
                }

                MultipleFieldsMatching, FoundInNonFinalField -> return result
                NotFound -> {}
            }
        }
        return if (fieldName != null) FieldName(fieldName) else NotFound
    }

}

internal sealed class OwnerWithName(val fieldName: String) {
    class StaticOwnerWithName(fieldName: String, val clazz: Class<*>) :
        OwnerWithName(fieldName)

    class InstanceOwnerWithName(fieldName: String, val owner: Any) :
        OwnerWithName(fieldName)
}