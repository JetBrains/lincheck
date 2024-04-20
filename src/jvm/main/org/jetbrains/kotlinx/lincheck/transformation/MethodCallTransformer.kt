/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation

import org.jetbrains.kotlinx.lincheck.transformation.CoroutineInternalCallTracker.isCoroutineInternalClass
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.Type.VOID_TYPE
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import sun.nio.ch.lincheck.*

/**
 * [MethodCallTransformer] tracks method calls,
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal class MethodCallTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        // TODO: do not ignore <init>
        if (isCoroutineInternalClass(owner)) {
            invokeInIgnoredSection {
                visitMethodInsn(opcode, owner, name, desc, itf)
            }
            return
        }
        if (
            // TODO: extract into method
            owner == "sun/misc/Unsafe" ||
            owner == "jdk/internal/misc/Unsafe" ||
            owner == "java/lang/invoke/VarHandle" ||
            owner.startsWith("java/util/concurrent/") && (owner.contains("Atomic")) ||
            owner.startsWith("kotlinx/atomicfu/") && (owner.contains("Atomic"))
        ) {
            invokeIfInTestingCode(
                original = {
                    visitMethodInsn(opcode, owner, name, desc, itf)
                },
                code = {
                    processAtomicMethodCall(desc, opcode, owner, name, itf)
                }
            )
            return
        }
        if (
            // TODO: extract into method
            name == "<init>" ||
            owner.startsWith("sun/nio/ch/lincheck/") ||
            owner.startsWith("org/jetbrains/kotlinx/lincheck/") ||
            owner == "kotlin/jvm/internal/Intrinsics" ||
            owner == "java/util/Objects" ||
            owner == "java/lang/String" ||
            owner == "java/lang/Boolean" ||
            owner == "java/lang/Long" ||
            owner == "java/lang/Integer" ||
            owner == "java/lang/Short" ||
            owner == "java/lang/Byte" ||
            owner == "java/lang/Double" ||
            owner == "java/lang/Float" ||
            owner == "java/util/Locale" ||
            owner == "org/slf4j/helpers/Util" ||
            owner == "java/util/Properties"
        ) {
            visitMethodInsn(opcode, owner, name, desc, itf)
            return
        }
        invokeIfInTestingCode(
            original = {
                visitMethodInsn(opcode, owner, name, desc, itf)
            },
            code = {
                // TODO: extract into `processMethodCall` method
                val endLabel = newLabel()
                val methodCallStartLabel = newLabel()
                // STACK [INVOKEVIRTUAL]: owner, arguments
                // STACK [INVOKESTATIC]: arguments
                val argumentLocals = storeArguments(desc)
                // STACK [INVOKEVIRTUAL]: owner
                // STACK [INVOKESTATIC]: <empty>
                when (opcode) {
                    INVOKESTATIC -> visitInsn(ACONST_NULL)
                    else -> dup()
                }
                push(owner)
                push(name)
                loadNewCodeLocationId()
                // STACK [INVOKEVIRTUAL]: owner, owner, className, methodName, codeLocation
                // STACK [INVOKESTATIC]:         null, className, methodName, codeLocation
                val argumentTypes = Type.getArgumentTypes(desc)
                push(argumentLocals.size) // size of the array
                visitTypeInsn(ANEWARRAY, OBJECT_TYPE.internalName)
                // STACK: ..., array
                for (i in argumentLocals.indices) {
                    // STACK: ..., array
                    dup()
                    // STACK: ..., array, array
                    push(i)
                    // STACK: ..., array, array, index
                    loadLocal(argumentLocals[i])
                    // STACK: ..., array, array, index, argument[index]
                    box(argumentTypes[i])
                    arrayStore(OBJECT_TYPE)
                    // STACK: ..., array
                }
                // STACK: ..., array
                invokeStatic(Injections::beforeMethodCall)
                invokeBeforeEventIfPluginEnabled("method call $methodName", setMethodEventId = true)
                // STACK [INVOKEVIRTUAL]: owner, arguments
                // STACK [INVOKESTATIC]: arguments
                val methodCallEndLabel = newLabel()
                val handlerExceptionStartLabel = newLabel()
                val handlerExceptionEndLabel = newLabel()
                visitTryCatchBlock(methodCallStartLabel, methodCallEndLabel, handlerExceptionStartLabel, null)
                visitLabel(methodCallStartLabel)
                loadLocals(argumentLocals)
                visitMethodInsn(opcode, owner, name, desc, itf)
                visitLabel(methodCallEndLabel)
                // STACK [INVOKEVIRTUAL]: owner, arguments
                // STACK [INVOKESTATIC]: arguments
                processMethodCallResult(desc)
                // STACK: value
                goTo(handlerExceptionEndLabel)
                visitLabel(handlerExceptionStartLabel)
                dup()
                invokeStatic(Injections::onMethodCallException)
                throwException()
                visitLabel(handlerExceptionEndLabel)
                // STACK: value
                visitLabel(endLabel)
            }
        )
    }

    private fun GeneratorAdapter.processAtomicMethodCall(
        desc: String,
        opcode: Int,
        owner: String,
        name: String,
        itf: Boolean,
    ) {
        // In cases of Atomic*FieldUpdater, Unsafe and VarHandle we edit
        // the params list before creating a trace point to remove redundant parameters
        // as receiver and offset.
        // To determine how we should process it, we provide owner instance.
        val provideOwner = opcode != INVOKESTATIC &&
                (owner.endsWith("FieldUpdater") ||
                        owner == "sun/misc/Unsafe" || owner == "jdk/internal/misc/Unsafe" ||
                        owner == "java/lang/invoke/VarHandle")
        // STACK [INVOKEVIRTUAL]: owner, arguments
        // STACK [INVOKESTATIC]: arguments
        val argumentLocals = storeArguments(desc)
        // STACK [INVOKEVIRTUAL]: owner
        // STACK [INVOKESTATIC]: <empty>
        if (provideOwner) {
            dup()
        } else {
            visitInsn(ACONST_NULL)
        }
        // STACK [INVOKEVIRTUAL atomic updater]: owner, owner
        // STACK [INVOKESTATIC atomic updater]: <empty>, null

        // STACK [INVOKEVIRTUAL atomic]: owner, ownerName
        // STACK [INVOKESTATIC atomic]: <empty>, ownerName
        push(name)
        loadNewCodeLocationId()
        // STACK [INVOKEVIRTUAL atomic updater]: owner, owner, methodName, codeLocation
        // STACK [INVOKESTATIC atomic updater]:         null, methodName, codeLocation

        // STACK [INVOKEVIRTUAL atomic]: owner, ownerName, methodName, codeLocation
        // STACK [INVOKESTATIC atomic]:         ownerName, methodName, codeLocation
        val argumentTypes = Type.getArgumentTypes(desc)

        push(argumentLocals.size) // size of the array
        visitTypeInsn(ANEWARRAY, OBJECT_TYPE.internalName)
        // STACK: ..., array
        for (i in argumentLocals.indices) {
            // STACK: ..., array
            dup()
            // STACK: ..., array, array
            push(i)
            // STACK: ..., array, array, index
            loadLocal(argumentLocals[i])
            // STACK: ..., array, array, index, argument[index]
            box(argumentTypes[i])
            arrayStore(OBJECT_TYPE)
            // STACK: ..., array
        }
        // STACK: ..., array
        invokeStatic(Injections::beforeAtomicMethodCall)
        invokeBeforeEventIfPluginEnabled("atomic method call $methodName")

        // STACK [INVOKEVIRTUAL]: owner, arguments
        // STACK [INVOKESTATIC]: arguments
        loadLocals(argumentLocals)
        invokeInIgnoredSection {
            visitMethodInsn(opcode, owner, name, desc, itf)
        }
        // STACK [INVOKEVIRTUAL]: owner, arguments
        // STACK [INVOKESTATIC]: arguments
        processMethodCallResult(desc)
        // STACK: value
    }

    private fun processMethodCallResult(desc: String) = adapter.run {
        // STACK [INVOKEVIRTUAL]: owner, arguments
        // STACK [INVOKESTATIC]: arguments
        val resultType = Type.getReturnType(desc)
        if (resultType == VOID_TYPE) {
            invokeStatic(Injections::onMethodCallReturnVoid)
        } else {
            val resultLocal = newLocal(resultType)
            storeLocal(resultLocal)
            loadLocal(resultLocal)
            box(resultType)
            invokeStatic(Injections::onMethodCallReturn)
            loadLocal(resultLocal)
        }
    }
}