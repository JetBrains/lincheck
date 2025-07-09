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
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicLongFieldUpdater
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

abstract class ObjectLocation

data class StaticFieldLocation(
    val className: String,
    val fieldName: String,
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
    require(arguments[1] is Long) {
        "Expected memory offset to be Long, but got ${arguments[1]?.javaClass?.name ?: "null"}"
    }

    val targetObject = arguments[0]
    val memoryOffset = arguments[1] as Long
    val remainingArguments = arguments.drop(2)

    return when {
        // Array access case
        targetObject != null && targetObject::class.java.isArray -> {
            val unsafe = UnsafeHolder.UNSAFE
            val arrayBaseOffset = unsafe.arrayBaseOffset(targetObject::class.java)
            val arrayIndexScale = unsafe.arrayIndexScale(targetObject::class.java)
            val index = ((memoryOffset - arrayBaseOffset) / arrayIndexScale).toInt()
            ObjectAccessMethodInfo(
                obj = targetObject,
                location = ArrayIndexLocation(index),
                arguments = remainingArguments
            )
        }

        // Static field access case
        targetObject is Class<*> -> {
            val fieldName = findFieldNameByOffsetViaUnsafe(targetObject, memoryOffset)
                ?: error("Failed to find field name by offset $memoryOffset")
            ObjectAccessMethodInfo(
                obj = null,
                location = StaticFieldLocation(
                    className = targetObject.name,
                    fieldName = fieldName,
                ),
                arguments = remainingArguments
            )
        }

        // Instance field access case
        targetObject != null -> {
            val className = targetObject::class.java.name
            val fieldName = findFieldNameByOffsetViaUnsafe(targetObject::class.java, memoryOffset)
                ?: error("Failed to find field name by offset $memoryOffset")
            ObjectAccessMethodInfo(
                obj = targetObject,
                location = ObjectFieldLocation(className, fieldName),
                arguments = remainingArguments
            )
        }

        // Unexpected case
        else -> error("Failed to determine unsafe object access location")
    }
}

internal fun AtomicMethodDescriptor.getAtomicFieldUpdaterAccessLocation(receiver: Any, arguments: Array<Any?>): ObjectAccessMethodInfo {
    require(apiKind == AtomicApiKind.ATOMIC_FIELD_UPDATER) {
        "Method is not an AtomicFieldUpdater method: $this"
    }
    require(isAtomicFieldUpdater(receiver)) {
        "Receiver is not a recognized Atomic*FieldUpdater type: ${receiver.javaClass.name}"
    }
    require(arguments.isNotEmpty()) {
        "Expected at least 1 argument, but got ${arguments.size}"
    }

    val targetObject = arguments[0]
    val remainingArguments = arguments.drop(1)

    // Extract the private offset value and find the matching field.
    // Cannot use neither reflection nor MethodHandles.Lookup, as they lead to a warning
    try {
        // extract `targetType`
        val tclassField = receiver.javaClass.getDeclaredField("tclass")
        val tclassOffset = UnsafeHolder.UNSAFE.objectFieldOffset(tclassField)
        val targetType = UnsafeHolder.UNSAFE.getObject(receiver, tclassOffset) as Class<*>
        // extract offset
        val offsetField = receiver.javaClass.getDeclaredField("offset")
        val offset = UnsafeHolder.UNSAFE.getLong(receiver, UnsafeHolder.UNSAFE.objectFieldOffset(offsetField))
        // lookup field name
        val fieldName = findFieldNameByOffsetViaUnsafe(targetType, offset)
            ?: error("Failed to find field name by offset $offset in ${targetType.name}")

        return ObjectAccessMethodInfo(
            obj = targetObject,
            location = ObjectFieldLocation(
                className = targetType.name,
                fieldName = fieldName
            ),
            arguments = remainingArguments
        )
    } catch (t: Throwable) {
        error("Failed to extract field information from atomic field updater: ${t.message}")
    }
}
