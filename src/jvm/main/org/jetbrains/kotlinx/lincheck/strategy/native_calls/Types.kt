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

import org.objectweb.asm.commons.Method
import sun.nio.ch.lincheck.Types
import sun.nio.ch.lincheck.Types.convertAsmMethodType

internal val TypeVoid = Types.Type.Void.get()
internal val TypeInt = Types.ArgumentType.Primitive.Int.get()
internal val TypeLong = Types.ArgumentType.Primitive.Long.get()
internal val TypeDouble = Types.ArgumentType.Primitive.Double.get()
internal val TypeFloat = Types.ArgumentType.Primitive.Float.get()
internal val TypeBoolean = Types.ArgumentType.Primitive.Boolean.get()
internal val TypeByte = Types.ArgumentType.Primitive.Byte.get()
internal val TypeShort = Types.ArgumentType.Primitive.Short.get()
internal val TypeChar = Types.ArgumentType.Primitive.Char.get()

internal fun Method.toMethodSignature() = Types.MethodSignature(this.name, convertAsmMethodType(this.descriptor))
internal fun java.lang.reflect.Method.toMethodSignature() = Method.getMethod(this).toMethodSignature()