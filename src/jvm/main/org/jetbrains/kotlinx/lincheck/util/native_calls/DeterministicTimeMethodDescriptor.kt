/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util.native_calls

internal fun getDeterministicTimeMethodDescriptorOrNull(
    methodCallInfo: MethodCallInfo
): DeterministicMethodDescriptor<*, Long>? {
    if (methodCallInfo.ownerType != systemType) return null
    val methodName = methodCallInfo.methodSignature.name
    if (methodName != "nanoTime" && methodName != "currentTimeMillis") return null
    return PureDeterministicMethodDescriptor<Long>(methodCallInfo) { _, _ -> 1337L /* any constant value */ }
}

private val systemType = ArgumentType.Object("java.lang.System")
