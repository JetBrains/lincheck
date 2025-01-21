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
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
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
    adapter: GeneratorAdapter,
) : ManagedStrategyMethodVisitor(fileName, className, methodName, adapter)  {

    override fun visitCode() = adapter.run {
        if (isThreadStartMethod(methodName, desc)) {
            // STACK: <empty>
            loadThis()
            // STACK: forkedThread
            invokeStatic(Injections::beforeThreadFork)
            // STACK: <empty>
        }
        if (isThreadRunMethod(methodName, desc)) {
            // STACK: <empty>
            invokeStatic(Injections::beforeThreadStart)
            // STACK: <empty>
        }
        visitCode()
    }

    override fun visitInsn(opcode: Int) = adapter.run {
        // TODO: this approach does not support thread interruptions and any other thrown exceptions
        if (isThreadRunMethod(methodName, desc) && opcode == Opcodes.RETURN) {
            // STACK: <empty>
            invokeStatic(Injections::afterThreadFinish)
        }
        visitInsn(opcode)
    }

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        if (isThreadJoinCall(owner, name, desc) &&
            // in some JDK implementations, `Thread` methods may themselves call `join`,
            // so we do not instrument `join` class inside the `Thread` class itself.
            className != JAVA_THREAD_CLASSNAME
        ) {
            // STACK: joiningThread, timeout?, nanos?
            val nArguments = Type.getArgumentTypes(desc).size
            val withTimeout = (nArguments > 0) // TODO: `join(0)` should be handled same as `join()`
            if (nArguments >= 2) {
                // int nanos
                pop()
            }
            if (nArguments >= 1) {
                // long timeout
                pop2()
            }
            push(withTimeout)
            // STACK: joiningThread
            invokeStatic(Injections::threadJoin)
            // STACK: <empty>
            return
        }
        // In some newer versions of JDK, some of the java library classes
        // use internal API `JavaLangAccess.start` to start threads;
        // so we instrument calls to this method to detect thread starts.
        if (isJavaLangAccessThreadStartMethod(owner, name)) {
            // STACK: thread, threadContainer
            val threadContainerLocal = newLocal(OBJECT_TYPE)
            storeLocal(threadContainerLocal)
            dup()
            // STACK: thread, thread
            invokeStatic(Injections::beforeThreadFork)
            // STACK: thread
            loadLocal(threadContainerLocal)
            // STACK: thread, threadContainer
        }
        adapter.visitMethodInsn(opcode, owner, name, desc, itf)
    }

    private fun isThreadStartMethod(methodName: String, desc: String): Boolean =
        methodName == "start" && desc == VOID_METHOD_DESCRIPTOR && isThreadSubClass(className)
    
    private fun isJavaLangAccessThreadStartMethod(className: String, methodName: String): Boolean =
        className == "jdk/internal/access/JavaLangAccess" && methodName == "start"

    private fun isThreadRunMethod(methodName: String, desc: String): Boolean =
        methodName == "run" && desc == VOID_METHOD_DESCRIPTOR && isThreadSubClass(className)

    // TODO: add support for thread joins with time limit
    private fun isThreadJoinCall(className: String, methodName: String, desc: String) =
        // no need to check for thread subclasses here, since join methods are marked as final
        className == JAVA_THREAD_CLASSNAME && methodName == "join" && (
            desc == "()V" || desc == "(J)V" || desc == "(JI)V"
        )
}

private val EMPTY_TYPE_ARRAY = emptyArray<Type>()

private val VOID_METHOD_DESCRIPTOR: String = Type.getMethodDescriptor(Type.VOID_TYPE, *EMPTY_TYPE_ARRAY)
