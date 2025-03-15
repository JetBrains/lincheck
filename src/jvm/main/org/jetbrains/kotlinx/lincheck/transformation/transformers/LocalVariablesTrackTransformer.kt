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
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import sun.nio.ch.lincheck.Injections

internal class LocalVariablesAccessTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
    private val locals: Map<Int, List<LocalVariableInfo>>,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    private val visitedLabels = HashSet<Label>()

    override fun visitLabel(label: Label) = adapter.run {
        visitedLabels += label
        visitLabel(label)
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
            visitWriteVarInsn(localVariableInfo)
        }

        super.visitVarInsn(opcode, varIndex)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun visitWriteVarInsn(localVariableInfo: LocalVariableInfo) = adapter.run {
        invokeIfInTestingCode(
            original = {
            },
            code = {
                val local = newLocal(OBJECT_TYPE)

                if (localVariableInfo.type.sort == Type.DOUBLE || localVariableInfo.type.sort == Type.LONG) {
                    dup2()
                    box(localVariableInfo.type)
                    storeLocal(local)
                } else {
                    dup()
                    box(localVariableInfo.type)
                    storeLocal(local)
                }

                loadNewCodeLocationId()
                push(localVariableInfo.name)

                loadLocal(local)
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
        check(localList.isNotEmpty())
        if (localList.size == 1) {
            return localList.first()
        }
        if (localList.isUniqueVariable()) {
            return localList.first()
        }
        // TODO: handle ambiguity
        return null
        // return findNameForLabelIndex(localList)
    }

    // TODO: does not work
    private fun findNameForLabelIndex(localList: List<LocalVariableInfo>) =
        localList.find { (_, range, _) -> 
            val (start, finish) = range
            start in visitedLabels && finish !in visitedLabels
        }
}

private fun List<LocalVariableInfo>.isUniqueVariable(): Boolean {
    val name = first().name
    return all { it.name == name }
}

internal data class LocalVariableInfo(val name: String, val labelIndexRange: Pair<Label, Label>, val type: Type)