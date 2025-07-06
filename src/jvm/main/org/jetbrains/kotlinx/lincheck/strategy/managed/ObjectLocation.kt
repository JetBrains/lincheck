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

abstract class ObjectLocation

data class StaticFieldLocation(
    val className: String
)

data class ObjectFieldLocation(
    val className: String,
    val fieldName: String,
) : ObjectLocation()

data class ArrayIndexLocation(
    val index: Int
) : ObjectLocation()

