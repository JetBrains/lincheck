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

import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type.getType
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import org.jetbrains.lincheck.jvm.agent.*
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
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, adapter, methodVisitor) {

    override fun visitInsn(opcode: Int) = adapter.run {
        when (opcode) {
            MONITORENTER -> {
                invokeIfInAnalyzedCode(
                    original = { super.visitInsn(opcode) },
                    instrumented = {
                        // STACK: monitor
                        val monitorLocal = newLocal(OBJECT_TYPE).also { storeLocal(it) }
                        // STACK: <empty>
                        invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                        // STACK: descriptor
                        loadNewCodeLocationId()
                        // STACK: descriptor, codeLocation
                        invokeStatic(Injections::beforeLock)
                        // STACK: <empty>
                        invokeBeforeEventIfPluginEnabled("lock")
                        // STACK: <empty>
                        invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                        loadLocal(monitorLocal)
                        // STACK: descriptor, monitor
                        invokeStatic(Injections::lock)
                        // STACK: <empty>
                    }
                )
            }

            MONITOREXIT -> {
                invokeIfInAnalyzedCode(
                    original = { super.visitInsn(opcode) },
                    instrumented = {
                        // STACK: monitor
                        val monitorLocal = newLocal(OBJECT_TYPE).also { storeLocal(it) }
                        // STACK: <empty>
                        invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                        // STACK: <empty>
                        loadLocal(monitorLocal)
                        loadNewCodeLocationId()
                        // STACK: descriptor, monitor, codeLocation
                        invokeStatic(Injections::unlock)
                        // STACK: <empty>
                        invokeBeforeEventIfPluginEnabled("unlock")
                        // STACK: <empty>
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
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    private val classVersion: Int,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, adapter, methodVisitor) {

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
                // STACK: <empty>
                loadSynchronizedMethodMonitorOwner()
                // STACK: monitor
                val monitorLocal = newLocal(OBJECT_TYPE).also { storeLocal(it) }
                // STACK: <empty>
                invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                loadNewCodeLocationId()
                // STACK: descriptor, codeLocation
                invokeStatic(Injections::beforeLock)
                // STACK: <empty>
                invokeBeforeEventIfPluginEnabled("lock")
                // STACK: <empty>
                invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                loadLocal(monitorLocal)
                // STACK: descriptor, monitor
                invokeStatic(Injections::lock)
                // STACK: <empty>
            }
        )
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) = adapter.run {
        visitLabel(catchLabel)
        invokeIfInAnalyzedCode(
            original = {},
            instrumented = {
                // STACK: <empty>
                loadSynchronizedMethodMonitorOwner()
                // STACK: monitor
                val monitorLocal = newLocal(OBJECT_TYPE).also { storeLocal(it) }
                // STACK: <empty>
                invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                loadLocal(monitorLocal)
                loadNewCodeLocationId()
                // STACK: descriptor, monitor, codeLocation
                invokeStatic(Injections::unlock)
                // STACK: <empty>
                invokeBeforeEventIfPluginEnabled("unlock")
                // STACK: <empty>
                loadLocal(monitorLocal)
                // STACK: monitor
                monitorEnter()
                // STACK: <empty>
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
                        // STACK: <empty>
                        loadSynchronizedMethodMonitorOwner()
                        // STACK: monitor
                        val monitorLocal = newLocal(OBJECT_TYPE).also { storeLocal(it) }
                        // STACK: <empty>
                        invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                        loadLocal(monitorLocal)
                        loadNewCodeLocationId()
                        // STACK: descriptor, monitor, codeLocation
                        invokeStatic(Injections::unlock)
                        // STACK: <empty>
                        invokeBeforeEventIfPluginEnabled("unlock")
                        // STACK: <empty>
                        loadLocal(monitorLocal)
                        // STACK: monitor
                        monitorEnter()
                        // STACK: <empty>
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
    descriptor: String,
    access: Int,
    methodInfo: MethodInformation,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, adapter, methodVisitor) {

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        if (opcode == INVOKEVIRTUAL) {
            when {
                isWait0(name, desc) -> {
                    invokeIfInAnalyzedCode(
                        original = {
                            super.visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        instrumented = {
                            val monitorLocal = newLocal(OBJECT_TYPE).also { storeLocal(it) }
                            invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                            loadNewCodeLocationId()
                            invokeStatic(Injections::beforeWait)
                            invokeBeforeEventIfPluginEnabled("wait")
                            invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                            loadLocal(monitorLocal)
                            invokeStatic(Injections::wait)
                        }
                    )
                }

                isWait1(name, desc) -> {
                    invokeIfInAnalyzedCode(
                        original = {
                            super.visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        instrumented = {
                            pop2() // timeMillis
                            val monitorLocal = newLocal(OBJECT_TYPE).also { storeLocal(it) }
                            invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                            loadNewCodeLocationId()
                            invokeStatic(Injections::beforeWait)
                            invokeBeforeEventIfPluginEnabled("wait 1")
                            invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                            loadLocal(monitorLocal)
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
                            val monitorLocal = newLocal(OBJECT_TYPE).also { storeLocal(it) }
                            invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                            loadNewCodeLocationId()
                            invokeStatic(Injections::beforeWait)
                            invokeBeforeEventIfPluginEnabled("wait 2")
                            invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                            loadLocal(monitorLocal)
                            invokeStatic(Injections::waitWithTimeout)
                        }
                    )
                }

                isNotify(name, desc) -> {
                    invokeIfInAnalyzedCode(
                        original = {
                            super.visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        instrumented = {
                            val monitorLocal = newLocal(OBJECT_TYPE).also { storeLocal(it) }
                            invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                            loadLocal(monitorLocal)
                            loadNewCodeLocationId()
                            invokeStatic(Injections::notify)
                            invokeBeforeEventIfPluginEnabled("notify")
                        }
                    )
                }

                isNotifyAll(name, desc) -> {
                    invokeIfInAnalyzedCode(
                        original = {
                            super.visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        instrumented = {
                            val monitorLocal = newLocal(OBJECT_TYPE).also { storeLocal(it) }
                            invokeStatic(ThreadDescriptor::getCurrentThreadDescriptor)
                            loadLocal(monitorLocal)
                            loadNewCodeLocationId()
                            invokeStatic(Injections::notifyAll)
                            invokeBeforeEventIfPluginEnabled("notifyAll")
                        }
                    )
                }

                else -> {
                    super.visitMethodInsn(opcode, owner, name, desc, itf)
                }
            }
        } else {
            super.visitMethodInsn(opcode, owner, name, desc, itf)
        }
    }

    private fun isWait0(mname: String, desc: String) = mname == "wait" && desc == "()V"
    private fun isWait1(mname: String, desc: String) = mname == "wait" && desc == "(J)V"
    private fun isWait2(mname: String, desc: String) = mname == "wait" && desc == "(JI)V"

    private fun isNotify(mname: String, desc: String) = mname == "notify" && desc == "()V"
    private fun isNotifyAll(mname: String, desc: String) = mname == "notifyAll" && desc == "()V"
}