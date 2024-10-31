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

import org.jetbrains.kotlinx.lincheck.strategy.managed.FieldSearchHelper.TraverseResult.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.OwnerWithName.*
import org.jetbrains.kotlinx.lincheck.traverseObjectGraph
import java.lang.reflect.Modifier


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
        return when (val result = findObjectField(testObject, value)) {
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

    private fun findObjectField(testObject: Any?, value: Any): TraverseResult {
        if (testObject == null) return NotFound

        var traverseResult: TraverseResult = NotFound
        var fieldName: OwnerWithName? = null
        val isTraverseCompleted = {
            traverseResult is MultipleFieldsMatching ||
            traverseResult is FoundInNonFinalField
        }

        traverseObjectGraph(
            testObject,
            onArrayElement = { _, _, _ -> false }, // do not traverse array elements further
            onField = { ownerObject, field, fieldValue ->
                if (field.type.isPrimitive || fieldValue == null) return@traverseObjectGraph false

                if (value === fieldValue && !isTraverseCompleted()) {
                    when {
                        fieldName != null -> {
                            traverseResult = MultipleFieldsMatching
                        }
                        !Modifier.isFinal(field.modifiers) -> {
                            traverseResult = FoundInNonFinalField
                        }
                        else -> {
                            fieldName = if (Modifier.isStatic(field.modifiers)) {
                                StaticOwnerWithName(field.name, /*ownerObject::class.java*/ field.declaringClass)
                            } else {
                                InstanceOwnerWithName(field.name, ownerObject!!)
                            }
                            traverseResult = FieldName(fieldName!!)
                        }
                    }
                    return@traverseObjectGraph false
                }

                return@traverseObjectGraph !isTraverseCompleted()
            }
        )

        return traverseResult
    }

}

internal sealed class OwnerWithName(val fieldName: String) {
    class StaticOwnerWithName(fieldName: String, val clazz: Class<*>) :
        OwnerWithName(fieldName)

    class InstanceOwnerWithName(fieldName: String, val owner: Any) :
        OwnerWithName(fieldName)
}