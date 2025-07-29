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

import java.lang.reflect.Field
import java.lang.reflect.Modifier

abstract class AccessLocation
abstract class ObjectAccessLocation : AccessLocation()
abstract class ArrayAccessLocation : ObjectAccessLocation()

data class LocalVariableAccess(
    val variableName: String
) : AccessLocation()

abstract class FieldAccessLocation : ObjectAccessLocation() {
    abstract val className: String
    abstract val fieldName: String
}

data class StaticFieldAccess(
    override val className: String,
    override val fieldName: String,
) : FieldAccessLocation()

data class ObjectFieldAccess(
    override val className: String,
    override val fieldName: String,
) : FieldAccessLocation()

data class ArrayElementByIndexAccess(
    val index: Int
) : ArrayAccessLocation()

data class ObjectAccessMethodInfo(
    val obj: Any?,
    val location: ObjectAccessLocation,
    val arguments: List<Any?>,
)


class AccessPath(val locations: List<AccessLocation>) {

    init { validate(this) }

    constructor(location: AccessLocation) : this(listOf(location))

    constructor(vararg locations: AccessLocation) : this(locations.toList())

    override fun toString(): String {
        val builder = StringBuilder()
        for (location in locations) {
            when (location) {
                is LocalVariableAccess -> {
                    builder.append(location.variableName)
                }
                is StaticFieldAccess -> {
                    with(builder) {
                        append(".")
                        append(location.fieldName)
                    }
                }
                is ObjectFieldAccess -> {
                    with(builder) {
                        append(".")
                        append(location.fieldName)
                    }
                }
                is ArrayElementByIndexAccess -> {
                    with(builder) {
                        append('[')
                        append(location.index)
                        append(']')
                    }
                }
            }
        }
        return builder.toString()
    }

    companion object {
        fun validate(path: AccessPath) {
            check(path.locations.isNotEmpty()) {
                "Access path must not be empty"
            }
            check(path.locations.first()
                .let { it is LocalVariableAccess || it is StaticFieldAccess }
            ) {
                "Access path must start with local variable or static field access"
            }
            check(path.locations.drop(1)
                .all { it !is LocalVariableAccess && it !is StaticFieldAccess }
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
    return if (Modifier.isStatic(modifiers)) {
        StaticFieldAccess(className, fieldName)
    } else {
        ObjectFieldAccess(className, fieldName)
    }
}

fun AccessLocation.toAccessPath() = AccessPath(this)
fun AccessLocation.toOwnerName() = toAccessPath()

operator fun AccessPath.plus(location: AccessLocation): AccessPath =
    AccessPath(this.locations + location)

operator fun AccessPath.plus(other: AccessPath): AccessPath =
    AccessPath(this.locations + other.locations)