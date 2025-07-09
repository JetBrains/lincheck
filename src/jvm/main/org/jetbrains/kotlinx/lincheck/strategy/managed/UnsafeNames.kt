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

import org.jetbrains.kotlinx.lincheck.strategy.managed.UnsafeName.*
import org.jetbrains.lincheck.util.UnsafeHolder
import org.jetbrains.lincheck.util.findFieldNameByOffsetViaUnsafe

/**
 * Helper object to provide the field name and the owner of the Unsafe method call.
 * When the Unsafe method is called with a receiver or a class and the offset of the field,
 * we extract the field name using it.
 */
internal object UnsafeNames {

    internal fun getMethodCallType(parameters: Array<Any?>): UnsafeName {
        if (parameters.size < 2) return TreatAsDefaultMethod

        val firstParameter = parameters[0]
        val secondParameter = parameters[1]
        if (secondParameter is Long) {
            return if (firstParameter is Class<*>) {
                // The First parameter is a Class object in case of static field access.
                val fieldName = findFieldNameByOffsetViaUnsafe(firstParameter, secondParameter)
                    ?: return TreatAsDefaultMethod
                UnsafeStaticMethod(firstParameter, fieldName, parameters.drop(2))
            } else if (firstParameter != null && firstParameter::class.java.isArray) {
                // The First parameter is an Array in case of array cell access.
                val unsafe = UnsafeHolder.UNSAFE
                val arrayBaseOffset = unsafe.arrayBaseOffset(firstParameter::class.java)
                val arrayIndexScale = unsafe.arrayIndexScale(firstParameter::class.java)
                val index = (secondParameter - arrayBaseOffset) / arrayIndexScale

                UnsafeArrayMethod(
                    array = firstParameter,
                    index = index.toInt(),
                    parametersToPresent = parameters.drop(2)
                )
            } else if (firstParameter != null) {
                // Then is an instance method call.
                val fieldName = findFieldNameByOffsetViaUnsafe(firstParameter::class.java, secondParameter)
                    ?: return TreatAsDefaultMethod
                UnsafeInstanceMethod(
                    owner = firstParameter,
                    fieldName = fieldName,
                    parametersToPresent = parameters.drop(2)
                )
            } else TreatAsDefaultMethod
        }

        return TreatAsDefaultMethod
    }
}

/**
 * Type of the Unsafe method call.
 */
internal sealed interface UnsafeName {
    /**
     * Field with name [fieldName] access method call of the [owner] object.
     */
    data class UnsafeInstanceMethod(
        val owner: Any,
        val fieldName: String,
        val parametersToPresent: List<Any?>
    ) : UnsafeName

    /**
     * Static field with name [fieldName] access method call of the [clazz] class.
     */
    data class UnsafeStaticMethod(
        val clazz: Class<*>,
        val fieldName: String,
        val parametersToPresent: List<Any?>
    ) : UnsafeName

    /**
     * Array [array] access method call by the index [index].
     */
    data class UnsafeArrayMethod(
        val array: Any,
        val index: Int,
        val parametersToPresent: List<Any?>
    ) : UnsafeName

    /**
     * Unrecognized Unsafe method call so we should present it 'as is'.
     */
    data object TreatAsDefaultMethod : UnsafeName
}
