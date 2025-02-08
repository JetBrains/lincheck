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
import org.objectweb.asm.Opcodes
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
            // Don't instrument JSR at all
            if (opcode == Opcodes.JSR) {
                return
            }
            if (label == null) {
                return
            }

            seenInJump.add(label)

            // If this label is not seen, it is forward jump
            if (!seenInLabels.contains(label)) {
                return
            }

            val id = LabelsTracker.newJumpTarget(className, methodName, label)
            when (opcode) {
                Opcodes.IFEQ,
                Opcodes.IFNE,
                Opcodes.IFLT,
                Opcodes.IFGE,
                Opcodes.IFGT,
                Opcodes.IFLE -> {
                    // Can be optimized with 6 different methods without a secondary `when` inside
                    // ival -> ival, ival
                    dup()
                    // ival, ival -> ival, ival, opcode
                    push(opcode)
                    // ival, ival, opcode -> ival, ival, opcode, codeLocationId
                    loadNewCodeLocationId()
                    // ival, ival, opcode, codeLocationId -> ival, ival, opcode, codeLocationId, labelId
                    push(id)
                    // ival, ival, opcode, codeLocationId, labelId -> ival
                    invokeStatic(Injections::beforeIfBackBranch)
                }
                Opcodes.IF_ICMPEQ,
                Opcodes.IF_ICMPNE,
                Opcodes.IF_ICMPLT,
                Opcodes.IF_ICMPGE,
                Opcodes.IF_ICMPGT,
                Opcodes.IF_ICMPLE -> {
                    // Can be optimized with 6 different methods without a secondary `when` inside
                    // ival1, ival2 -> ival1, ival2, ival1, ival2
                    dup2()
                    // ival1, ival2, ival1, ival2 -> ival1, ival2, ival1, ival2, opcode
                    push(opcode)
                    // ival1, ival2, ival1, ival2, opcode -> ival1, ival2, ival1, ival2, opcode, codeLocationId
                    loadNewCodeLocationId()
                    // ival1, ival2, ival1, ival2, opcode, codeLocationId -> ival1, ival2, ival1, ival2, opcode, codeLocationId, labelId
                    push(id)
                    // ival1, ival2, ival1, ival2, opcode, codeLocationId, labelId -> ival
                    invokeStatic(Injections::beforeIfCmpBackBranch)
                }
                Opcodes.IF_ACMPEQ,
                Opcodes.IF_ACMPNE -> {
                    // Can be optimized with 2 different methods without a secondary `when` inside
                    // aref1, aref2 -> aref1, aref2, aref1, aref2
                    dup2()
                    // aref1, aref2, aref1, aref2 -> aref1, aref2, aref1, aref2, opcode
                    push(opcode)
                    // aref1, aref2, aref1, aref2, opcode -> aref1, aref2, aref1, aref2, opcode, codeLocationId
                    loadNewCodeLocationId()
                    // aref1, aref2, aref1, aref2, opcode, codeLocationId -> aref1, aref2, aref1, aref2, opcode, codeLocationId, labelId
                    push(id)
                    // aref1, aref2, aref1, aref2, opcode, codeLocationId, labelId -> aref
                    invokeStatic(Injections::beforeIfRefBackBranch)
                }
                Opcodes.GOTO -> {
                    loadNewCodeLocationId()
                    push(id)
                    invokeStatic(Injections::beforeBackBranch)
                }
                Opcodes.IFNULL,
                Opcodes.IFNONNULL -> {
                    // Can be optimized with 2 different methods without a secondary `when` inside
                    // aref -> aref, aref
                    dup()
                    // aref, aref -> aref, aref, opcode
                    push(opcode)
                    // aref, aref, opcode -> aref, aref, opcode, codeLocationId
                    loadNewCodeLocationId()
                    // aref, aref, opcode, codeLocationId -> aref, aref, opcode, codeLocationId, labelId
                    push(id)
                    // aref, aref, opcode, codeLocationId, labelId -> aref
                    invokeStatic(Injections::beforeIfNullBackBranch)
                }
            }
        }
        finally {
            visitJumpInsn(opcode, label)
        }
    }
}
