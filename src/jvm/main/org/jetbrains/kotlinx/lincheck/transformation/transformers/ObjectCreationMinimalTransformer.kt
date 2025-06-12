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
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.commons.GeneratorAdapter

/**
 * [ObjectCreationMinimalTransformer] tracks creation of new objects,
 * injecting invocations of corresponding [EventTracker] methods.
 */
internal class ObjectCreationMinimalTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
    private val classFilter: (String, String) -> Boolean
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {
    override fun visitTypeInsn(opcode: Int, type: String) = adapter.run {
        if (opcode == NEW && classFilter(className, methodName)) {
            invokeIfInAnalyzedCode(
                original = {},
                instrumented = {
                    push(type.toCanonicalClassName())
                    invokeStatic(Injections::beforeNewObjectCreation)
                }
            )
        }
        visitTypeInsn(opcode, type)
    }
}