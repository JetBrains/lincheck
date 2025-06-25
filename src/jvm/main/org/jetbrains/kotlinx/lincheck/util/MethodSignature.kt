/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.util

import org.jetbrains.kotlinx.lincheck.tracedata.MethodSignature
import org.jetbrains.kotlinx.lincheck.tracedata.Types
import org.objectweb.asm.commons.Method


internal fun Method.toMethodSignature() = MethodSignature(this.name, Types.convertAsmMethodType(this.descriptor))
internal fun java.lang.reflect.Method.toMethodSignature() = Method.getMethod(this).toMethodSignature()
