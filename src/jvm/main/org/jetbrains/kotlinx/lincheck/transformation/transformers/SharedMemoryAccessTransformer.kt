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
) : ManagedStrategyWithAnalyzerClassVisitor(fileName, className, methodName, adapter) {

    override fun visitFieldInsn(opcode: Int, owner: String, fieldName: String, desc: String) = adapter.run {
        if (isCoroutineInternalClass(owner) || isCoroutineStateMachineClass(owner)) {
            visitFieldInsn(opcode, owner, fieldName, desc)
            return
        }
        when (opcode) {
            GETSTATIC -> {
                // STACK: <empty>
                invokeIfInTestingCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    code = {
                        // STACK: <empty>
                        pushNull()
                        push(owner)
                        push(fieldName)
                        loadNewCodeLocationId()
                        push(true) // isStatic
                        push(FinalFields.isFinalField(owner, fieldName)) // isFinal
                        // STACK: null, className, fieldName, codeLocation, isStatic, isFinal
                        invokeStatic(Injections::beforeReadField)
                        // STACK: isTracePointCreated
                        ifStatement(
                            condition = { /* already on stack */ },
                            ifClause = {
                                invokeBeforeEventIfPluginEnabled("read static field")
                            },
                            elseClause = {})
                        // STACK: <empty>
                        visitFieldInsn(opcode, owner, fieldName, desc)
                        // STACK: value
                        invokeAfterRead(getType(desc))
                        // STACK: value
                    }
                )
            }

            GETFIELD -> {
                // STACK: obj
                invokeIfInTestingCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    code = {
                        // STACK: obj
                        dup()
                        // STACK: obj, obj
                        push(owner)
                        push(fieldName)
                        loadNewCodeLocationId()
                        push(false) // isStatic
                        push(FinalFields.isFinalField(owner, fieldName)) // isFinal
                        // STACK: obj, obj, className, fieldName, codeLocation, isStatic, isFinal
                        invokeStatic(Injections::beforeReadField)
                        // STACK: obj, isTracePointCreated
                        ifStatement(
                            condition = { /* already on stack */ },
                            ifClause = {
                                invokeBeforeEventIfPluginEnabled("read field")
                            },
                            elseClause = {}
                        )
                        // STACK: obj
                        visitFieldInsn(opcode, owner, fieldName, desc)
                        // STACK: value
                        invokeAfterRead(getType(desc))
                        // STACK: value
                    }
                )
            }

            PUTSTATIC -> {
                // STACK: value
                invokeIfInTestingCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    code = {
                        val valueType = getType(desc)
                        val valueLocal = newLocal(valueType) // we cannot use DUP as long/double require DUP2
                        copyLocal(valueLocal)
                        // STACK: value
                        pushNull()
                        push(owner)
                        push(fieldName)
                        loadLocal(valueLocal)
                        box(valueType)
                        loadNewCodeLocationId()
                        push(true) // isStatic
                        push(FinalFields.isFinalField(owner, fieldName)) // isFinal
                        // STACK: value, null, className, fieldName, value, codeLocation, isStatic, isFinal
                        invokeStatic(Injections::beforeWriteField)
                        // STACK: isTracePointCreated
                        ifStatement(
                            condition = { /* already on stack */ },
                            ifClause = {
                                invokeBeforeEventIfPluginEnabled("write static field")
                            },
                            elseClause = {}
                        )
                        // STACK: value
                        visitFieldInsn(opcode, owner, fieldName, desc)
                        // STACK: <empty>
                        invokeStatic(Injections::afterWrite)
                    }
                )
            }

            PUTFIELD -> {
                // STACK: obj, value
                invokeIfInTestingCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    code = {
                        val valueType = getType(desc)
                        val valueLocal = newLocal(valueType) // we cannot use DUP as long/double require DUP2
                        storeLocal(valueLocal)
                        // STACK: obj
                        dup()
                        // STACK: obj, obj
                        push(owner)
                        push(fieldName)
                        loadLocal(valueLocal)
                        box(valueType)
                        loadNewCodeLocationId()
                        push(false) // isStatic
                        push(FinalFields.isFinalField(owner, fieldName)) // isFinal
                        // STACK: obj, obj, className, fieldName, value, codeLocation, isStatic, isFinal
                        invokeStatic(Injections::beforeWriteField)
                        // STACK: isTracePointCreated
                        ifStatement(
                            condition = { /* already on stack */ },
                            ifClause = {
                                invokeBeforeEventIfPluginEnabled("write field")
                            },
                            elseClause = {}
                        )
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
                invokeIfInTestingCode(
                    original = {
                        visitInsn(opcode)
                    },
                    code = {
                        // STACK: array: Array, index: Int
                        val arrayElementType = getArrayElementType(opcode)
                        dup2()
                        // STACK: array: Array, index: Int, array: Array, index: Int
                        loadNewCodeLocationId()
                        // STACK: array: Array, index: Int, array: Array, index: Int, codeLocation: Int
                        invokeStatic(Injections::beforeReadArray)
                        ifStatement(
                            condition = { /* already on stack */ },
                            ifClause = {
                                invokeBeforeEventIfPluginEnabled("read array")
                            },
                            elseClause = {}
                        )
                        // STACK: array: Array, index: Int
                        visitInsn(opcode)
                        // STACK: value
                        invokeAfterRead(arrayElementType)
                        // STACK: value
                    }
                )
            }

            AASTORE, IASTORE, FASTORE, BASTORE, CASTORE, SASTORE, LASTORE, DASTORE -> {
                invokeIfInTestingCode(
                    original = {
                        visitInsn(opcode)
                    },
                    code = {
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
                        ifStatement(
                            condition = { /* already on stack */ },
                            ifClause = {
                                invokeBeforeEventIfPluginEnabled("write array")
                            },
                            elseClause = {}
                        )
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

    private fun GeneratorAdapter.invokeAfterRead(valueType: Type) {
        // STACK: value
        val resultLocal = newLocal(valueType)
        copyLocal(resultLocal)
        loadLocal(resultLocal)
        // STACK: value, value
        box(valueType)
        invokeStatic(Injections::afterRead)
        // STACK: value
    }
}