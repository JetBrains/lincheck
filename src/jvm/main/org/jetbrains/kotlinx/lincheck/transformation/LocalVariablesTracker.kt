/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.jetbrains.kotlinx.lincheck.transformation.*
import org.jetbrains.kotlinx.lincheck.tracedata.MethodDescriptor
import org.jetbrains.kotlinx.lincheck.tracedata.methodCache
import org.jetbrains.kotlinx.lincheck.transformation.transformers.LocalVariablesAccessTransformer
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Type
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.*
import sun.nio.ch.lincheck.*

/**
 * [LocalVariablesTracker] tracks method calls,
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal class LocalVariablesTracker(
    visitor: MethodVisitor,
    val locals: MethodVariables
) : MethodVisitor(ASM_API, visitor) {
    override fun visitLabel(label: Label)  {
        super.visitLabel(label)
        locals.visitLabel(label)
    }
}
