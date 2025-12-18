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

import org.objectweb.asm.commons.GeneratorAdapter
import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.trace.TraceContext
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import sun.nio.ch.lincheck.*
import sun.misc.Unsafe

/**
 * [ParkingTransformer] tracks [Unsafe.park] and [Unsafe.unpark] method calls,
 * injecting invocations of [EventTracker.park] and [EventTracker.unpark] methods.
 */
internal class ParkingTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    context: TraceContext,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, context, adapter, methodVisitor) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        when {
            isUnsafe(owner) && name == "park" -> {
                invokeIfInAnalyzedCode(
                    original = {
                        super.visitMethodInsn(opcode, owner, name, desc, itf)
                    },
                    instrumented = {
                        pop2() // time
                        pop() // isAbsolute
                        pop() // Unsafe

                        // Stack: <empty>
                        invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                        // Stack: descriptor
                        dup()
                        // Stack: descriptor, descriptor
                        val codeLocationId = loadNewCodeLocationId()
                        // Stack: descriptor, descriptor, codeLocation
                        invokeStatic(Injections::beforePark)
                        // Stack: descriptor
                        invokeBeforeEventIfPluginEnabled("park")
                        // Stack: descriptor
                        push(codeLocationId)
                        // Stack: descriptor, codeLocation
                        invokeStatic(Injections::park)
                        // Stack: <empty>
                    }
                )
            }

            isUnsafe(owner) && name == "unpark" -> {
                invokeIfInAnalyzedCode(
                    original = {
                        super.visitMethodInsn(opcode, owner, name, desc, itf)
                    },
                    instrumented = {
                        // STACK: unsafe, thread
                        val threadLocal = newLocal(OBJECT_TYPE).also { storeLocal(it) }
                        // STACK: unsafe
                        pop() // drop Unsafe object
                        // STACK: <empty>
                        invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                        loadNewCodeLocationId()
                        loadLocal(threadLocal)
                        // STACK: descriptor, codeLocation, thread
                        invokeStatic(Injections::unpark)
                        // STACK: <empty>
                        invokeBeforeEventIfPluginEnabled("unpark")
                        // STACK: <empty>
                    }
                )
            }

            else -> {
                super.visitMethodInsn(opcode, owner, name, desc, itf)
            }
        }
    }

    private fun isUnsafe(owner: String) =
        (owner == "sun/misc/Unsafe" || owner == "jdk/internal/misc/Unsafe")
}