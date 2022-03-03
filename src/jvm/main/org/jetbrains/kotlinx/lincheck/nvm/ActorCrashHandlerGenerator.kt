/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.nvm

import org.jetbrains.kotlinx.lincheck.Result
import org.jetbrains.kotlinx.lincheck.createCrashResult
import org.jetbrains.kotlinx.lincheck.runner.TestThreadExecution
import org.jetbrains.kotlinx.lincheck.runner.TestThreadExecutionGenerator
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import kotlin.reflect.jvm.javaMethod

private val CRASH_ERROR_TYPE = Type.getType(CrashError::class.java)
private val RESULT_KT_CREATE_CRASH_RESULT_METHOD = Method.getMethod(::createCrashResult.javaMethod)
private val NVM_STATE_HOLDER_TYPE = Type.getType(NVMStateHolder::class.java)
private val AWAIT_SYSTEM_CRASH_METHOD = Method.getMethod(NVMStateHolder::awaitSystemCrash.javaMethod)
private val REGISTER_CRASH_RESULT_METHOD = Method.getMethod(NVMStateHolder::registerCrashResult.javaMethod)
private val SET_USE_CLOCKS = Method.getMethod(TestThreadExecution::forceUseClocksOnce.javaMethod)
private val TEST_THREAD_EXECUTION_TYPE = Type.getType(TestThreadExecution::class.java)
private val RESULT_TYPE = Type.getType(Result::class.java)

/** Wraps actor invocation with a try/catch block during actor code generation with [TestThreadExecutionGenerator]. */
open class ActorCrashHandlerGenerator {
    /** Add try/catch block and labels. */
    open fun addCrashTryBlock(start: Label, end: Label, mv: GeneratorAdapter) {}

    /** Generate catch block code. */
    open fun addCrashCatchBlock(mv: GeneratorAdapter, resLocal: Int, iLocal: Int, nextLabel: Label) {}
}

/**
 * Crash handler generator in durable model.
 * In catch block crash result is created and stored as a result,
 * clocks are incremented (this must be done before the barrier) and
 * then a barrier method is invoked ([Crash.awaitSystemCrash]).
 */
internal class DurableActorCrashHandlerGenerator : ActorCrashHandlerGenerator() {
    private lateinit var handlerLabel: Label

    override fun addCrashTryBlock(start: Label, end: Label, mv: GeneratorAdapter) {
        super.addCrashTryBlock(start, end, mv)
        handlerLabel = mv.newLabel()
        mv.visitTryCatchBlock(start, end, handlerLabel, CRASH_ERROR_TYPE.internalName)
    }

    override fun addCrashCatchBlock(mv: GeneratorAdapter, resLocal: Int, iLocal: Int, nextLabel: Label) {
        super.addCrashCatchBlock(mv, resLocal, iLocal, nextLabel)
        mv.visitLabel(handlerLabel)
        storeExceptionResultFromCrash(mv, resLocal, iLocal, nextLabel)
    }

    private fun storeExceptionResultFromCrash(mv: GeneratorAdapter, resLocal: Int, iLocal: Int, skip: Label): Unit = mv.run {
        pop()

        loadLocal(resLocal)
        loadLocal(iLocal)

        // Create crash result instance
        invokeStatic(TestThreadExecutionGenerator.RESULT_KT_TYPE, RESULT_KT_CREATE_CRASH_RESULT_METHOD)
        // Register crash result
        dup()
        invokeStatic(NVM_STATE_HOLDER_TYPE, REGISTER_CRASH_RESULT_METHOD)
        checkCast(RESULT_TYPE)
        arrayStore(RESULT_TYPE)

        // Increment number of current operation
        iinc(iLocal, 1)

        // force read clocks for next actor
        loadThis()
        invokeVirtual(TEST_THREAD_EXECUTION_TYPE, SET_USE_CLOCKS)

        loadThis()
        invokeStatic(NVM_STATE_HOLDER_TYPE, AWAIT_SYSTEM_CRASH_METHOD)

        goTo(skip)
    }
}

/**
 * Crash handler generator in detectable execution model.
 * In catch block the crashed actor is invoked again after a system crash barrier.
 */
internal class DetectableExecutionActorCrashHandlerGenerator : ActorCrashHandlerGenerator() {
    private lateinit var startLabel: Label
    private lateinit var handlerLabel: Label

    override fun addCrashTryBlock(start: Label, end: Label, mv: GeneratorAdapter) {
        super.addCrashTryBlock(start, end, mv)
        startLabel = start
        handlerLabel = mv.newLabel()
        mv.visitTryCatchBlock(start, end, handlerLabel, CRASH_ERROR_TYPE.internalName)
    }

    override fun addCrashCatchBlock(mv: GeneratorAdapter, resLocal: Int, iLocal: Int, nextLabel: Label) {
        super.addCrashCatchBlock(mv, resLocal, iLocal, nextLabel)
        mv.run {
            val afterActor = newLabel()
            goTo(afterActor)
            visitLabel(handlerLabel)
            pop()
            visitInsn(Opcodes.ACONST_NULL)
            invokeStatic(NVM_STATE_HOLDER_TYPE, AWAIT_SYSTEM_CRASH_METHOD)
            goTo(startLabel)
            visitLabel(afterActor)
        }
    }
}
