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
import sun.misc.Unsafe
import java.util.concurrent.locks.LockSupport

/**
 * [ParkingTransformer] tracks [Unsafe.park], [Unsafe.unpark], [LockSupport.park], [LockSupport.park] with blocker,
 * [LockSupport.parkNanos], [LockSupport.parkNanos] with blocker, and [LockSupport.unpark] method calls,
 * injecting invocations of [EventTracker.park] and [EventTracker.unpark] methods.
 */
internal class ParkingTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        when {
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
                        processLockSupportPark(withBlocker = withBlocker, withNanos = withNanos)
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
                        processLockSupportUnpark()
                    }
                )
            }

            isUnsafe(owner) && (name == "park") -> {
                invokeIfInAnalyzedCode(
                    original = {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    },
                    instrumented = {
                        processUnsafePark()
                    }
                )
            }

            isUnsafe(owner) && (name == "unpark") -> {
                invokeIfInAnalyzedCode(
                    original = {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    },
                    instrumented = {
                        processUnsafeUnpark()
                    }
                )
            }

            else -> {
                visitMethodInsn(opcode, owner, name, desc, itf)
            }
        }
    }

    private fun GeneratorAdapter.processLockSupportPark(
        withBlocker: Boolean = false,
        withNanos: Boolean = false
    ) {
        if (withNanos) pop2()   // pop nanos (long)
        if (withBlocker) pop()  // pop blocker
        loadNewCodeLocationId()
        dup()
        invokeStatic(Injections::beforePark)
        invokeBeforeEventIfPluginEnabled("park")
        invokeStatic(Injections::park)
    }

    private fun GeneratorAdapter.processLockSupportUnpark() {
        // Thread parameter is already on the stack
        loadNewCodeLocationId()
        invokeStatic(Injections::unpark)
        invokeBeforeEventIfPluginEnabled("unpark")
    }

    private fun GeneratorAdapter.processUnsafePark() {
        pop2()  // time
        pop()   // isAbsolute
        pop()   // Unsafe
        loadNewCodeLocationId()
        dup()
        invokeStatic(Injections::beforePark)
        invokeBeforeEventIfPluginEnabled("park")
        invokeStatic(Injections::park)
    }

    private fun GeneratorAdapter.processUnsafeUnpark() {
        loadNewCodeLocationId()
        invokeStatic(Injections::unpark)
        pop() // pop Unsafe object
        invokeBeforeEventIfPluginEnabled("unpark")
    }

    private fun isUnsafe(owner: String) =
        (owner == "sun/misc/Unsafe" || owner == "jdk/internal/misc/Unsafe")

    private fun isLockSupport(owner: String) =
        (owner == "java/util/concurrent/locks/LockSupport")
}
