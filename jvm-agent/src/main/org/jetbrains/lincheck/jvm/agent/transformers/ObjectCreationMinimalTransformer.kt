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

import sun.nio.ch.lincheck.*
import org.jetbrains.lincheck.jvm.agent.*
import org.jetbrains.lincheck.jvm.agent.LincheckClassFileTransformer.shouldTransform
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.commons.GeneratorAdapter

/**
 * [ObjectCreationMinimalTransformer] tracks creation of new objects,
 * injecting invocations of corresponding [EventTracker] methods.
 *
 *
 * *Note*:
 *
 * [MethodCallMinimalTransformer] and [ObjectCreationMinimalTransformer] transformers are part of the `jvm-agent` subproject,
 * and not the `trace-recorder` one, because they are not used directly by trace-recorder, but rather
 * by the lincheck's [LincheckClassVisitor].
 */
internal class ObjectCreationMinimalTransformer(
    fileName: String,
    className: String,
    methodName: String,
    metaInfo: MethodInformation,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : LincheckBaseMethodVisitor(fileName, className, methodName, metaInfo, adapter, methodVisitor,) {

    override fun visitTypeInsn(opcode: Int, type: String) = adapter.run {
        if (opcode == NEW && shouldTransform(type.toCanonicalClassName(), InstrumentationMode.TRACE_RECORDING)) {
            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    push(type.toCanonicalClassName())
                    invokeStatic(Injections::beforeNewObjectCreation)
                }
            )
        }
        super.visitTypeInsn(opcode, type)
    }

}