/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.transformation.transformers

import org.jetbrains.kotlinx.lincheck.transformation.ManagedStrategyMethodVisitor
import org.jetbrains.kotlinx.lincheck.transformation.invokeIfInAnalyzedCode
import org.jetbrains.kotlinx.lincheck.transformation.invokeStatic
import org.objectweb.asm.commons.GeneratorAdapter
import sun.nio.ch.lincheck.Injections

/**
 * [ConstantHashCodeTransformer] tracks invocations of [Object.hashCode] and [System.identityHashCode] methods,
 * and replaces them with the [sun.nio.ch.lincheck.Injections.hashCodeDeterministic] and [sun.nio.ch.lincheck.Injections.identityHashCodeDeterministic] calls.
 *
 * This transformation aims to prevent non-determinism due to the native [Any.hashCode] implementation,
 * which typically returns memory address of the object.
 * There is no guarantee that memory addresses will be the same in different runs.
 */
internal class ConstantHashCodeTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {
    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        when {
            name == "hashCode" && desc == "()I" -> {
                invokeIfInAnalyzedCode(
                    original = { visitMethodInsn(opcode, owner, name, desc, itf) },
                    instrumented = { invokeStatic(Injections::hashCodeDeterministic) }
                )
            }

            owner == "java/lang/System" && name == "identityHashCode" && desc == "(Ljava/lang/Object;)I" -> {
                invokeIfInAnalyzedCode(
                    original = { visitMethodInsn(opcode, owner, name, desc, itf) },
                    instrumented = { invokeStatic(Injections::identityHashCodeDeterministic) }
                )
            }

            else -> {
                visitMethodInsn(opcode, owner, name, desc, itf)
            }
        }
    }
}