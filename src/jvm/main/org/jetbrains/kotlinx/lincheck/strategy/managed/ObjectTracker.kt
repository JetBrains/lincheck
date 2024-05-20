/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed

/**
 * Tracks object allocations and changes in object graph topology.
 */
interface ObjectTracker {

    fun registerNewObject(obj: Any)

    fun registerObjectLink(fromObject: Any, toObject: Any?)

    fun isTrackedObject(obj: Any): Boolean

    fun reset()
}

/**
 * Special auxiliary object used as an owner of static fields (instead of `null`).
 */
internal object StaticObject: Any()