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

import org.jetbrains.kotlinx.lincheck.transformation.*
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import sun.nio.ch.lincheck.*


/**
 * Instruments [java.lang.Thread] class and its subclasses by making the thread
 * report itself on [Thread.start] and on finish of [Thread.run] to [EventTracker] instance.
 *
 * Also, it tracks for the [java.lang.Thread.join] method calls and injects corresponding handler methods.
 */
internal class ThreadTransformer(
    fileName: String,
    className: String,
    methodName: String,
    private val desc: String,
    private val isThreadSubclass: Boolean,
    adapter: GeneratorAdapter,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter)  {

    override fun visitCode() = adapter.run {
        if (isThreadSubclass && isStartMethod(methodName, desc)) {
            // STACK: <empty>
            loadThis()
            // STACK: forkedThread
            invokeStatic(Injections::beforeThreadFork)
            // STACK: <empty>
        }
        if (isThreadSubclass && isRunMethod(methodName, desc)) {
            // STACK: <empty>
            invokeStatic(Injections::beforeThreadStart)
            // STACK: <empty>
        }
        visitCode()
    }

    override fun visitInsn(opcode: Int) = adapter.run {
        // TODO: this approach does not support thread interruptions and any other thrown exceptions
        if (isThreadSubclass && isStartMethod(methodName, desc) && opcode == Opcodes.RETURN) {
            // STACK: <empty>
            loadThis()
            // STACK: forkedThread
            invokeStatic(Injections::afterThreadFork)
            // STACK: <empty>
        }
        if (isThreadSubclass && isRunMethod(methodName, desc) && opcode == Opcodes.RETURN) {
            // STACK: <empty>
            invokeStatic(Injections::afterThreadFinish)
        }
        visitInsn(opcode)
    }

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        if (isThreadJoinCall(owner, name, desc)) {
            // STACK: joiningThread
            dup()
            // STACK: joiningThread, joiningThread
            invokeStatic(Injections::beforeThreadJoin)
            // STACK: joiningThread
            adapter.visitMethodInsn(opcode, owner, name, desc, itf)
            // STACK: <empty>
        } else {
            adapter.visitMethodInsn(opcode, owner, name, desc, itf)
        }
    }

    // TODO: add support for thread joins with time limit
    private fun isThreadJoinCall(owner: String, methodName: String, desc: String) =
        // no need to check for thread subclasses here, since join methods are marked as final
        owner == JAVA_THREAD_CLASSNAME && methodName == "join" && desc == VOID_METHOD_DESCRIPTOR

    private fun isStartMethod(methodName: String, desc: String): Boolean =
        methodName == "start" && desc == VOID_METHOD_DESCRIPTOR

    private fun isRunMethod(methodName: String, desc: String): Boolean =
        methodName == "run" && desc == VOID_METHOD_DESCRIPTOR
}

internal const val JAVA_THREAD_CLASSNAME = "java/lang/Thread"

private val EMPTY_TYPE_ARRAY = emptyArray<Type>()

private val VOID_METHOD_DESCRIPTOR: String = Type.getMethodDescriptor(Type.VOID_TYPE, *EMPTY_TYPE_ARRAY)
