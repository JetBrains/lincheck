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
import kotlin.time.measureTime

/**
 * [LabelsTracker] tracks active regions of variables and status of the labels in the method.
 */
internal class LabelsTracker(
    visitor: MethodVisitor,
    private val metaInfo: MethodMetaInfo
) : MethodVisitor(ASM_API, visitor) {
    override fun visitLabel(label: Label)  {
        metaInfo.locals.visitLabel(label)
        metaInfo.labels.visitLabel(label)
        super.visitLabel(label)
    }
}
