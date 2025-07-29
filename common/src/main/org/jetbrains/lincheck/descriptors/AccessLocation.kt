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

data class StaticFieldAccess(
    val className: String,
    val fieldName: String,
) : ObjectAccessLocation()

data class ObjectFieldAccess(
    val className: String,
    val fieldName: String,
) : ObjectAccessLocation()

data class ArrayElementByIndexAccess(
    val index: Int
) : ArrayAccessLocation()

data class ObjectAccessMethodInfo(
    val obj: Any?,
    val location: ObjectAccessLocation,
    val arguments: List<Any?>,
)