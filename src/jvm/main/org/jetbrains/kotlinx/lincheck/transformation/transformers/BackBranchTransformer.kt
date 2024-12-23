/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation.transformers

import org.objectweb.asm.Label
import org.objectweb.asm.commons.GeneratorAdapter
import org.jetbrains.kotlinx.lincheck.transformation.*
import sun.nio.ch.lincheck.Injections

/**
 * [BackBranchTransformer] tracks all labels and jumo instructions, and instruments
 * such jumps to detect loops.
 */
internal class BackBranchTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    val seenInJump = HashSet<Label>()
    val seenInLabels = HashSet<Label>()

    override fun visitLabel(label: Label?) = adapter.run {
        visitLabel(label)

        if (label == null) {
            return
        }

        seenInLabels.add(label)

        // If this label was seen in "jump" instruction,
        // it means it could not be a back-branch label and should not be instrumented
        if (seenInJump.contains(label)) {
            return
        }

        // Instrument label
        val id = LabelsTracker.newLabel(className, methodName, label)
        loadNewCodeLocationId()
        push(id)
        invokeStatic(Injections::afterPossibleBackBranchTarget)
    }

    override fun visitJumpInsn(opcode: Int, label: Label?) = adapter.run {
        try {
            if (label == null) {
                return
            }

            seenInJump.add(label)

            // If this label is not seen, it is forward jump
            if (!seenInLabels.contains(label)) {
                return
            }

            val id = LabelsTracker.newJumpTarget(className, methodName, label)
            loadNewCodeLocationId()
            push(id)
            invokeStatic(Injections::beforeBackBranch)
        }
        finally {
            visitJumpInsn(opcode, label)
        }
    }
}
