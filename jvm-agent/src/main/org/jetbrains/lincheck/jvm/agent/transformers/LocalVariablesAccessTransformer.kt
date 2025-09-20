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

import org.jetbrains.lincheck.descriptors.Types.convertAsmMethodType
import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.trace.*
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.commons.*
import sun.nio.ch.lincheck.*

internal class LocalVariablesAccessTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
    private val locals: MethodVariables,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, adapter, methodVisitor) {
    private var firstLabelVisited = false

    override fun visitLabel(label: Label) = adapter.run {
        if (!firstLabelVisited) {
            firstLabelVisited = true
            super.visitLabel(label)
            return
        }

        // For each variable that starts at this label, read its value and inject an afterLocalWrite call
        locals.variablesStartAt(label).forEach {
            registerLocalVariableWrite(it)
        }

        super.visitLabel(label)
    }

    override fun visitIincInsn(varIndex: Int, increment: Int) {
        super.visitIincInsn(varIndex, increment)
        getVariableInfo(varIndex)?.let {
            adapter.registerLocalVariableWrite(it)
        }
    }

    private fun GeneratorAdapter.registerLocalVariableWrite(localVariableInfo: LocalVariableInfo) {
        invokeIfInAnalyzedCode(
            original = {},
            instrumented = {
                // STACK: <empty>
                loadNewCodeLocationId()
                val variableId = TRACE_CONTEXT.getOrCreateVariableId(localVariableInfo.name)
                push(variableId)
                // VerifyError with `loadLocal(..)`, here is a workaround
                visitVarInsn(localVariableInfo.type.getOpcode(ILOAD), localVariableInfo.index)
                box(localVariableInfo.type)
                // STACK: codeLocation, variableId, boxedValue
                invokeStatic(Injections::afterLocalWrite)
                // invokeBeforeEventIfPluginEnabled("write local")
                // STACK: <empty>
            }
        )
    }

    override fun visitVarInsn(opcode: Int, varIndex: Int): Unit = adapter.run {
        visitVarInsn(opcode, varIndex)

        getVariableInfo(varIndex)?.let { localVariableInfo ->
            if (opcode == localVariableInfo.type.getOpcode(ISTORE)) {
                registerLocalVariableWrite(localVariableInfo)
            }
        }
    }

    private fun getVariableInfo(varIndex: Int): LocalVariableInfo? {
        return methodInfo.locals.activeVariables.find { it.index == varIndex }
    }
}
