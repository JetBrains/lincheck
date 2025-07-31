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

data class LocalVariableAccessLocation(
    val variableName: String
) : AccessLocation()

abstract class FieldAccessLocation : ObjectAccessLocation() {
    abstract val className: String
    abstract val fieldName: String
}

data class StaticFieldAccessLocation(
    override val className: String,
    override val fieldName: String,
) : FieldAccessLocation()

data class ObjectFieldAccessLocation(
    override val className: String,
    override val fieldName: String,
) : FieldAccessLocation()

data class ArrayElementByIndexAccessLocation(
    val index: Int
) : ArrayAccessLocation()


class AccessPath(val locations: List<AccessLocation>) {

    init { validate(this) }

    constructor(location: AccessLocation) : this(listOf(location))

    constructor(vararg locations: AccessLocation) : this(locations.toList())

    override fun toString(): String {
        val builder = StringBuilder()
        for (location in locations) {
            when (location) {
                is LocalVariableAccessLocation -> {
                    builder.append(location.variableName)
                }
                is StaticFieldAccessLocation -> {
                    with(builder) {
                        append(".")
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
    return if (Modifier.isStatic(modifiers)) {
        StaticFieldAccessLocation(className, fieldName)
    } else {
        ObjectFieldAccessLocation(className, fieldName)
    }
}

fun AccessLocation.toAccessPath() = AccessPath(this)
fun AccessLocation.toOwnerName() = toAccessPath()

operator fun AccessPath.plus(location: AccessLocation?): AccessPath =
    if (location != null) AccessPath(this.locations + location) else this

operator fun AccessPath.plus(other: AccessPath): AccessPath =
    AccessPath(this.locations + other.locations)