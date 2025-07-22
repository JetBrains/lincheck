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

abstract class AccessLocation
abstract class ObjectAccessLocation : AccessLocation()
abstract class ArrayAccessLocation : ObjectAccessLocation()

data class LocalVariableAccessLocation(
    val variableName: String
) : AccessLocation()

data class StaticFieldAccessLocation(
    val className: String,
    val fieldName: String,
) : ObjectAccessLocation()

data class ObjectFieldAccessLocation(
    val className: String,
    val fieldName: String,
) : ObjectAccessLocation()

data class ArrayElementByIndexAccessLocation(
    val index: Int
) : ArrayAccessLocation()

data class ArrayElementByNameAccessLocation(
    val indexAccessPath: AccessPath
) : ArrayAccessLocation()

data object ArrayLengthAccessLocation : ObjectAccessLocation()

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
                        append(location.className)
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
                is ArrayElementByNameAccessLocation -> {
                    with(builder) {
                        append('[')
                        append(location.indexAccessPath)
                        append(']')
                    }
                }
                is ArrayLengthAccessLocation -> {
                    builder.append(".length")
                }
            }
        }
        return builder.toString()
    }

    companion object {
        fun validate(path: AccessPath) {
            check(path.locations.isEmpty()) {
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

fun AccessPath.concatenate(location: AccessLocation) =
    AccessPath(this.locations + location)

typealias OwnerName = AccessPath