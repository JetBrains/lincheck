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

    /**
     * Searches for invocations of constructors `className::<init>(args)` and inserts bytecode
     * that will extract all objects from `args` which types are subtypes of the class `className`.
     * Snapshot tracker then tracks the extracted objects energetically.
     *
     * This is a hack solution to the problem of impossibility of identifying whether
     * some object on stack in the constructor is a `this` reference or not.
     * So for instructions `GETFIELD`/`PUTFIELD` in constructors handler of `visitFieldInsn(...)` method below
     * we ignore the accesses to fields which owners are the same type as the constructor owner.
     * The optimization is done to avoid 'leaking this' problem during bytecode verification.
     * But sometimes `GETFIELD`/`PUTFIELD` are invoked on fields with owner objects are the same type as a constructor owner,
     * but which are not `this`. This can happen when:
     * - a non-static object is accessed which was passed to constructor arguments
     * - a non-static object is accessed which was created locally in constructor
     *
     * We only case about the 1nd case, because for the 3nd case when dealing with
     * a locally constructed object it can't be reached from static memory, so no need to track its fields.
     * The 2nd case where such object is passed via constructor argument is handled here.
     */
    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        if (name == "<init>") {
            val matchedArguments = getArgumentTypes(desc).toList()
                .mapIndexed { index, type ->
                    /* TODO: change to type.className.isSubclassOf(owner) */
                    if (type.className == owner.canonicalClassName) index
                    else null
                }
                .filterNotNull()
                .toIntArray()

            if (matchedArguments.isEmpty()) {
                visitMethodInsn(opcode, owner, name, desc, itf)
                return
            }

            invokeIfInTestingCode(
                original = { visitMethodInsn(opcode, owner, name, desc, itf) },
                code = {
                    // STACK: args
                    val arguments = storeArguments(desc)
                    val matchedLocals = arguments.filterIndexed { index, _ -> matchedArguments.contains(index) }
                    // STACK: <empty>
                    push(matchedLocals.size)
                    // STACK: length
                    visitTypeInsn(ANEWARRAY, "java/lang/Object")
                    // STACK: array
                    matchedLocals.forEachIndexed { index, local ->
                        // STACK: array
                        visitInsn(DUP)
                        push(index)
                        loadLocal(local)
                        // STACK: array, array, index, obj
                        visitInsn(AASTORE)
                        // STACK: array
                    }
                    // STACK: array
                    invokeStatic(Injections::updateSnapshotWithEnergeticTracking)
                    // STACK: <empty>
                    loadLocals(arguments)
                    // STACK: args
                    visitMethodInsn(opcode, owner, name, desc, itf)
                }
            )
        }
        else visitMethodInsn(opcode, owner, name, desc, itf)
    }

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