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
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.AnalyzerAdapter
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import org.jetbrains.kotlinx.lincheck.transformation.*
import sun.nio.ch.lincheck.*

/**
 * [SharedMemoryAccessTransformer] tracks reads and writes to plain or volatile shared variables,
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal class SharedMemoryAccessTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : LincheckBaseMethodVisitor(fileName, className, methodName, adapter) {

    lateinit var analyzer: AnalyzerAdapter

    override fun visitFieldInsn(opcode: Int, owner: String, fieldName: String, desc: String) = adapter.run {
        if (
            isCoroutineInternalClass(owner.toCanonicalClassName()) ||
            isCoroutineStateMachineClass(owner.toCanonicalClassName()) ||
            // when initializing our own fields in constructor, we do not want to track that;
            // otherwise `VerifyError` will be thrown, see https://github.com/JetBrains/lincheck/issues/424
            (methodName == "<init>" && className == owner)
        ) {
            visitFieldInsn(opcode, owner, fieldName, desc)
            return
        }
        when (opcode) {
            GETSTATIC -> {
                // STACK: <empty>
                invokeIfInAnalyzedCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    instrumented = {
                        val fieldId = TRACE_CONTEXT.getOrCreateFieldId(
                            className = owner.toCanonicalClassName(),
                            fieldName = fieldName,
                            isStatic = true,
                            isFinal = FinalFields.isFinalField(owner, fieldName)
                        )
                        // STACK: <empty>
                        pushNull()
                        loadNewCodeLocationId()
                        push(fieldId)
                        // STACK: null, codeLocation, fieldId
                        invokeStatic(Injections::beforeReadField)
                        // STACK: <empty>
                        visitFieldInsn(opcode, owner, fieldName, desc)
                        // STACK: value
                        invokeAfterReadField(null, fieldId, getType(desc))
                        // STACK: value
                        invokeBeforeEventIfPluginEnabled("read static field")
                        // STACK: value
                    }
                )
            }

            GETFIELD -> {
                // STACK: obj
                invokeIfInAnalyzedCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    instrumented = {
                        val fieldId = TRACE_CONTEXT.getOrCreateFieldId(
                            className = owner.toCanonicalClassName(),
                            fieldName = fieldName,
                            isStatic = false,
                            isFinal = FinalFields.isFinalField(owner, fieldName)
                        )
                        // STACK: obj
                        val ownerLocal = newLocal(getType("L$owner;")).also { copyLocal(it) }
                        loadLocal(ownerLocal)
                        // STACK: obj, obj
                        loadNewCodeLocationId()
                        push(fieldId)
                        // STACK: obj, obj, codeLocation, fieldId
                        invokeStatic(Injections::beforeReadField)
                        // STACK: obj
                        visitFieldInsn(opcode, owner, fieldName, desc)
                        // STACK: obj
                        invokeAfterReadField(ownerLocal, fieldId, getType(desc))
                        // STACK: value
                        invokeBeforeEventIfPluginEnabled("read field")
                        // STACK: value
                    }
                )
            }

            PUTSTATIC -> {
                // STACK: value
                invokeIfInAnalyzedCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    instrumented = {
                        val valueType = getType(desc)
                        val fieldId = TRACE_CONTEXT.getOrCreateFieldId(
                            className = owner.toCanonicalClassName(),
                            fieldName = fieldName,
                            isStatic = true,
                            isFinal = FinalFields.isFinalField(owner, fieldName)
                        )
                        val valueLocal = newLocal(valueType) // we cannot use DUP as long/double require DUP2
                        copyLocal(valueLocal)
                        // STACK: value
                        pushNull()
                        loadLocal(valueLocal)
                        box(valueType)
                        loadNewCodeLocationId()
                        push(fieldId)
                        // STACK: value, null, value, codeLocation, fieldId
                        invokeStatic(Injections::beforeWriteField)
                        // STACK: value
                        invokeBeforeEventIfPluginEnabled("write static field")
                        // STACK: value
                        visitFieldInsn(opcode, owner, fieldName, desc)
                        // STACK: <empty>
                        invokeStatic(Injections::afterWrite)
                    }
                )
            }

            PUTFIELD -> {
                // STACK: obj, value
                invokeIfInAnalyzedCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    instrumented = {
                        val valueType = getType(desc)
                        val fieldId = TRACE_CONTEXT.getOrCreateFieldId(
                            className = owner.toCanonicalClassName(),
                            fieldName = fieldName,
                            isStatic = false,
                            isFinal = FinalFields.isFinalField(owner, fieldName)
                        )
                        val valueLocal = newLocal(valueType) // we cannot use DUP as long/double require DUP2
                        storeLocal(valueLocal)
                        // STACK: obj
                        dup()
                        // STACK: obj, obj
                        loadLocal(valueLocal)
                        box(valueType)
                        loadNewCodeLocationId()
                        push(fieldId)
                        // STACK: obj, obj, value, codeLocation, fieldId
                        invokeStatic(Injections::beforeWriteField)
                        // STACK: obj
                        invokeBeforeEventIfPluginEnabled("write field")
                        // STACK: obj
                        loadLocal(valueLocal)
                        // STACK: obj, value
                        visitFieldInsn(opcode, owner, fieldName, desc)
                        // STACK: <empty>
                        invokeStatic(Injections::afterWrite)
                    }
                )
            }

            else -> {
                // All opcodes are covered above. However, in case a new one is added, Lincheck should not fail.
                visitFieldInsn(opcode, owner, fieldName, desc)
            }
        }
    }

    override fun visitInsn(opcode: Int) = adapter.run {
        when (opcode) {
            AALOAD, LALOAD, FALOAD, DALOAD, IALOAD, BALOAD, CALOAD, SALOAD -> {
                invokeIfInAnalyzedCode(
                    original = {
                        visitInsn(opcode)
                    },
                    instrumented = {
                        // STACK: array: Array, index: Int
                        val arrayElementType = getArrayElementType(opcode)
                        val indexLocal = newLocal(INT_TYPE).also { storeLocal(it) }
                        val arrayLocal = newLocal(getType("[$arrayElementType")).also { storeLocal(it) }
                        loadLocal(arrayLocal)
                        loadLocal(indexLocal)
                        // STACK: array: Array, index: Int
                        loadNewCodeLocationId()
                        // STACK: array: Array, index: Int, codeLocation: Int
                        invokeStatic(Injections::beforeReadArray)
                        // STACK: <empty>
                        loadLocal(arrayLocal)
                        loadLocal(indexLocal)
                        // STACK: array: Array, index: Int
                        visitInsn(opcode)
                        // STACK: value
                        invokeAfterReadArray(arrayLocal, indexLocal, arrayElementType)
                        // STACK: value
                        invokeBeforeEventIfPluginEnabled("read array")
                        // STACK: value
                    }
                )
            }

            AASTORE, IASTORE, FASTORE, BASTORE, CASTORE, SASTORE, LASTORE, DASTORE -> {
                invokeIfInAnalyzedCode(
                    original = {
                        visitInsn(opcode)
                    },
                    instrumented = {
                        // STACK: array: Array, index: Int, value: Object
                        val arrayElementType = getArrayElementType(opcode)
                        val valueLocal = newLocal(arrayElementType) // we cannot use DUP as long/double require DUP2
                        storeLocal(valueLocal)
                        // STACK: array: Array, index: Int
                        dup2()
                        // STACK: array: Array, index: Int, array: Array, index: Int
                        loadLocal(valueLocal)
                        box(arrayElementType)
                        loadNewCodeLocationId()
                        // STACK: array: Array, index: Int, array: Array, index: Int, value: Object, codeLocation: Int
                        invokeStatic(Injections::beforeWriteArray)
                        invokeBeforeEventIfPluginEnabled("write array")
                        // STACK: array: Array, index: Int
                        loadLocal(valueLocal)
                        // STACK: array: Array, index: Int, value: Object
                        visitInsn(opcode)
                        // STACK: <EMPTY>
                        invokeStatic(Injections::afterWrite)
                    }
                )
            }

            else -> {
                visitInsn(opcode)
            }
        }
    }

    private fun GeneratorAdapter.invokeAfterReadField(ownerLocal: Int?, fieldId: Int, valueType: Type) {
        // STACK: value
        val resultLocal = newLocal(valueType)
        copyLocal(resultLocal)
        if (ownerLocal != null) {
            loadLocal(ownerLocal)
        } else {
            pushNull()
        }
        loadNewCodeLocationId()
        push(fieldId)
        loadLocal(resultLocal)
        box(valueType)
        // STACK: value, owner, codeLocation, fieldId, boxed value
        invokeStatic(Injections::afterReadField)
        // STACK: value
    }

    private fun GeneratorAdapter.invokeAfterReadArray(arrayLocal: Int, indexLocal: Int, valueType: Type) {
        // STACK: value
        val resultLocal = newLocal(valueType)
        copyLocal(resultLocal)
        loadLocal(arrayLocal)
        loadLocal(indexLocal)
        loadNewCodeLocationId()
        loadLocal(resultLocal)
        box(valueType)
        // STACK: value, array, index, codeLocation, boxed value
        invokeStatic(Injections::afterReadArray)
        // STACK: value
    }

    /*
     * For an array access instruction (either load or store),
     * tries to obtain the type of the read/written array element.
     *
     * If the type can be determined from the opcode of the instruction itself
     * (e.g., IALOAD/IASTORE) returns it immediately.
     *
     * Otherwise, queries the analyzer to determine the type of the array in the respective stack slot.
     * This is used in two cases:
     * - for `BALOAD` and `BASTORE` instructions, since they are used to access both boolean and byte arrays;
     * - for `AALOAD` and `AASTORE` instructions, to get the class name of the array elements.
     */
    private fun getArrayElementType(opcode: Int): Type = when (opcode) {
        // Load
        IALOAD -> INT_TYPE
        FALOAD -> FLOAT_TYPE
        CALOAD -> CHAR_TYPE
        SALOAD -> SHORT_TYPE
        LALOAD -> LONG_TYPE
        DALOAD -> DOUBLE_TYPE
        BALOAD -> getArrayAccessTypeFromStack(2) ?: BYTE_TYPE
        AALOAD -> getArrayAccessTypeFromStack(2) ?: OBJECT_TYPE
        // Store
        IASTORE -> INT_TYPE
        FASTORE -> FLOAT_TYPE
        CASTORE -> CHAR_TYPE
        SASTORE -> SHORT_TYPE
        LASTORE -> LONG_TYPE
        DASTORE -> DOUBLE_TYPE
        BASTORE -> getArrayAccessTypeFromStack(3) ?: BYTE_TYPE
        AASTORE -> getArrayAccessTypeFromStack(3) ?: OBJECT_TYPE
        else -> throw IllegalStateException("Unexpected opcode: $opcode")
    }

    /*
     * Tries to obtain the type of array elements by inspecting the type of the array itself.
     * To do this, the method queries the analyzer to get the type of accessed array
     * which should lie on the stack.
     * If the analyzer does not know the type, then return null
     * (according to the ASM docs, this can happen, for example, when the visited instruction is unreachable).
     */
    private fun getArrayAccessTypeFromStack(position: Int): Type? {
        if (analyzer.stack == null) return null
        val arrayDesc = analyzer.stack[analyzer.stack.size - position]
        check(arrayDesc is String)
        val arrayType = getType(arrayDesc)
        check(arrayType.sort == ARRAY)
        check(arrayType.dimensions > 0)
        return getType(arrayDesc.substring(1))
    }
}