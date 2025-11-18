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

import org.jetbrains.lincheck.jvm.agent.*
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE
import sun.nio.ch.lincheck.*


/**
 * [ThreadRunTransformer] tracks [Thread.run] method start and finish
 * by instrumenting [Thread] class and its subclasses.
 */
internal class ThreadRunTransformer(
    fileName: String,
    className: String,
    methodName: String,
    methodInfo: MethodInformation,
    descriptor: String,
    access: Int,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
    val configuration: TransformationConfiguration,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, adapter, methodVisitor) {

    // TODO: unify with `IgnoredSectionWrapperTransformer` ---
    //  extract common logic of injecting code on method entry/exit.

    private val runMethodTryBlockStart: Label = adapter.newLabel()
    private val runMethodTryBlockEnd: Label = adapter.newLabel()
    private val runMethodCatchBlock: Label = adapter.newLabel()

    override fun visitCode() = adapter.run {
        super.visitCode()
        if (isThreadRunMethod(methodName, descriptor)) {
            // STACK: <empty>
            visitTryCatchBlock(runMethodTryBlockStart, runMethodTryBlockEnd, runMethodCatchBlock, null)
            visitLabel(runMethodTryBlockStart)
            // STACK: <empty>
            invokeStatic(Injections::beforeThreadRun)
            // STACK: <empty>
        }
    }

    override fun visitInsn(opcode: Int) = adapter.run {
        if (isThreadRunMethod(methodName, descriptor) && opcode == Opcodes.RETURN) {
            // STACK: <empty>
            invokeStatic(Injections::afterThreadRunReturn)
            // STACK: <empty>
        }
        super.visitInsn(opcode)
    }

    override fun visitMaxs(maxStack: Int, maxLocals: Int) = adapter.run {
        if (isThreadRunMethod(methodName, descriptor)) {
            visitLabel(runMethodTryBlockEnd)
            visitLabel(runMethodCatchBlock)
            // STACK: exception
            invokeStatic(Injections::afterThreadRunException)
            // STACK: <empty>

            // Notify that the thread has finished.
            // TODO: currently does not work, because `ManagedStrategy::onThreadFinish`
            //   assumes the thread finish injection is called under normal managed execution,
            //   i.e., in non-aborted state
            // invokeStatic(Injections::afterThreadFinish)

            visitInsn(Opcodes.RETURN)
        }

        super.visitMaxs(maxStack, maxLocals)
    }

    private fun isThreadRunMethod(methodName: String, desc: String): Boolean =
        methodName == "run" && desc == VOID_METHOD_DESCRIPTOR && isThreadSubClass(className.toCanonicalClassName())
}

/**
 * [ThreadStartJoinTransformer] tracks [Thread.start] and [Thread.join] method invocations.
 */
internal class ThreadStartJoinTransformer(
    fileName: String,
    className: String,
    methodName: String,
    methodInfo: MethodInformation,
    descriptor: String,
    access: Int,
    adapter: GeneratorAdapter,
    methodVisitor: MethodVisitor,
    val configuration: TransformationConfiguration,
) : LincheckMethodVisitor(fileName, className, methodName, descriptor, access, methodInfo, adapter, methodVisitor) {

    override fun visitCode() = adapter.run {
        super.visitCode()
        // TODO: here we instrument `Thread::start` body itself (callee-site based instrumentation),
        //  consider instead instrumenting `Thread.start()` calls (call-site based instrumentation).
        //  This would be consistent with `Thread.join()` instrumentation,
        //  and interacts better with the (call-site based) method calls instrumentation.
        if (configuration.trackThreadStart && isThreadStartMethod(methodName, descriptor)) {
            // STACK: <empty>
            loadThis()
            // STACK: startingThread
            invokeStatic(Injections::beforeThreadStart)
            // STACK: <empty>
        }
    }

    override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
        if (configuration.trackThreadJoin && isThreadJoinCall(owner, name, desc) &&
            // in some JDK implementations, `Thread` methods may themselves call `join`,
            // so we do not instrument `join` class inside the `Thread` class itself.
            !isThreadClass(className.toCanonicalClassName())
        ) {
            // STACK: thread, millis?, nanos?
            val threadLocal = newLocal(OBJECT_TYPE)
            val millisLocal = newLocal(Type.LONG_TYPE)
            val nanosLocal = newLocal(Type.INT_TYPE)
            val nArguments = Type.getArgumentTypes(desc).size
            val withTimeout = (nArguments > 0) // TODO: `join(0)` should be handled same as `join()`
            if (nArguments == 0) {
                // STACK: thread
                copyLocal(threadLocal)
                // STACK: thread
            }
            if (nArguments == 1) {
                // STACK: thread, millis
                storeLocal(millisLocal)
                copyLocal(threadLocal)
                loadLocal(millisLocal)
                // STACK: thread, millis
            }
            if (nArguments == 2) {
                // STACK: thread, millis, nanos
                storeLocal(nanosLocal)
                storeLocal(millisLocal)
                copyLocal(threadLocal)
                loadLocal(millisLocal)
                loadLocal(nanosLocal)
                // STACK: thread, millis, nanos
            }
            loadLocal(threadLocal)
            push(withTimeout)
            // STACK: thread, millis?, nanos?, thread, withTimeout
            invokeStatic(Injections::onThreadJoin)
            // STACK: thread, millis?, nanos?
        }

        // In some newer versions of JDK, `ThreadPoolExecutor` uses
        // the internal `ThreadContainer` classes to manage threads in the pool;
        // This class, in turn, has the method `start`,
        // that does not directly call `Thread.start` to start a thread,
        // but instead uses internal API `JavaLangAccess.start`.
        // To detect threads started in this way, we instrument this class
        // and inject the appropriate hook on calls to the `JavaLangAccess.start` method.
        if (configuration.trackThreadStart && isJavaLangAccessThreadStartMethod(owner, name)) {
            // STACK: thread, threadContainer
            val threadContainerLocal = newLocal(OBJECT_TYPE)
            storeLocal(threadContainerLocal)
            dup()
            // STACK: thread, thread
            invokeStatic(Injections::beforeThreadStart)
            // STACK: thread
            loadLocal(threadContainerLocal)
            // STACK: thread, threadContainer
        }

        super.visitMethodInsn(opcode, owner, name, desc, itf)
    }

    private fun isThreadStartMethod(methodName: String, desc: String): Boolean =
        methodName == "start" && desc == VOID_METHOD_DESCRIPTOR && isThreadSubClass(className.toCanonicalClassName())

    private fun isJavaLangAccessThreadStartMethod(className: String, methodName: String): Boolean =
        methodName == "start" && isJavaLangAccessClass(className.toCanonicalClassName())

    // TODO: add support for thread joins with time limit
    private fun isThreadJoinCall(className: String, methodName: String, desc: String) =
        // no need to check for thread subclasses here, since join methods are marked as final
        methodName == "join" && isThreadClass(className.toCanonicalClassName()) && (
            desc == VOID_METHOD_DESCRIPTOR || desc == "(J)V" || desc == "(JI)V"
        )
}

private val VOID_METHOD_DESCRIPTOR: String = Type.getMethodDescriptor(Type.VOID_TYPE)
