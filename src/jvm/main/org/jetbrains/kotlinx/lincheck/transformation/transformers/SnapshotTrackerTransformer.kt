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

import org.jetbrains.kotlinx.lincheck.canonicalClassName
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.GeneratorAdapter
import sun.nio.ch.lincheck.Injections

internal class SnapshotTrackerTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ManagedStrategyWithAnalyzerClassVisitor(fileName, className, methodName, adapter) {

    override fun visitFieldInsn(opcode: Int, owner: String, fieldName: String, desc: String) = adapter.run {
        if (
            isCoroutineInternalClass(owner) ||
            isCoroutineStateMachineClass(owner) ||
            // when initializing our own fields in constructor, we do not want to track that as snapshot modification
            (methodName == "<init>" && className == owner)
        ) {
            visitFieldInsn(opcode, owner, fieldName, desc)
            return
        }

        when (opcode) {
            GETSTATIC, PUTSTATIC -> {
                // STACK: [<empty> | value]
                invokeIfInTestingCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    code = {
                        // STACK: [<empty> | value]
                        pushNull()
                        push(owner.canonicalClassName)
                        push(fieldName)
                        loadNewCodeLocationId()
                        // STACK: [<empty> | value], null, className, fieldName, codeLocation
                        invokeStatic(Injections::updateSnapshotOnFieldAccess)
                        // STACK: [<empty> | value]
                        visitFieldInsn(opcode, owner, fieldName, desc)
                        // STACK: [<empty> | value]
                    }
                )
            }

            GETFIELD, PUTFIELD -> {
                // STACK: obj, [value]
                invokeIfInTestingCode(
                    original = {
                        visitFieldInsn(opcode, owner, fieldName, desc)
                    },
                    code = {
                        val valueType = getType(desc)
                        val valueLocal = newLocal(valueType) // we cannot use DUP as long/double require DUP2

                        // STACK: obj, [value]
                        if (opcode == PUTFIELD) storeLocal(valueLocal)
                        // STACK: obj
                        dup()
                        // STACK: obj, obj
                        push(owner.canonicalClassName)
                        push(fieldName)
                        loadNewCodeLocationId()
                        // STACK: obj, obj, className, fieldName, codeLocation
                        invokeStatic(Injections::updateSnapshotOnFieldAccess)
                        // STACK: obj
                        if (opcode == PUTFIELD) loadLocal(valueLocal)
                        // STACK: obj, [value]
                        visitFieldInsn(opcode, owner, fieldName, desc)
                        // STACK: [<empty> | value]
                    }
                )
            }

            else -> {
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
                        // STACK: array, index
                        dup2()
                        // STACK: array, index, array, index
                        loadNewCodeLocationId()
                        // STACK: array, index, array, index, codeLocation
                        invokeStatic(Injections::updateSnapshotOnArrayElementAccess)
                        // STACK: array, index
                        visitInsn(opcode)
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
                        val arrayElementType = getArrayElementType(opcode)
                        val valueLocal = newLocal(arrayElementType) // we cannot use DUP as long/double require DUP2

                        // STACK: array, index, value
                        storeLocal(valueLocal)
                        // STACK: array, index
                        dup2()
                        // STACK: array, index, array, index
                        loadNewCodeLocationId()
                        // STACK: array, index, array, index, codeLocation
                        invokeStatic(Injections::updateSnapshotOnArrayElementAccess)
                        // STACK: array, index
                        loadLocal(valueLocal)
                        // STACK: array, index, value
                        visitInsn(opcode)
                        // STACK: <empty>
                    }
                )
            }

            else -> {
                visitInsn(opcode)
            }
        }
    }
}