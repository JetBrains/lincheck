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

import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.trace.TraceContext
import org.objectweb.asm.MethodVisitor
import sun.nio.ch.lincheck.*

/**
 * [SharedMemoryAccessTransformer] tracks reads and writes to plain or volatile shared variables,
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal class SharedMemoryAccessTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    context: TraceContext,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
    val configuration: TransformationConfiguration,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, context, adapter, methodVisitor) {

    override val requiresTypeAnalyzer: Boolean = true
    override val requiresOwnerNameAnalyzer: Boolean = true

    override fun visitFieldInsn(opcode: Int, owner: String, fieldName: String, desc: String) = adapter.run {
        if (
            isCoroutineInternalClass(owner.toCanonicalClassName()) ||
            isCoroutineStateMachineClass(owner.toCanonicalClassName()) ||
            // when initializing our own fields in constructor, we do not want to track that;
            // otherwise `VerifyError` will be thrown, see https://github.com/JetBrains/lincheck/issues/424
            (methodName == "<init>" && className == owner)
        ) {
            super.visitFieldInsn(opcode, owner, fieldName, desc)
            return
        }
        when (opcode) {
            GETSTATIC if configuration.trackStaticFieldReads -> {
                processStaticFieldGet(owner, fieldName, opcode, desc)
            }
            GETFIELD if configuration.trackRegularFieldReads -> {
                processInstanceFieldGet(owner, fieldName, opcode, desc)
            }
            PUTSTATIC if configuration.trackStaticFieldWrites -> {
                processStaticFieldPut(desc, owner, fieldName, opcode)
            }
            PUTFIELD if configuration.trackRegularFieldWrites -> {
                processInstanceFieldPut(desc, owner, fieldName, opcode)
            }
            else -> super.visitFieldInsn(opcode, owner, fieldName, desc)
        }
    }

    private fun GeneratorAdapter.processStaticFieldGet(owner: String, fieldName: String, opcode: Int, desc: String) {
        val fieldId = context.getOrCreateFieldId(
            className = owner.toCanonicalClassName(),
            fieldName = fieldName,
            isStatic = true,
            isFinal = FinalFields.isFinalField(owner, fieldName)
        )

        // STACK: <empty>
        invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
        val codeLocationId = loadNewCodeLocationId()
        pushNull()
        push(fieldId)
        push(desc)
        // STACK: descriptor, codeLocation, null, fieldId, desc
        invokeStatic(Injections::beforeReadField)
        // STACK: <empty>
        if(configuration.interceptReadResults) {
            invokeStatic(Injections::interceptReadResult)
        } else {
            super.visitFieldInsn(opcode, owner, fieldName, desc)
        }
        // STACK: value
        invokeAfterReadField(null, fieldId, getType(desc), codeLocationId)
        // STACK: value
        invokeBeforeEventIfPluginEnabled("read static field")
        // STACK: value
    }

    private fun GeneratorAdapter.processInstanceFieldGet(owner: String, fieldName: String, opcode: Int, desc: String) {
        val fieldId = context.getOrCreateFieldId(
            className = owner.toCanonicalClassName(),
            fieldName = fieldName,
            isStatic = false,
            isFinal = FinalFields.isFinalField(owner, fieldName)
        )
        // STACK: obj
        val ownerName = ownerNameAnalyzer?.stack?.getStackElementAt(0)
        val ownerLocal = newLocal(getType("L$owner;")).also { copyLocal(it) }

        invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
        val codeLocationId = loadNewCodeLocationId(accessPath = ownerName)
        loadLocal(ownerLocal)
        push(fieldId)
        push(desc)
        // STACK: descriptor, codeLocation, obj, fieldId, desc
        invokeStatic(Injections::beforeReadField)
        // STACK: obj
        if(configuration.interceptReadResults) {
            pop()
            invokeStatic(Injections::interceptReadResult)
            unbox(getType(desc))
        } else {
            super.visitFieldInsn(opcode, owner, fieldName, desc)
        }
        // STACK: obj
        invokeAfterReadField(ownerLocal, fieldId, getType(desc), codeLocationId)
        // STACK: value
        invokeBeforeEventIfPluginEnabled("read field")
        // STACK: value
    }

    private fun GeneratorAdapter.processStaticFieldPut(desc: String, owner: String, fieldName: String, opcode: Int) {
        val valueType = getType(desc)
        val fieldId = context.getOrCreateFieldId(
            className = owner.toCanonicalClassName(),
            fieldName = fieldName,
            isStatic = true,
            isFinal = FinalFields.isFinalField(owner, fieldName)
        )
        val valueLocal = newLocal(valueType).also { storeLocal(it) } // we cannot use DUP as long/double require DUP2


        loadLocal(valueLocal)
        // STACK: value
        invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
        loadNewCodeLocationId()
        pushNull()
        loadLocal(valueLocal)
        box(valueType)
        push(fieldId)
        push(desc)
        // STACK: value, descriptor, codeLocation, null, value, fieldId, desc
        invokeStatic(Injections::beforeWriteField)
        // STACK: value
        invokeBeforeEventIfPluginEnabled("write static field")
        // STACK: value
        super.visitFieldInsn(opcode, owner, fieldName, desc)
        // STACK: <empty>
        invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
        invokeStatic(Injections::afterWrite)
    }

    private fun GeneratorAdapter.processInstanceFieldPut(desc: String, owner: String, fieldName: String, opcode: Int) {
        // STACK: obj, value
        val valueType = getType(desc)
        val fieldId = context.getOrCreateFieldId(
            className = owner.toCanonicalClassName(),
            fieldName = fieldName,
            isStatic = false,
            isFinal = FinalFields.isFinalField(owner, fieldName)
        )
        val valueLocal = newLocal(valueType).also { storeLocal(it) } // we cannot use DUP as long/double require DUP2
        val ownerLocal = newLocal(getType("L$owner;")).also { storeLocal(it) }
        val ownerName = ownerNameAnalyzer?.stack?.getStackElementAt(valueType.size)

        invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
        loadNewCodeLocationId(accessPath = ownerName)
        loadLocal(ownerLocal)
        loadLocal(valueLocal)
        box(valueType)
        push(fieldId)
        push(desc)
        // STACK: descriptor, codeLocation, obj, value, fieldId, desc
        invokeStatic(Injections::beforeWriteField)
        // STACK: <empty>
        invokeBeforeEventIfPluginEnabled("write field")
        // STACK: <empty>
        loadLocal(ownerLocal)
        loadLocal(valueLocal)
        // STACK: obj, value
        super.visitFieldInsn(opcode, owner, fieldName, desc)
        invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
        invokeStatic(Injections::afterWrite)
    }

    override fun visitInsn(opcode: Int) = adapter.run {
        when (opcode) {
            AALOAD, LALOAD, FALOAD, DALOAD, IALOAD, BALOAD, CALOAD, SALOAD -> {
                if (configuration.trackArrayElementReads) {
                    processArrayLoad(opcode)
                } else {
                    super.visitInsn(opcode)
                }
            }

            AASTORE, IASTORE, FASTORE, BASTORE, CASTORE, SASTORE, LASTORE, DASTORE -> {
                if (configuration.trackArrayElementWrites) {
                    processArrayStore(opcode)
                } else {
                    super.visitInsn(opcode)
                }
            }

            else -> {
                super.visitInsn(opcode)
            }
        }
    }

    private fun GeneratorAdapter.processArrayLoad(opcode: Int) {
        // STACK: array, index
        val arrayElementType = getArrayElementType(opcode)
        val indexLocal = newLocal(INT_TYPE).also { storeLocal(it) }
        val arrayLocal = newLocal(getType("[$arrayElementType")).also { storeLocal(it) }
        val ownerName = ownerNameAnalyzer?.stack?.getStackElementAt(1)

        // STACK: <empty>
        invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
        val codeLocationId = loadNewCodeLocationId(accessPath = ownerName)
        loadLocal(arrayLocal)
        loadLocal(indexLocal)
        push(arrayElementType.descriptor)
        // STACK: descriptor, codeLocation, array, index, desc
        invokeStatic(Injections::beforeReadArray)
        // STACK: <empty>
        loadLocal(arrayLocal)
        loadLocal(indexLocal)
        // STACK: array, index
        if(configuration.interceptReadResults) {
            pop()
            pop()
            invokeStatic(Injections::interceptReadResult)
            unbox(arrayElementType)
        } else {
            super.visitInsn(opcode)
        }
        // STACK: value
        invokeAfterReadArray(arrayLocal, indexLocal, arrayElementType, codeLocationId)
        // STACK: value
        invokeBeforeEventIfPluginEnabled("read array")
        // STACK: value
    }

    private fun GeneratorAdapter.processArrayStore(opcode: Int) {
        // STACK: array, index, value
        val arrayElementType = getArrayElementType(opcode)
        val valueLocal = newLocal(arrayElementType) // we cannot use DUP as long/double require DUP2
        val ownerName = ownerNameAnalyzer?.stack?.getStackElementAt(1 + arrayElementType.size)
        storeLocal(valueLocal)
        val indexLocal = newLocal(INT_TYPE).also { storeLocal(it) }
        val arrayLocal = newLocal(getType("[$arrayElementType")).also { storeLocal(it) }

        // STACK: <empty>
        invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
        loadNewCodeLocationId(accessPath = ownerName)
        loadLocal(arrayLocal)
        loadLocal(indexLocal)
        loadLocal(valueLocal)
        box(arrayElementType)
        push(arrayElementType.descriptor)
        // STACK: descriptor, codeLocation, array, index, value, desc
        invokeStatic(Injections::beforeWriteArray)
        invokeBeforeEventIfPluginEnabled("write array")
        // STACK: <empty>
        loadLocal(arrayLocal)
        loadLocal(indexLocal)
        loadLocal(valueLocal)
        // STACK: array, index, value
        super.visitInsn(opcode)
        // STACK: <empty>
        invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
        invokeStatic(Injections::afterWrite)
    }

    private fun GeneratorAdapter.invokeAfterReadField(ownerLocal: Int?, fieldId: Int, valueType: Type, codeLocationId: Int) {
        // STACK: value
        val resultLocal = newLocal(valueType)
        copyLocal(resultLocal)
        invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
        push(codeLocationId)
        if (ownerLocal != null) {
            loadLocal(ownerLocal)
        } else {
            pushNull()
        }
        push(fieldId)
        loadLocal(resultLocal)
        box(valueType)
        // STACK: value, descriptor, codeLocation, owner, fieldId, boxed value
        invokeStatic(Injections::afterReadField)
        // STACK: value
    }

    private fun GeneratorAdapter.invokeAfterReadArray(arrayLocal: Int, indexLocal: Int, valueType: Type, codeLocationId: Int) {
        // STACK: value
        val resultLocal = newLocal(valueType)
        copyLocal(resultLocal)
        invokeStatic(Injections::getCurrentThreadDescriptorIfInAnalyzedCode)
        push(codeLocationId)
        loadLocal(arrayLocal)
        loadLocal(indexLocal)
        loadLocal(resultLocal)
        box(valueType)
        // STACK: value, descriptor, codeLocation, array, index, boxed value
        invokeStatic(Injections::afterReadArray)
        // STACK: value
    }

    /*
     * For an array access instruction (either load or store),
     * tries to obtain the type of the read/written array element.
     *
     * If the type can be determined from the opcode of the instruction itself (e.g., `IALOAD`/`IASTORE`)
     * then returns this type.
     *
     * Otherwise, queries the analyzer to determine the type of the array in the respective stack slot.
     * This is used in two cases:
     * - for `BALOAD` and `BASTORE` instructions, since they are used to access both boolean and byte arrays;
     * - for `AALOAD` and `AASTORE` instructions, to get the class name of the array elements.
     */
    private fun getArrayElementType(opcode: Int): Type = when (opcode) {
        BALOAD -> getArrayAccessTypeFromStack(1) ?: BYTE_TYPE
        AALOAD -> getArrayAccessTypeFromStack(1) ?: OBJECT_TYPE

        BASTORE -> getArrayAccessTypeFromStack(2) ?: BYTE_TYPE
        AASTORE -> getArrayAccessTypeFromStack(2) ?: OBJECT_TYPE

        else -> getArrayAccessOpcodeType(opcode)
    }

    /*
     * Tries to obtain the type of array elements by inspecting the type of the array itself.
     * To do this, the method queries the analyzer to get the type of accessed array
     * which should lie on the stack.
     * If the analyzer does not know the type, then return null
     * (according to the ASM docs, this can happen, for example, when the visited instruction is unreachable).
     */
    private fun getArrayAccessTypeFromStack(position: Int): Type? {
        val stack = typeAnalyzer?.stack ?: return null
        val arrayDesc = stack.getStackElementAt(position)
        check(arrayDesc is String)
        val arrayType = getType(arrayDesc)
        check(arrayType.sort == ARRAY)
        check(arrayType.dimensions > 0)
        return getType(arrayDesc.substring(1))
    }
}