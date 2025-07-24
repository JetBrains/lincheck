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

import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type.getType
import org.objectweb.asm.commons.GeneratorAdapter
import org.jetbrains.kotlinx.lincheck.transformation.*
import org.objectweb.asm.MethodVisitor
import sun.nio.ch.lincheck.*

/**
 * [MonitorTransformer] tracks `monitorenter` and `monitorexit` instructions,
 * injecting invocations of [EventTracker.lock] and [EventTracker.unlock] methods.
 */
internal class MonitorTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : LincheckBaseMethodVisitor(fileName, className, methodName, adapter, methodVisitor) {

    override fun visitInsn(opcode: Int) = adapter.run {
        when (opcode) {
            MONITORENTER -> {
                invokeIfInAnalyzedCode(
                    original = { super.visitInsn(opcode) },
                    instrumented = {
                        loadNewCodeLocationId()
                        invokeStatic(Injections::beforeLock)
                        invokeBeforeEventIfPluginEnabled("lock")
                        invokeStatic(Injections::lock)
                    }
                )
            }

            MONITOREXIT -> {
                invokeIfInAnalyzedCode(
                    original = { super.visitInsn(opcode) },
                    instrumented = {
                        loadNewCodeLocationId()
                        invokeStatic(Injections::unlock)
                        invokeBeforeEventIfPluginEnabled("unlock")
                    }
                )
            }

            else -> super.visitInsn(opcode)
        }
    }

}

/**
 * [SynchronizedMethodTransformer] tracks synchronized method calls,
 * injecting invocations of [EventTracker.lock] and [EventTracker.unlock] methods.
 *
 * It also replaces code:
 *
 *   ```method(...) {...}```
 *
 * with the following code:
 *
 *   ```method(...) { synchronized(this) {...} }```
 */
internal class SynchronizedMethodTransformer(
    fileName: String,
    className: String,
    methodName: String,
    private val access: Int,
    private val classVersion: Int,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor
) : LincheckBaseMethodVisitor(fileName, className, methodName, adapter, methodVisitor) {

    private val isStatic: Boolean = (access and ACC_STATIC != 0)

    private val tryLabel = Label()
    private val catchLabel = Label()

    override fun visitCode() = adapter.run {
        super.visitCode()
        invokeIfInAnalyzedCode(
            original = {},
            instrumented = {
                loadSynchronizedMethodMonitorOwner()
                monitorExit()
            }
        )
        visitLabel(tryLabel)
        invokeIfInAnalyzedCode(
            original = {},
            instrumented = {
                loadSynchronizedMethodMonitorOwner()
                loadNewCodeLocationId()
                invokeStatic(Injections::beforeLock)
                invokeBeforeEventIfPluginEnabled("lock")
                invokeStatic(Injections::lock)
            }
        )
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) = adapter.run {
        visitLabel(catchLabel)
        invokeIfInAnalyzedCode(
            original = {},
            instrumented = {
                loadSynchronizedMethodMonitorOwner()
                loadNewCodeLocationId()
                invokeStatic(Injections::unlock)
                invokeBeforeEventIfPluginEnabled("unlock")
                loadSynchronizedMethodMonitorOwner()
                monitorEnter()
            }
        )
        throwException()
        visitTryCatchBlock(tryLabel, catchLabel, catchLabel, null)
        super.visitMaxs(maxStack, maxLocals)
    }

    override fun visitInsn(opcode: Int) = adapter.run {
        when (opcode) {
            ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN -> {
                invokeIfInAnalyzedCode(
                    original = {},
                    instrumented = {
                        loadSynchronizedMethodMonitorOwner()
                        loadNewCodeLocationId()
                        invokeStatic(Injections::unlock)
                        invokeBeforeEventIfPluginEnabled("unlock")
                        loadSynchronizedMethodMonitorOwner()
                        monitorEnter()
                    }
                )
            }
        }
        super.visitInsn(opcode)
    }

    private fun loadSynchronizedMethodMonitorOwner() = adapter.run {
        if (isStatic) {
            val classType = getType("L$className;")
            if (classVersion >= V1_5) {
                visitLdcInsn(classType)
            } else {
                visitLdcInsn(classType.className)
                invokeInsideIgnoredSection {
                    invokeStatic(CLASS_TYPE, CLASS_FOR_NAME_METHOD)
                }
            }
        } else {
            loadThis()
        }
    }
}

/**
 * [WaitNotifyTransformer] tracks [Object.wait] and [Object.notify] method calls,
 * injecting invocations of [EventTracker.wait] and [EventTracker.notify] methods.
 */
internal class WaitNotifyTransformer(
    fileName: String,
    className: String,
    methodName: String,
    adapter: GeneratorAdapter,
) : LincheckBaseMethodVisitor(fileName, className, methodName, adapter) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        if (opcode == INVOKEVIRTUAL) {
            when {
                isWait0(name, desc) -> {
                    invokeIfInAnalyzedCode(
                        original = {
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        instrumented = {
                            loadNewCodeLocationId()
                            invokeStatic(Injections::beforeWait)
                            invokeBeforeEventIfPluginEnabled("wait")
                            invokeStatic(Injections::wait)
                        }
                    )
                }

                isWait1(name, desc) -> {
                    invokeIfInAnalyzedCode(
                        original = {
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        instrumented = {
                            pop2() // timeMillis
                            loadNewCodeLocationId()
                            invokeStatic(Injections::beforeWait)
                            invokeBeforeEventIfPluginEnabled("wait 1")
                            invokeStatic(Injections::waitWithTimeout)
                        }
                    )
                }

                isWait2(name, desc) -> {
                    invokeIfInAnalyzedCode(
                        original = {
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        instrumented = {
                            pop() // timeNanos
                            pop2() // timeMillis
                            loadNewCodeLocationId()
                            invokeStatic(Injections::beforeWait)
                            invokeBeforeEventIfPluginEnabled("wait 2")
                            invokeStatic(Injections::waitWithTimeout)
                        }
                    )
                }

                isNotify(name, desc) -> {
                    invokeIfInAnalyzedCode(
                        original = {
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        instrumented = {
                            loadNewCodeLocationId()
                            invokeStatic(Injections::notify)
                            invokeBeforeEventIfPluginEnabled("notify")
                        }
                    )
                }

                isNotifyAll(name, desc) -> {
                    invokeIfInAnalyzedCode(
                        original = {
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        instrumented = {
                            loadNewCodeLocationId()
                            invokeStatic(Injections::notifyAll)
                            invokeBeforeEventIfPluginEnabled("notifyAll")
                        }
                    )
                }

                else -> {
                    visitMethodInsn(opcode, owner, name, desc, itf)
                }
            }
        } else {
            visitMethodInsn(opcode, owner, name, desc, itf)
        }
    }

    private fun isWait0(mname: String, desc: String) = mname == "wait" && desc == "()V"
    private fun isWait1(mname: String, desc: String) = mname == "wait" && desc == "(J)V"
    private fun isWait2(mname: String, desc: String) = mname == "wait" && desc == "(JI)V"

    private fun isNotify(mname: String, desc: String) = mname == "notify" && desc == "()V"
    private fun isNotifyAll(mname: String, desc: String) = mname == "notifyAll" && desc == "()V"
}