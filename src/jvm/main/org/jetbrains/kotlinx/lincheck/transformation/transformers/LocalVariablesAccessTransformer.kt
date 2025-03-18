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

    override fun visitVarInsn(opcode: Int, varIndex: Int) = adapter.run {
        val localVariableInfo = getVariableName(varIndex)?.takeIf { it.name != "this" }
        if (localVariableInfo == null) {
            visitVarInsn(opcode, varIndex)
            return
        }
        when (opcode) {
            Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD -> {
                visitReadVarInsn(localVariableInfo, opcode, varIndex)
                // visitVarInsn(opcode, varIndex)
            }
            Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE -> {
                visitWriteVarInsn(localVariableInfo, opcode, varIndex)
                // visitVarInsn(opcode, varIndex)
            }
            else -> {
                visitVarInsn(opcode, varIndex)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun visitWriteVarInsn(localVariableInfo: LocalVariableInfo, opcode: Int, varIndex: Int) = adapter.run {
        invokeIfInTestingCode(
            original = {
                visitVarInsn(opcode, varIndex)
            },
            code = {
                // STACK: value
                val type = getVarInsOpcodeType(opcode)
                val local = newLocal(type)
                dup(type)
                storeLocal(local)
                // STACK: value
                visitVarInsn(opcode, varIndex)
                // STACK: <empty>
                loadNewCodeLocationId()
                push(localVariableInfo.name)
                loadLocal(local)
                box(type)
                // STACK: codeLocation, varName, boxedValue
                invokeStatic(Injections::beforeLocalWrite)
                invokeBeforeEventIfPluginEnabled("write local")
                // STACK: <empty>
            }
        )
    }

    private fun visitReadVarInsn(localVariableInfo: LocalVariableInfo, opcode: Int, varIndex: Int) = adapter.run {
        invokeIfInTestingCode(
            original = {
                visitVarInsn(opcode, varIndex)
            },
            code = {
                // STACK: <empty>
                visitVarInsn(opcode, varIndex)
                // STACK: value
                val type = getVarInsOpcodeType(opcode)
                val local = newLocal(type)
                dup(type)
                storeLocal(local)
                // STACK: <empty>
                loadNewCodeLocationId()
                push(localVariableInfo.name)
                loadLocal(local)
                box(type)
                // STACK: codeLocation, varName, boxedValue
                invokeStatic(Injections::beforeLocalRead)
                invokeBeforeEventIfPluginEnabled("read local")
                // STACK: <empty>
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

    private fun getVarInsOpcodeType(opcode: Int) = when (opcode) {
        Opcodes.ILOAD -> Type.INT_TYPE
        Opcodes.LLOAD -> Type.LONG_TYPE
        Opcodes.FLOAD -> Type.FLOAT_TYPE
        Opcodes.DLOAD -> Type.DOUBLE_TYPE
        Opcodes.ALOAD -> OBJECT_TYPE

        Opcodes.ISTORE -> Type.INT_TYPE
        Opcodes.LSTORE -> Type.LONG_TYPE
        Opcodes.FSTORE -> Type.FLOAT_TYPE
        Opcodes.DSTORE -> Type.DOUBLE_TYPE
        Opcodes.ASTORE -> OBJECT_TYPE

        else -> throw IllegalArgumentException("Invalid opcode: $opcode")
    }
}

private fun List<LocalVariableInfo>.isUniqueVariable(): Boolean {
    val name = first().name
    val type = first().type
    return all { it.name == name && it.type == type }
}

internal data class LocalVariableInfo(val name: String, val labelIndexRange: Pair<Label, Label>, val type: Type)