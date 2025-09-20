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
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
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

    private val isStatic: Boolean = (access and ACC_STATIC != 0)

    private val numberOfLocals = convertAsmMethodType(descriptor).argumentTypes.size + if (isStatic) 0 else 1

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

    override fun visitVarInsn(opcode: Int, varIndex: Int) = adapter.run {
        val localVariableInfo = getVariableInfo(varIndex)?.takeIf { it.name != "this" }
        if (localVariableInfo == null) {
            super.visitVarInsn(opcode, varIndex)
            return
        }
        when (opcode) {
            ILOAD, LLOAD, FLOAD, DLOAD, ALOAD -> {
                visitReadVarInsn(localVariableInfo, opcode, varIndex)
            }
            ISTORE, LSTORE, FSTORE, DSTORE, ASTORE -> {
                visitWriteVarInsn(localVariableInfo, opcode, varIndex)
            }
            else -> {
                super.visitVarInsn(opcode, varIndex)
            }
        }
    }

    override fun visitIincInsn(varIndex: Int, increment: Int) {
        super.visitIincInsn(varIndex, increment)
        getVariableInfo(varIndex)?.let {
            adapter.registerLocalVariableWrite(it)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun GeneratorAdapter.visitWriteVarInsn(localVariableInfo: LocalVariableInfo, opcode: Int, varIndex: Int) {
        invokeIfInAnalyzedCode(
            original = {
                super.visitVarInsn(opcode, varIndex)
            },
            instrumented = {
                // STACK: value
                val type = getVarInsOpcodeType(opcode)
                val local = newLocal(type)
                dup(type)
                storeLocal(local)
                // STACK: value
                super.visitVarInsn(opcode, varIndex)
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

    private fun GeneratorAdapter.visitReadVarInsn(localVariableInfo: LocalVariableInfo, opcode: Int, varIndex: Int) {
        // Skip variable read if it is in an unknown line of code and variable is argument
        // It can be code inserted by compiler to check Null invariant
        if (!isKnownLineNumber() && varIndex < numberOfLocals) {
            super.visitVarInsn(opcode, varIndex)
            return
        }
        invokeIfInAnalyzedCode(
            original = {
                super.visitVarInsn(opcode, varIndex)
            },
            instrumented = {
                // STACK: <empty>
                super.visitVarInsn(opcode, varIndex)
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

    private fun getVariableInfo(varIndex: Int): LocalVariableInfo? {
        return methodInfo.locals.activeVariables.find { it.index == varIndex }
    }

    private fun getVarInsOpcodeType(opcode: Int) = when (opcode) {
        ILOAD -> Type.INT_TYPE
        LLOAD -> Type.LONG_TYPE
        FLOAD -> Type.FLOAT_TYPE
        DLOAD -> Type.DOUBLE_TYPE
        ALOAD -> OBJECT_TYPE

        ISTORE -> Type.INT_TYPE
        LSTORE -> Type.LONG_TYPE
        FSTORE -> Type.FLOAT_TYPE
        DSTORE -> Type.DOUBLE_TYPE
        ASTORE -> OBJECT_TYPE

        else -> throw IllegalArgumentException("Invalid opcode: $opcode")
    }
}
