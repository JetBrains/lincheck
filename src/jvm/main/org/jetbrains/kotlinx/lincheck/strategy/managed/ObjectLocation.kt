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
import org.jetbrains.lincheck.util.*
import org.jetbrains.kotlinx.lincheck.util.*
import java.util.concurrent.ConcurrentHashMap
import sun.misc.Unsafe

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
                location = ArrayElementByIndexAccess(index),
                arguments = remainingArguments
            )
        }

        // Static field access case
        targetObject is Class<*> -> {
            val fieldName = findFieldNameByOffsetViaUnsafe(targetObject, memoryOffset)
                ?: error("Failed to find field name by offset $memoryOffset")
            ObjectAccessMethodInfo(
                obj = null,
                location = StaticFieldAccess(
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
                location = ObjectFieldAccess(className, fieldName),
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
            location = ObjectFieldAccess(
                className = targetType.name,
                fieldName = fieldName
            ),
            arguments = remainingArguments
        )
    } catch (t: Throwable) {
        error("Failed to extract field information from atomic field updater: ${t.message}")
    }
}

internal fun AtomicMethodDescriptor.getVarHandleAccessLocation(varHandle: Any, arguments: Array<Any?>): ObjectAccessMethodInfo {
    require(apiKind == AtomicApiKind.VAR_HANDLE) {
        "Method is not a VarHandle method: $this"
    }
    require(isVarHandle(varHandle)) {
        "Receiver is not a recognized VarHandle type: ${varHandle.javaClass.name}"
    }

    val varHandleClassName = varHandle.javaClass.name
    return when {
        // Array access case
        isVarHandleArrayClass(varHandleClassName) -> {
            require(arguments.size >= 2) {
                "Expected at least 2 arguments for array VarHandle, but got ${arguments.size}"
            }
            require(arguments[1] is Int) {
                "Expected array index to be Int, but got ${arguments[1]?.javaClass?.name ?: "null"}"
            }
            val array = arguments[0]
            val index = arguments[1] as Int
            ObjectAccessMethodInfo(
                obj = array,
                location = ArrayElementByIndexAccess(index),
                arguments = arguments.drop(2)
            )
        }

        // Static field access case
        isVarHandleStaticFieldClass(varHandleClassName) -> {
            try {
                val extractor = varHandleStaticFieldExtractors.computeIfAbsent(varHandle.javaClass) {
                    VarHandleStaticFieldExtractor(it)
                }
                val receiverType = extractor.extractBase(varHandle)
                val fieldOffset = extractor.extractFieldOffset(varHandle)
                val fieldName = findFieldNameByOffsetViaUnsafe(receiverType, fieldOffset)
                    ?: error("Failed to find field name by offset $fieldOffset in ${receiverType.name}")

                ObjectAccessMethodInfo(
                    obj = null,
                    location = StaticFieldAccess(
                        className = receiverType.name,
                        fieldName = fieldName
                    ),
                    arguments = arguments.toList()
                )
            } catch (t: Throwable) {
                error("Failed to extract field information from static field VarHandle ${varHandleClassName}: ${t.message}")
            }
        }

        // Instance field access case
        isVarHandleInstanceFieldClass(varHandleClassName) -> {
            require(arguments.isNotEmpty()) {
                "Expected at least 1 argument for instance field VarHandle, but got ${arguments.size}"
            }
            require(arguments[0] != null) {
                "Target object cannot be null for instance field VarHandle"
            }
            try {
                val targetObject = arguments[0]
                val extractor = varHandleInstanceFieldExtractors.computeIfAbsent(varHandle.javaClass) {
                    VarHandleInstanceFieldExtractor(it)
                }
                val receiverType = extractor.extractReceiverType(varHandle)
                val fieldOffset = extractor.extractFieldOffset(varHandle)
                val fieldName = findFieldNameByOffsetViaUnsafe(receiverType, fieldOffset)
                    ?: error("Failed to find field name by offset $fieldOffset in ${receiverType.name}")

                ObjectAccessMethodInfo(
                    obj = targetObject,
                    location = ObjectFieldAccess(
                        className = receiverType.name,
                        fieldName = fieldName
                    ),
                    arguments = arguments.drop(1)
                )
            } catch (t: Throwable) {
                error("Failed to extract field information from instance field VarHandle ${varHandleClassName}: ${t.message}")
            }
        }

        // Unknown VarHandle class
        else -> error("Unsupported VarHandle type: $varHandleClassName")
    }
}

private class VarHandleStaticFieldExtractor(private val varHandleClass: Class<*>) {
    private val baseField = varHandleClass.getDeclaredField("base")
    private val fieldOffsetField = varHandleClass.getDeclaredField("fieldOffset")

    fun extractBase(varHandle: Any): Class<*> {
        return readFieldViaUnsafe(varHandle, baseField, Unsafe::getObject) as Class<*>
    }

    fun extractFieldOffset(varHandle: Any): Long {
        return readFieldViaUnsafe(varHandle, fieldOffsetField, Unsafe::getLong)
    }
}

private class VarHandleInstanceFieldExtractor(private val varHandleClass: Class<*>) {
    private val receiverTypeField = varHandleClass.getDeclaredField("receiverType")
    private val fieldOffsetField = varHandleClass.getDeclaredField("fieldOffset")

    fun extractReceiverType(varHandle: Any): Class<*> {
        return readFieldViaUnsafe(varHandle, receiverTypeField, Unsafe::getObject) as Class<*>
    }

    fun extractFieldOffset(varHandle: Any): Long {
        return readFieldViaUnsafe(varHandle, fieldOffsetField, Unsafe::getLong)
    }
}

private val varHandleStaticFieldExtractors = ConcurrentHashMap<Class<*>, VarHandleStaticFieldExtractor>()
private val varHandleInstanceFieldExtractors = ConcurrentHashMap<Class<*>, VarHandleInstanceFieldExtractor>()
