/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation.transformers

import sun.nio.ch.lincheck.Injections
import org.jetbrains.kotlinx.lincheck.transformation.LincheckBaseMethodVisitor
import org.jetbrains.kotlinx.lincheck.transformation.invokeStatic
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor

internal class IgnoredSectionWrapperTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : LincheckBaseMethodVisitor(fileName, className, methodName, adapter, methodVisitor) {

    private val tryBlock: Label = adapter.newLabel()
    private val catchBlock: Label = adapter.newLabel()

    override fun visitCode() = adapter.run {
        super.visitCode()
        invokeStatic(Injections::enterIgnoredSection)
        visitTryCatchBlock(tryBlock, catchBlock, catchBlock, null)
        visitLabel(tryBlock)
    }

    override fun visitInsn(opcode: Int) = adapter.run {
        when (opcode) {
            ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN -> {
                invokeStatic(Injections::leaveIgnoredSection)
            }
        }
        super.visitInsn(opcode)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) = adapter.run {
        visitLabel(catchBlock)
        invokeStatic(Injections::leaveIgnoredSection)
        visitInsn(ATHROW)
        super.visitMaxs(maxStack, maxLocals)
    }

}