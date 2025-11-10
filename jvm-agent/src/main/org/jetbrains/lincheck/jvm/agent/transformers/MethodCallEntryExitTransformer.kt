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

import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.trace.TRACE_CONTEXT
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.Type.*
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import sun.nio.ch.lincheck.*

/**
 * Method-level (callee-site) instrumentation that injects callbacks in method prologue and epilogue.
 *
 * Unlike [MethodCallTransformer] which instruments call-sites,
 * this visitor instruments the body of the method being visited.
 */
internal class MethodCallEntryExitTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : MethodCallTransformerBase(fileName, className, methodName, descriptor, access, methodInfo, adapter, methodVisitor) {

    private val isStatic: Boolean = (access and ACC_STATIC) != 0

    // Compute once; used as a constant during instrumentation
    private val methodId: Int = TRACE_CONTEXT.getOrCreateMethodId(
        className.toCanonicalClassName(),
        methodName,
        Types.convertAsmMethodType(descriptor)
    )

    private val tryBlock: Label = adapter.newLabel()
    private val catchBlock: Label = adapter.newLabel()

    private var argumentsArrayLocal: Int = -1

    private val enabled: Boolean = run {
        // Do not instrument constructors and class initializers to avoid illegal receiver access before super-call
        if (methodName == "<init>" || methodName == "<clinit>") return@run false
        // Do not instrument methods from `java.lang.Thread`
        if (isThreadClass(className.toCanonicalClassName())) return@run false
        if (!shouldTrackMethodCall(className, methodName, descriptor)) return@run false
        return@run true
    }

    // We extend MethodCallTransformerBase only to reuse helper methods; disable its caller-site logic.
    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) {
        // Delegate without additional instrumentation at call-site: bypass MethodCallTransformerBase logic
        mv.visitMethodInsn(opcode, owner, name, desc, itf)
    }

    override fun processMethodCall(desc: String, opcode: Int, owner: String, name: String, itf: Boolean) {
        // Unused for callee-site transformer.
    }

    override fun visitCode() = adapter.run {
        super.visitCode()

        // Do not instrument constructors and class initializers to avoid illegal receiver access before super-call
        if (!enabled) return

        // Build the arguments array from local variables and store it for later reuse
        argumentsArrayLocal = newLocal(OBJECT_ARRAY_TYPE)
        createArgumentsArrayIntoLocal(argumentsArrayLocal)

        invokeIfInAnalyzedCode(
            original = {},
            instrumented = {
                // Inject method entry callback
                processMethodCallEnter(
                    methodId = methodId,
                    receiverLocal = null, // if (isStatic) null else 0, // use original receiver slot
                    argumentsArrayLocal = argumentsArrayLocal,
                    ownerName = null,
                    argumentNames = null,
                )
                // We don't use deterministic call descriptor in this prototype
                pop()
            }
        )

        // Wrap the rest of the method body into try/catch to intercept exceptional exit
        adapter.visitTryCatchBlock(tryBlock, catchBlock, catchBlock, null)
        adapter.visitLabel(tryBlock)
    }

    override fun visitInsn(opcode: Int) = adapter.run {
        if (!enabled) {
            super.visitInsn(opcode)
            return
        }

        when (opcode) {
            ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN -> {
                invokeIfInAnalyzedCode(
                    original = {},
                    instrumented = {
                        // On normal return, report the result (if any)
                        processMethodCallReturn(
                            returnType = Type.getReturnType(descriptor), // returnType,
                            deterministicCallIdLocal = null,
                            deterministicMethodDescriptorLocal = null,
                            methodId = methodId,
                            receiverLocal = null, // if (isStatic) null else 0,
                            argumentsArrayLocal = argumentsArrayLocal,
                        )
                    }
                )
            }
        }
        super.visitInsn(opcode)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) = adapter.run {
        if (!enabled) {
            super.visitMaxs(maxStack, maxLocals)
            return
        }

        adapter.visitLabel(catchBlock)

        invokeIfInAnalyzedCode(
            original = {},
            instrumented = {
                // Exception is on the stack here
                processMethodCallException(
                    deterministicCallIdLocal = null,
                    deterministicMethodDescriptorLocal = null,
                    methodId = methodId,
                    receiverLocal = null, // if (isStatic) null else 0,
                    argumentsArrayLocal = argumentsArrayLocal,
                )
            }
        )

        adapter.visitInsn(ATHROW)
        super.visitMaxs(maxStack, maxLocals)
    }

    /**
     * Creates an `Object[]` containing boxed copies of the method arguments from local variable slots
     * and stores it to the provided local index.
     */
    private fun GeneratorAdapter.createArgumentsArrayIntoLocal(argumentsArrayLocal: Int) {
        val argTypes = Type.getArgumentTypes(descriptor)
        val objType = OBJECT_TYPE

        // Allocate array
        push(argTypes.size)
        newArray(objType)
        storeLocal(argumentsArrayLocal)

        // var slot = if (isStatic) 0 else 1
        // for ((i, type) in argTypes.withIndex()) {
        //     loadLocal(argumentsArrayLocal)
        //     push(i)
        //     try {
        //         loadLocal(slot, type)
        //     } catch (e: IndexOutOfBoundsException) {
        //         Logger.debug { "Out of bounds access to local variable #$slot of type $type in $className.$methodName$descriptor (isStatic=$isStatic)" }
        //         throw e
        //     }
        //     box(type)
        //     arrayStore(objType)
        //     slot += type.size
        // }
    }
}
