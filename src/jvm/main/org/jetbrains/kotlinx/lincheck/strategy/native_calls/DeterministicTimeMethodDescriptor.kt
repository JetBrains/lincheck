/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.native_calls

import org.jetbrains.lincheck.trace.Types

internal fun getDeterministicTimeMethodDescriptorOrNull(
    methodCallInfo: MethodCallInfo
): DeterministicMethodDescriptor<*, *>? {
    if (methodCallInfo.ownerType != systemType) return null
    val methodName = methodCallInfo.methodSignature.name
    if (methodName != "nanoTime" && methodName != "currentTimeMillis") return null
    return PureDeterministicMethodDescriptor(methodCallInfo) { _, _ -> 1337L /* any constant value */ }
}

private val systemType = Types.ObjectType("java.lang.System")
