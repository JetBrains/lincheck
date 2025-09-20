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
    val configuration: TransformationConfiguration,
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

        if (configuration.trackLocalVariableWrites) {
            // For each variable that starts at this label, read its value and inject an `afterLocalWrite` call
            methodInfo.locals.variablesStartAt(label).forEach {
                registerLocalVariableAccess(it, AccessType.WRITE)
            }
        }

        super.visitLabel(label)
    }

    override fun visitVarInsn(opcode: Int, varIndex: Int) = adapter.run {
        val localVariableInfo = getVariableInfo(varIndex)?.takeIf { it.name != "this" }
        if (localVariableInfo == null) {
            super.visitVarInsn(opcode, varIndex)
            return
        }
        when {
            isLoadOpcode(opcode) && configuration.trackLocalVariableReads -> {
                visitReadVarInsn(localVariableInfo, opcode, varIndex)
            }
            isStoreOpcode(opcode) && configuration.trackLocalVariableWrites -> {
                visitWriteVarInsn(localVariableInfo, opcode, varIndex)
            }
            else -> {
                super.visitVarInsn(opcode, varIndex)
            }
        }
    }

    override fun visitIincInsn(varIndex: Int, increment: Int): Unit = adapter.run {
        super.visitIincInsn(varIndex, increment)
        if (configuration.trackLocalVariableWrites) {
            getVariableInfo(varIndex)?.let {
                registerLocalVariableAccess(it, AccessType.WRITE)
            }
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
                super.visitVarInsn(opcode, varIndex)
                // STACK: <empty>
                registerLocalVariableAccess(localVariableInfo, AccessType.WRITE)
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
                registerLocalVariableAccess(localVariableInfo, AccessType.READ)
                // STACK: value
            }
        )
    }

    private fun GeneratorAdapter.registerLocalVariableAccess(variableInfo: LocalVariableInfo, accessType: AccessType) {
        invokeIfInAnalyzedCode(
            original = {},
            // load the current value of stored in the local variable and call `afterLocalWrite` with it
            instrumented = {
                // STACK: <empty>
                loadNewCodeLocationId()
                val variableId = TRACE_CONTEXT.getOrCreateVariableId(variableInfo.name)
                push(variableId)
                // VerifyError with `loadLocal(..)`, here is a workaround
                visitVarInsn(variableInfo.type.getVarInsnOpcode(), variableInfo.index)
                box(variableInfo.type)
                // STACK: codeLocation, variableId, boxedValue
                when (accessType) {
                    AccessType.READ -> {
                        invokeStatic(Injections::afterLocalRead)
                        // invokeBeforeEventIfPluginEnabled("read local")
                        // STACK: <empty>
                    }
                    AccessType.WRITE -> {
                        invokeStatic(Injections::afterLocalWrite)
                        // invokeBeforeEventIfPluginEnabled("write local")
                    }
                }
                // STACK: <empty>
            }
        )
    }

    private enum class AccessType { READ, WRITE }

    private fun getVariableInfo(varIndex: Int): LocalVariableInfo? {
        return methodInfo.locals.activeVariables.find { it.index == varIndex }
    }

    private fun isLoadOpcode(opcode: Int) =
        opcode == ILOAD || opcode == LLOAD || opcode == FLOAD || opcode == DLOAD || opcode == ALOAD

    private fun isStoreOpcode(opcode: Int) =
        opcode == ISTORE || opcode == LSTORE || opcode == FSTORE || opcode == DSTORE || opcode == ASTORE

    private fun Type.getVarInsnOpcode() =
        getOpcode(ILOAD)

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
