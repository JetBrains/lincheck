/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.analysis

import org.jetbrains.lincheck.descriptors.*
import org.jetbrains.lincheck.util.*

/**
 * Represents a shadow stack frame used to reflect the program's stack in [org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategy].
 *
 * @property instance the object on which the method was invoked, null in the case of a static method.
  */
class ShadowStackFrame(val instance: Any?) {
    val instanceClassName = instance?.javaClass?.name
}

fun ShadowStackFrame.isCurrentStackFrameReceiver(obj: Any): Boolean =
    (obj === instance)

fun ShadowStackFrame.findCurrentReceiverFieldReferringTo(obj: Any): FieldAccessLocation? {
    val field = instance?.findInstanceFieldReferringTo(obj)
    return field?.toAccessLocation()
}