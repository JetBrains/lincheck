/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.tracedata

@ConsistentCopyVisibility
data class ClassDescriptor internal constructor(
    val name: String,
)

@ConsistentCopyVisibility
data class FieldDescriptor internal constructor(
    val classId: Int,
    val fieldName: String,
    val isStatic: Boolean,
    val isFinal: Boolean,
) {
    val className: String get() = TRACE_CONTEXT.getClassDescriptor(classId).name
}

@ConsistentCopyVisibility
data class VariableDescriptor internal constructor(
    val name: String,
)
