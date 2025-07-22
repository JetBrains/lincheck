/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor

/**
 * [LocalVariablesTracker] tracks active regions of variables in the method.
 */
internal class LocalVariablesTracker(
    visitor: MethodVisitor,
    val locals: MethodVariables
) : MethodVisitor(ASM_API, visitor) {
    override fun visitLabel(label: Label)  {
        locals.visitLabel(label)
        super.visitLabel(label)
    }
}
