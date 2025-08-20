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

import sun.nio.ch.lincheck.*
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE

/**
 * [ObjectCreationTransformer] tracks creation of new objects,
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal class ObjectCreationTransformer(
    fileName: String,
    className: String,
    methodName: String,
    metaInfo: MethodMetaInfo,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : LincheckBaseMethodVisitor(fileName, className, methodName, metaInfo, adapter, methodVisitor) {

    /* To track object creation, this transformer inserts `Injections::afterNewObjectCreation` calls
     * after an object is allocated and initialized.
     * The created object is passed into the injected function as an argument.
     *
     * In order to achieve this, this transformer tracks the following instructions:
     * `NEW`, `NEWARRAY`, `ANEWARRAY`, and `MULTIANEWARRAY`;
     *
     * It is possible to inject the injection call right after array objects creation
     * (i.e., after all instructions listed above except `NEW`),
     * since the array is in initialized state right after its allocation.
     * However, when an object is allocated via `NEW` it is first in uninitialized state,
     * until its constructor (i.e., `<init>` method) is called.
     * Trying to pass the object in uninitialized into the injected function would result
     * in a bytecode verification error.
     * Thus, we postpone the injection up after the constructor call (i.e., `<init>`).
     *
     * Another difficulty is that because of the inheritance, there could exist several
     * constructor calls (i.e., `<init>`) for the same object.
     * We need to distinguish between the base class constructor call inside the derived class constructor,
     * and the actual initializing constructor call from the object creation call size.
     *
     * Therefore, to tackle these issues, we maintain a counter of allocated, but not yet initialized objects.
     * Whenever we encounter a constructor call (i.e., `<init>`) we check for the counter
     * and inject the object creation tracking method if the counter is not null.
     *
     * The solution with allocated objects counter is inspired by:
     * https://github.com/google/allocation-instrumenter
     *
     * TODO: keeping just a counter might be not reliable in some cases,
     *   perhaps we need more robust solution, checking for particular bytecode instructions sequence, e.g.:
     *   `NEW; DUP; INVOKESPECIAL <init>`
     */
    private var uninitializedObjects = 0

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        // special handling for a common case of `Object` constructor
        if (name == "<init>" && owner == "java/lang/Object" && uninitializedObjects > 0) {
            invokeIfInAnalyzedCode(
                original = {
                    super.visitMethodInsn(opcode, owner, name, desc, itf)
                },
                instrumented = {
                    val objectLocal = newLocal(OBJECT_TYPE)
                    copyLocal(objectLocal)
                    super.visitMethodInsn(opcode, owner, name, desc, itf)
                    loadLocal(objectLocal)
                    invokeStatic(Injections::afterNewObjectCreation)
                }
            )
            uninitializedObjects--
            return
        }
        if (name == "<init>" && uninitializedObjects > 0) {
            invokeIfInAnalyzedCode(
                original = {
                    super.visitMethodInsn(opcode, owner, name, desc, itf)
                },
                instrumented = {
                    val objectLocal = newLocal(OBJECT_TYPE)
                    // save and pop the constructor parameters from the stack
                    val constructorType = Type.getType(desc)
                    val params = storeLocals(constructorType.argumentTypes)
                    // copy the object on which we call the constructor
                    copyLocal(objectLocal)
                    // push constructor parameters back on the stack
                    params.forEach { loadLocal(it) }
                    // call the constructor
                    super.visitMethodInsn(opcode, owner, name, desc, itf)
                    // call the injected method
                    loadLocal(objectLocal)
                    invokeStatic(Injections::afterNewObjectCreation)
                }
            )
            uninitializedObjects--
            return
        }
        super.visitMethodInsn(opcode, owner, name, desc, itf)
    }

    override fun visitIntInsn(opcode: Int, operand: Int) = adapter.run {
        super.visitIntInsn(opcode, operand)
        if (opcode == NEWARRAY) {
            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    dup()
                    invokeStatic(Injections::afterNewObjectCreation)
                }
            )
        }
    }

    override fun visitTypeInsn(opcode: Int, type: String) = adapter.run {
        if (opcode == NEW) {
            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    push(type.toCanonicalClassName())
                    invokeStatic(Injections::beforeNewObjectCreation)
                }
            )
            uninitializedObjects++
        }
        super.visitTypeInsn(opcode, type)
        if (opcode == ANEWARRAY) {
            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    dup()
                    invokeStatic(Injections::afterNewObjectCreation)
                }
            )
        }
    }

    override fun visitMultiANewArrayInsn(descriptor: String?, numDimensions: Int) = adapter.run {
        super.visitMultiANewArrayInsn(descriptor, numDimensions)
        invokeIfInAnalyzedCode(
            original = {},
            instrumented = {
                dup()
                invokeStatic(Injections::afterNewObjectCreation)
            }
        )
    }
}