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

import org.objectweb.asm.commons.GeneratorAdapter
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.objectweb.asm.MethodVisitor
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
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : LincheckBaseMethodVisitor(fileName, className, methodName, adapter, methodVisitor) {

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
                        loadNewCodeLocationId()
                        dup()
                        invokeStatic(Injections::beforePark)
                        invokeBeforeEventIfPluginEnabled("park")
                        invokeStatic(Injections::park)
                    }
                )
            }

            isUnsafe(owner) && name == "unpark" -> {
                invokeIfInAnalyzedCode(
                    original = {
                        super.visitMethodInsn(opcode, owner, name, desc, itf)
                    },
                    instrumented = {
                        loadNewCodeLocationId()
                        invokeStatic(Injections::unpark)
                        pop() // pop Unsafe object
                        invokeBeforeEventIfPluginEnabled("unpark")
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