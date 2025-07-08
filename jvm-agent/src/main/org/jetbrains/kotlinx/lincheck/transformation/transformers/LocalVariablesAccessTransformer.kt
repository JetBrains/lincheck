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

import org.jetbrains.lincheck.trace.TRACE_CONTEXT
import org.jetbrains.kotlinx.lincheck.trace.Types.convertAsmMethodType
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
    desc: String,
    isStatic: Boolean,
    private val locals: MethodVariables
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {
    private var turnoffTransform = false
    private val numberOfLocals = convertAsmMethodType(desc).argumentTypes.size  + if (isStatic) 0 else 1

    override fun visitVarInsn(opcode: Int, varIndex: Int) = adapter.run {
        val localVariableInfo = getVariableName(varIndex)?.takeIf { it.name != "this" }
        if (localVariableInfo == null || turnoffTransform) {
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

    fun runWithoutLocalVariablesTracking(block: () -> Unit) {
        val currentState = turnoffTransform
        try {
            turnoffTransform = true
            block()
        } finally {
            turnoffTransform = currentState
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun visitWriteVarInsn(localVariableInfo: LocalVariableInfo, opcode: Int, varIndex: Int) = adapter.run {
        invokeIfInAnalyzedCode(
            original = {
                visitVarInsn(opcode, varIndex)
            },
            instrumented = {
                // STACK: value
                val type = getVarInsOpcodeType(opcode)
                val local = newLocal(type)
                dup(type)
                storeLocal(local)
                // STACK: value
                visitVarInsn(opcode, varIndex)
                // STACK: <empty>
                loadNewCodeLocationId()
                val variableId = TRACE_CONTEXT.getOrCreateVariableId(localVariableInfo.name)
                push(variableId)
                loadLocal(local)
                box(type)
                // STACK: codeLocation, variableId, boxedValue
                invokeStatic(Injections::afterLocalWrite)
                // invokeBeforeEventIfPluginEnabled("write local")
                // STACK: <empty>
            }
        )
    }

    private fun visitReadVarInsn(localVariableInfo: LocalVariableInfo, opcode: Int, varIndex: Int) = adapter.run {
        // Skip variable read if it is in an unknown line of code and variable is argument
        // It can be code inserted by compiler to check Null invariant
        if (!isKnownLineNumber() && varIndex < numberOfLocals) {
            visitVarInsn(opcode, varIndex)
            return
        }
        invokeIfInAnalyzedCode(
            original = {
                visitVarInsn(opcode, varIndex)
            },
            instrumented = {
                // STACK: <empty>
                visitVarInsn(opcode, varIndex)
                // STACK: value
                val type = getVarInsOpcodeType(opcode)
                val local = newLocal(type)
                dup(type)
                storeLocal(local)
                // STACK: <empty>
                loadNewCodeLocationId()
                val variableId = TRACE_CONTEXT.getOrCreateVariableId(localVariableInfo.name)
                push(variableId)
                loadLocal(local)
                box(type)
                // STACK: codeLocation, variableId, boxedValue
                invokeStatic(Injections::afterLocalRead)
                // invokeBeforeEventIfPluginEnabled("read local")
                // STACK: <empty>
            }
        )
    }

    private fun getVariableName(varIndex: Int): LocalVariableInfo? {
        return locals.activeVariables.find { it.index == varIndex }
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
