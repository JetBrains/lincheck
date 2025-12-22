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

import sun.nio.ch.lincheck.Injections
import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.trace.TraceContext
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.commons.GeneratorAdapter

/**
 * [ConstantHashCodeTransformer] tracks invocations of [Object.hashCode] and [System.identityHashCode] methods,
 * and replaces them with the [Injections.hashCodeDeterministic] and [Injections.identityHashCodeDeterministic] calls.
 *
 * This transformation aims to prevent non-determinism due to the native [Any.hashCode] implementation,
 * which typically returns memory address of the object.
 * There is no guarantee that memory addresses will be the same in different runs.
 */
internal class ConstantHashCodeTransformer(
    fileName: String,
    className: String,
    methodName: String,
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    context: TraceContext,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, context, adapter, methodVisitor) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        when {
            name == "hashCode" && desc == "()I" -> {
                invokeIfInAnalyzedCode(
                    original = { super.visitMethodInsn(opcode, owner, name, desc, itf) },
                    instrumented = { invokeStatic(Injections::hashCodeDeterministic) }
                )
            }

            owner == "java/lang/System" && name == "identityHashCode" && desc == "(Ljava/lang/Object;)I" -> {
                invokeIfInAnalyzedCode(
                    original = { super.visitMethodInsn(opcode, owner, name, desc, itf) },
                    instrumented = { invokeStatic(Injections::identityHashCodeDeterministic) }
                )
            }

            else -> {
                super.visitMethodInsn(opcode, owner, name, desc, itf)
            }
        }
    }
}