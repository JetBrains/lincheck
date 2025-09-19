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

import org.jetbrains.lincheck.trace.TRACE_CONTEXT
import org.jetbrains.lincheck.descriptors.Types.convertAsmMethodType
import org.jetbrains.lincheck.jvm.agent.*
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import sun.nio.ch.lincheck.Injections

internal class LocalVariablesAccessTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, adapter, methodVisitor) {

    private val isStatic: Boolean = (access and ACC_STATIC != 0)

    private val numberOfLocals = convertAsmMethodType(descriptor).argumentTypes.size + if (isStatic) 0 else 1

    override fun visitVarInsn(opcode: Int, varIndex: Int) = adapter.run {
        val localVariableInfo = getVariableName(varIndex)?.takeIf { it.name != "this" }
        if (localVariableInfo == null) {
            super.visitVarInsn(opcode, varIndex)
            return
        }
        when (opcode) {
            Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD -> {
                visitReadVarInsn(localVariableInfo, opcode, varIndex)
            }
            Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE -> {
                visitWriteVarInsn(localVariableInfo, opcode, varIndex)
            }
            else -> {
                super.visitVarInsn(opcode, varIndex)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun visitWriteVarInsn(localVariableInfo: LocalVariableInfo, opcode: Int, varIndex: Int) = adapter.run {
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

    private fun visitReadVarInsn(localVariableInfo: LocalVariableInfo, opcode: Int, varIndex: Int) = adapter.run {
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

    private fun getVariableName(varIndex: Int): LocalVariableInfo? {
        // First, we try to search among active variables.
        methodInfo.locals.activeVariables.find { it.index == varIndex }?.let { return it }

        // There could be cases where a variable live range given in local variable table entry
        // does not match intuitively expected one.
        // For instance, for the following code:
        //    L0: var sum = 0
        //    L1: for (i in 1..10) {
        //        sum += i
        //    }
        //    L2: return sum
        //
        // one would expect live range of `sum` variable to be [L0, L2], but in fact it is [L1, L2]:
        //     LOCALVARIABLE sum I L1 L2 0
        //
        // So for this reason, at the point of initialization write `var sum = 0`
        // the `sum` variable will not be active according to debug information.
        // To mitigate this problem, we apply some heuristics.

        // If only a single variable occupies a given stack slot, just return it.
        val variables = methodInfo.locals.getVariablesByStackSlot(varIndex)
        if (variables.size == 0) return null
        if (variables.size == 1) return variables.first()

        // Otherwise, we check if we are in the initialization point,
        // and if so, return the variable with minimal starting label position.
        // To check if we are in the initialization point,
        // we check if we have not yet visited any starting label of all variables' live ranges.
        val isAtInitialization = variables.all { !methodInfo.labels.isLabelSeen(it.startLabel) }
        if (isAtInitialization) {
            return variables.minWith { v, u ->
                methodInfo.labels.compare(v.startLabel, u.startLabel)
            }
        }

        return null
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
