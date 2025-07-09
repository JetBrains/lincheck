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

import org.jetbrains.kotlinx.lincheck.util.*

abstract class ObjectLocation

data class StaticFieldLocation(
    val className: String
) : ObjectLocation()

data class ObjectFieldLocation(
    val className: String,
    val fieldName: String,
) : ObjectLocation()

data class ArrayIndexLocation(
    val index: Int
) : ObjectLocation()

data class ObjectAccessMethodInfo(
    val obj: Any?,
    val location: ObjectLocation,
    val arguments: List<Any?>,
)

internal fun AtomicMethodDescriptor.getUnsafeAccessLocation(receiver: Any, arguments: Array<Any?>): ObjectAccessMethodInfo {
    require(apiKind == AtomicApiKind.UNSAFE) {
        "Method is not an Unsafe method: $this"
    }
    require(arguments.size >= 2) {
        "Expected at least 2 arguments, but got ${arguments.size}"
    }

    val firstParameter = arguments[0]
    val secondParameter = arguments[1]

    if (secondParameter is Long) {
        if (firstParameter is Class<*>) {
            // The First parameter is a Class object in case of static field access.
            val fieldName = findFieldNameByOffsetViaUnsafe(firstParameter, secondParameter)
                ?: return ObjectAccessMethodInfo(
                    obj = null,
                    location = StaticFieldLocation("Unknown"),
                    arguments = arguments.drop(2)
                )

            return ObjectAccessMethodInfo(
                obj = null,
                location = StaticFieldLocation(firstParameter.name),
                arguments = arguments.drop(2)
            )
        } else if (firstParameter != null && firstParameter::class.java.isArray) {
            // The First parameter is an Array in case of array cell access.
            val unsafe = UnsafeHolder.UNSAFE
            val arrayBaseOffset = unsafe.arrayBaseOffset(firstParameter::class.java)
            val arrayIndexScale = unsafe.arrayIndexScale(firstParameter::class.java)
            val index = (secondParameter - arrayBaseOffset) / arrayIndexScale

            return ObjectAccessMethodInfo(
                obj = firstParameter,
                location = ArrayIndexLocation(index.toInt()),
                arguments = arguments.drop(2)
            )
        } else if (firstParameter != null) {
            // Then is an instance method call.
            val fieldName = findFieldNameByOffsetViaUnsafe(firstParameter::class.java, secondParameter)
                ?: return ObjectAccessMethodInfo(
                    obj = firstParameter,
                    location = ObjectFieldLocation(firstParameter::class.java.name, "Unknown"),
                    arguments = arguments.drop(2)
                )

            return ObjectAccessMethodInfo(
                obj = firstParameter,
                location = ObjectFieldLocation(firstParameter::class.java.name, fieldName),
                arguments = arguments.drop(2)
            )
        }
    }

    // Default case for unrecognized patterns
    return ObjectAccessMethodInfo(
        obj = null,
        location = StaticFieldLocation("Unknown"),
        arguments = arguments.toList()
    )
}
