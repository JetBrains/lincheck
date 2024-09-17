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

import org.jetbrains.kotlinx.lincheck.transformation.*
import org.objectweb.asm.*
import org.objectweb.asm.commons.GeneratorAdapter
import sun.nio.ch.lincheck.Injections

internal class LocalVariablesAnalyzerAdapter(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
    private val locals: Map<Int, List<LocalVariableInfo>>
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    private var currentLabelIndex = -1

    override fun visitLabel(label: Label) {
        currentLabelIndex++
        super.visitLabel(label)
    }

    override fun visitVarInsn(opcode: Int, varIndex: Int) {
        val localVariableInfo = getVariableName(varIndex)?.takeIf { it.name != "this" } ?: run {
            super.visitVarInsn(opcode, varIndex)
            return
        }
        val isRead = when (opcode) {
            Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD -> true
            Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE -> false
            else -> run {
                super.visitVarInsn(opcode, varIndex)
                return
            }
        }

        if (isRead) {
            visitReadVarInsn(localVariableInfo, opcode, varIndex)
        } else {
            visitWriteVarInsn(localVariableInfo, opcode, varIndex)
        }

        super.visitVarInsn(opcode, varIndex)
    }

    private fun visitWriteVarInsn(localVariableInfo: LocalVariableInfo, opcode: Int, varIndex: Int) = adapter.run {
        invokeIfInTestingCode(
            original = {
            },
            code = {
                val local = newLocal(localVariableInfo.type)
                dup()
                storeLocal(local)

                loadNewCodeLocationId()
                push(localVariableInfo.name)

                loadLocal(local)
                box(localVariableInfo.type)
                invokeStatic(Injections::beforeLocalWrite)

                invokeBeforeEventIfPluginEnabled("write local")
            }
        )
    }

    private fun visitReadVarInsn(localVariableInfo: LocalVariableInfo, opcode: Int, varIndex: Int) = adapter.run {
        invokeIfInTestingCode(
            original = {
            },
            code = {
                loadNewCodeLocationId()
                push(localVariableInfo.name)

                mv.visitVarInsn(opcode, varIndex)
                box(localVariableInfo.type)

                invokeStatic(Injections::beforeLocalRead)
                invokeBeforeEventIfPluginEnabled("read local")
            }
        )
    }

    private fun getVariableName(varIndex: Int): LocalVariableInfo? {
        val localList = locals[varIndex] ?: return null
        val result = findNameForLabelIndex(currentLabelIndex, localList)

        return result // ?: findNameForLabelIndex(currentLabelIndex + 1, localList)
    }

    private fun findNameForLabelIndex(index: Int, localList: List<LocalVariableInfo>) =
        localList.find { (_, range) -> index + 1 in range }
}