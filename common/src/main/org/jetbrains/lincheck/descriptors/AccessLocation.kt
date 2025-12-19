/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.descriptors

import org.jetbrains.lincheck.trace.*
import java.lang.reflect.Field
import java.lang.reflect.Modifier

abstract class AccessLocation
abstract class ObjectAccessLocation : AccessLocation()
abstract class ArrayAccessLocation : ObjectAccessLocation()

data class LocalVariableAccessLocation(
    val variableDescriptor: VariableDescriptor
) : AccessLocation() {
    val variableName: String get() = variableDescriptor.name
}

abstract class FieldAccessLocation : ObjectAccessLocation() {
    abstract val fieldDescriptor: FieldDescriptor
    val className get() = fieldDescriptor.className
    val fieldName get() = fieldDescriptor.fieldName
}

data class StaticFieldAccessLocation(
    override val fieldDescriptor: FieldDescriptor
) : FieldAccessLocation() {
    init {
        require(fieldDescriptor.isStatic) {
            "Static field access location must be constructed with static field descriptor"
        }
    }
}

data class ObjectFieldAccessLocation(
    override val fieldDescriptor: FieldDescriptor
) : FieldAccessLocation() {
    init {
        require(!fieldDescriptor.isStatic) {
            "Object field access location must be constructed with non-static field descriptor"
        }
    }

    companion object {
        fun createArrayLengthLocation(context: TraceContext) = ObjectFieldAccessLocation(
            context.getFieldDescriptor(
                /* NOTE: `java.lang.Array` does not actually exist in Java.
                 *   We added it here to handle `.length` accesses uniformly with regular instance field accesses,
                 *   and to avoid introducing special sub-class only for `.length` accesses.
                 *
                 * TODO: be careful on descriptors and Java reflection bridge APIs.
                 *   This design decision can backfire, so we might want to re-visit it in the future.
                 */
                className = "java.lang.Array",
                fieldName = "length",
                isStatic = false,
                isFinal = true,
            )
        )
    }
}

data class ArrayElementByIndexAccessLocation(
    val index: Int
) : ArrayAccessLocation()

data class ArrayElementByNameAccessLocation(
    val indexAccessPath: AccessPath
) : ArrayAccessLocation()

class AccessPath(val locations: List<AccessLocation>) {

    constructor(location: AccessLocation) : this(listOf(location))

    constructor(vararg locations: AccessLocation) : this(locations.toList())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AccessPath) return false
        return locations == other.locations
    }

    override fun hashCode(): Int {
        return locations.hashCode()
    }

    override fun toString(): String {
        val builder = StringBuilder()

        val locations = this.filterThisAccesses().locations
        for (i in locations.indices) {
            val location = locations[i]
            val nextLocation = locations.getOrNull(i + 1)

            when (location) {
                is LocalVariableAccessLocation -> {
                    with(builder) {
                        builder.append(location.variableName)
                        if (nextLocation is FieldAccessLocation) {
                            append(".")
                        }
                    }
                }
                is StaticFieldAccessLocation -> {
                    with(builder) {
                        append(location.fieldName)
                        if (nextLocation is FieldAccessLocation) {
                            append(".")
                        }
                    }
                }
                is ObjectFieldAccessLocation -> {
                    with(builder) {
                        append(location.fieldName)
                        if (nextLocation is FieldAccessLocation) {
                            append(".")
                        }
                    }
                }
                is ArrayElementByIndexAccessLocation -> {
                    with(builder) {
                        append('[')
                        append(location.index)
                        append(']')
                    }
                }
                is ArrayElementByNameAccessLocation -> {
                    with(builder) {
                        append('[')
                        append(location.indexAccessPath)
                        append(']')
                    }
                }
            }
        }

        return builder.toString()
    }
}

fun AccessPath.isEmpty(): Boolean = locations.isEmpty()

typealias OwnerName = AccessPath

fun Field.toAccessLocation(context: TraceContext): FieldAccessLocation {
    val className = declaringClass.name
    val fieldName = name
    val isStatic = Modifier.isStatic(modifiers)
    val isFinal = Modifier.isFinal(modifiers)
    val descriptorId = context.getOrCreateFieldId(className, fieldName,
        isStatic = isStatic,
        isFinal = isFinal,
    )
    val descriptor = context.getFieldDescriptor(descriptorId)
    return if (Modifier.isStatic(modifiers)) {
        StaticFieldAccessLocation(descriptor)
    } else {
        ObjectFieldAccessLocation(descriptor)
    }
}

fun AccessLocation.toAccessPath() = AccessPath(this)
fun AccessLocation.toOwnerName() = toAccessPath()

operator fun AccessPath.plus(location: AccessLocation?): AccessPath =
    if (location != null) AccessPath(this.locations + location) else this

operator fun AccessPath.plus(other: AccessPath): AccessPath =
    AccessPath(this.locations + other.locations)