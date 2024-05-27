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
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import org.jetbrains.kotlinx.lincheck.canonicalClassName
import org.jetbrains.kotlinx.lincheck.transformation.*
import sun.nio.ch.lincheck.*

/**
 * [ObjectCreationTransformer] tracks creation of new objects,
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal class ObjectCreationTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) =
        adapter.run {
            if (name == "<init>" && owner == "java/lang/Object") {
                invokeIfInTestingCode(
                    original = {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    },
                    code = {
                        val objectLocal = newLocal(OBJECT_TYPE)
                        dup()
                        storeLocal(objectLocal)
                        visitMethodInsn(opcode, owner, name, desc, itf)
                        loadLocal(objectLocal)
                        invokeStatic(Injections::afterNewObjectCreation)
                    }
                )
            } else {
                visitMethodInsn(opcode, owner, name, desc, itf)
            }
        }

    override fun visitIntInsn(opcode: Int, operand: Int) = adapter.run {
        adapter.visitIntInsn(opcode, operand)
        if (opcode == NEWARRAY) {
            invokeIfInTestingCode(
                original = {},
                code = {
                    dup()
                    invokeStatic(Injections::afterNewObjectCreation)
                }
            )
        }
    }

    override fun visitTypeInsn(opcode: Int, type: String) = adapter.run {
        if (opcode == NEW) {
            invokeIfInTestingCode(
                original = {},
                code = {
                    push(type.canonicalClassName)
                    invokeStatic(Injections::beforeNewObjectCreation)
                }
            )
        }
        visitTypeInsn(opcode, type)
        if (opcode == ANEWARRAY) {
            invokeIfInTestingCode(
                original = {},
                code = {
                    dup()
                    invokeStatic(Injections::afterNewObjectCreation)
                }
            )
        }
    }

    override fun visitMultiANewArrayInsn(descriptor: String?, numDimensions: Int) = adapter.run {
        visitMultiANewArrayInsn(descriptor, numDimensions)
        invokeIfInTestingCode(
            original = {},
            code = {
                dup()
                invokeStatic(Injections::afterNewObjectCreation)
            }
        )
    }
}