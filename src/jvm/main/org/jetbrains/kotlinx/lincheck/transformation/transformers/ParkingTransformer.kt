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
import sun.nio.ch.lincheck.*
import java.util.concurrent.locks.LockSupport
import sun.misc.Unsafe

/**
 * The ParkingTransformer class is responsible for inserting bytecode instrumentation
 * for methods related to thread parking and unparking APIs.
 *
 * It handles the instrumentation of both the [LockSupport] and [Unsafe] classes,
 * which are commonly used for thread parking.
 */
internal class ParkingTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        when {

            /* Instrument `LockSupport` parking API */

            isLockSupport(owner) && (name == "park" || name == "parkNanos") -> {
                invokeIfInAnalyzedCode(
                    original = {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    },
                    instrumented = {
                        val withNanos = (name == "parkNanos")
                        val withBlocker = if (withNanos) {
                            desc == "(Ljava/lang/Object;J)V"
                        } else {
                            desc == "(Ljava/lang/Object;)V"
                        }
                        processPark(withBlocker = withBlocker, withNanos = withNanos)
                    }
                )
            }

            isLockSupport(owner) && (name == "parkUntil") -> {
                invokeIfInAnalyzedCode(
                    original = {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    },
                    instrumented = {
                        val withBlocker = desc == "(Ljava/lang/Object;J)V"
                        processPark(withBlocker = withBlocker, withNanos = true, withIsAbsolute = true)
                    }
                )
            }

            isLockSupport(owner) && (name == "unpark") -> {
                check(desc == "(Ljava/lang/Thread;)V")
                invokeIfInAnalyzedCode(
                    original = {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    },
                    instrumented = {
                        processUnpark()
                    }
                )
            }

            /* Instrument `Unsafe` parking API. */

            // Note that calls to `Unsafe` parking API from teh `LockSupport` class are not instrumented,
            // because the calls to `LockSupport` itself are instrumented.

            isUnsafe(owner) && (name == "park") && !isLockSupport(className) -> {
                invokeIfInAnalyzedCode(
                    original = {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    },
                    instrumented = {
                        processPark(isUnsafe = true, withIsAbsolute = true, withNanos = true)
                    }
                )
            }

            isUnsafe(owner) && (name == "unpark") && !isLockSupport(className) -> {
                invokeIfInAnalyzedCode(
                    original = {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    },
                    instrumented = {
                        processUnpark(isUnsafe = true)
                    }
                )
            }

            /* Do not instrument other methods */

            else -> {
                visitMethodInsn(opcode, owner, name, desc, itf)
            }
        }
    }

    private fun GeneratorAdapter.processPark(
        isUnsafe: Boolean = false,
        withBlocker: Boolean = false,
        withNanos: Boolean = false,
        withIsAbsolute: Boolean = false
    ) {
        if (withNanos)                  pop2()  // nanos: long
        if (isUnsafe && withIsAbsolute) pop()   // isAbsolute: boolean
        if (withBlocker)                pop()   // blocker: Object
        if (isUnsafe)                   pop()   // this: Unsafe
        loadNewCodeLocationId()
        dup()
        invokeStatic(Injections::beforePark)
        invokeBeforeEventIfPluginEnabled("park")
        invokeStatic(Injections::park)
    }

    private fun GeneratorAdapter.processUnpark(
        isUnsafe: Boolean = false
    ) {
        if (isUnsafe) {
            pop() // this: Unsafe
        }
        // Thread parameter is already on the stack
        loadNewCodeLocationId()
        invokeStatic(Injections::unpark)
        invokeBeforeEventIfPluginEnabled("unpark")
    }

    private fun isUnsafe(owner: String) =
        (owner == "sun/misc/Unsafe" || owner == "jdk/internal/misc/Unsafe")

    private fun isLockSupport(owner: String) =
        (owner == "java/util/concurrent/locks/LockSupport")
}
