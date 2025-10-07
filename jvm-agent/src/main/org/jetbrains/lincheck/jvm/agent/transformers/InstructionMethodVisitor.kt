/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.jvm.agent.transformers

import org.jetbrains.lincheck.jvm.agent.LincheckMethodVisitor
import org.jetbrains.lincheck.jvm.agent.MethodInformation
import org.objectweb.asm.*
import org.objectweb.asm.commons.GeneratorAdapter

/**
 * A utility [MethodVisitor] that centralizes handling of bytecode instructions
 * by invoking [beforeInsn] just before visiting an instruction and [afterInsn]
 * immediately after visiting it. This removes duplication across different
 * visit* methods and gives a single interception point per opcode.
 *
 * Non-opcode pseudo nodes such as labels, line numbers, and frames are passed
 * through without calling hooks.
 */

/**
 * Abstract class that extends `LincheckMethodVisitor` for processing instructions uniformly.
 * Allows injecting visitor-specific logic before and after processing of each instruction.
 * Maintains an index of the current instruction being processed.
 */
internal abstract class InstructionMethodVisitor(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, adapter, methodVisitor) {

    /**
     * Tracks the current instruction index being processed.
     */
    protected var currentInsnIndex: Int = -1
        private set

    /**
     * Hook invoked before processing an instruction.
     *
     * @param index The index of the instruction being processed.
     * @param opcode The opcode of the instruction being processed.
     */
    protected open fun beforeInsn(index: Int, opcode: Int) {}

    /**
     * Hook invoked after processing an instruction.
     *
     * @param index The index of the instruction that was processed.
     * @param opcode The opcode of the instruction that was processed.
     */
    protected open fun afterInsn(index: Int, opcode: Int) {}

    private inline fun processInstruction(opcode: Int, emit: () -> Unit) {
        val index = ++currentInsnIndex
        beforeInsn(index, opcode)
        emit()
        afterInsn(index, opcode)
    }

    override fun visitInsn(opcode: Int) =
        processInstruction(opcode) { super.visitInsn(opcode) }

    override fun visitIntInsn(opcode: Int, operand: Int) =
        processInstruction(opcode) { super.visitIntInsn(opcode, operand) }

    override fun visitVarInsn(opcode: Int, `var`: Int) =
        processInstruction(opcode) { super.visitVarInsn(opcode, `var`) }

    override fun visitTypeInsn(opcode: Int, type: String) =
        processInstruction(opcode) { super.visitTypeInsn(opcode, type) }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) =
        processInstruction(opcode) { super.visitFieldInsn(opcode, owner, name, descriptor) }

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) =
        processInstruction(opcode) { super.visitMethodInsn(opcode, owner, name, descriptor, isInterface) }

    override fun visitInvokeDynamicInsn(name: String, descriptor: String, bootstrapMethodHandle: Handle, vararg bootstrapMethodArguments: Any) =
        processInstruction(Opcodes.INVOKEDYNAMIC) {
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
        }

    override fun visitJumpInsn(opcode: Int, label: Label) =
        processInstruction(opcode) { super.visitJumpInsn(opcode, label) }

    override fun visitLdcInsn(value: Any) =
        processInstruction(Opcodes.LDC) { super.visitLdcInsn(value) }

    override fun visitIincInsn(`var`: Int, increment: Int) =
        processInstruction(Opcodes.IINC) { super.visitIincInsn(`var`, increment) }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) =
        processInstruction(Opcodes.TABLESWITCH) { super.visitTableSwitchInsn(min, max, dflt, *labels) }

    override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray, labels: Array<Label>) =
        processInstruction(Opcodes.LOOKUPSWITCH) { super.visitLookupSwitchInsn(dflt, keys, labels) }

    override fun visitMultiANewArrayInsn(descriptor: String, numDimensions: Int) =
        processInstruction(Opcodes.MULTIANEWARRAY) { super.visitMultiANewArrayInsn(descriptor, numDimensions) }

    override fun visitLabel(label: Label) {
        super.visitLabel(label)
    }

    override fun visitLineNumber(line: Int, start: Label) {
        super.visitLineNumber(line, start)
    }

    override fun visitFrame(type: Int, nLocal: Int, local: Array<Any>?, nStack: Int, stack: Array<Any>?) {
        super.visitFrame(type, nLocal, local, nStack, stack)
    }
}
