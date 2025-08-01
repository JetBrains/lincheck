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

import org.jetbrains.lincheck.analysis.isThisName
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
}

data class ArrayElementByIndexAccessLocation(
    val index: Int
) : ArrayAccessLocation()

data class ArrayElementByNameAccessLocation(
    val indexAccessPath: AccessPath
) : ArrayAccessLocation()

val ArrayLengthAccessLocation = ObjectFieldAccessLocation(
    TRACE_CONTEXT.getFieldDescriptor(
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

class AccessPath(val locations: List<AccessLocation>) {

    init { validate(this) }

    constructor(location: AccessLocation) : this(listOf(location))

    constructor(vararg locations: AccessLocation) : this(locations.toList())

    override fun toString(): String {
        val builder = StringBuilder()
        for (location in locations) {
            when (location) {
                is LocalVariableAccessLocation -> {
                    if (!isThisName(location.variableName)) {
                        builder.append(location.variableName)
                    }
                }
                is StaticFieldAccessLocation -> {
                    with(builder) {
                        // append(location.className.getSimpleClassName())
                        // append(".")
                        append(location.fieldName)
                    }
                }
                is ObjectFieldAccessLocation -> {
                    with(builder) {
                        append(".")
                        append(location.fieldName)
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
        val result = builder.toString()
        if (result.startsWith(".")) {
            return result.substring(1)
        }
        return result
    }

    companion object {
        fun validate(path: AccessPath) {
            check(path.locations.isNotEmpty()) {
                "Access path must not be empty"
            }
            check(path.locations.first()
                .let { it is LocalVariableAccessLocation || it is StaticFieldAccessLocation }
            ) {
                "Access path must start with local variable or static field access"
            }
            check(path.locations.drop(1)
                .all { it !is LocalVariableAccessLocation && it !is StaticFieldAccessLocation }
            ) {
                "Access path must not contain local variable or static field access in the middle"
            }
        }
    }
}

typealias OwnerName = AccessPath

fun Field.toAccessLocation(): FieldAccessLocation {
    val className = declaringClass.name
    val fieldName = name
    val isStatic = Modifier.isStatic(modifiers)
    val isFinal = Modifier.isFinal(modifiers)
    val descriptorId = TRACE_CONTEXT.getOrCreateFieldId(className, fieldName,
        isStatic = isStatic,
        isFinal = isFinal,
    )
    val descriptor = TRACE_CONTEXT.getFieldDescriptor(descriptorId)
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