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

package org.jetbrains.kotlinx.lincheck.runner

import org.jetbrains.kotlinx.lincheck.CrashResult
import org.jetbrains.kotlinx.lincheck.nvm.BusyWaitingBarrier
import org.jetbrains.kotlinx.lincheck.nvm.Crash
import org.jetbrains.kotlinx.lincheck.nvm.CrashError
import org.jetbrains.kotlinx.lincheck.runner.TestThreadExecutionGenerator.OBJECT_TYPE
import org.jetbrains.kotlinx.lincheck.runner.TestThreadExecutionGenerator.TEST_THREAD_EXECUTION_TYPE
import org.objectweb.asm.Label
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method

private val CRASH_ERROR_TYPE = Type.getType(CrashError::class.java)
private val CRASH_RESULT_TYPE = Type.getType(CrashResult::class.java)
private val RESULT_KT_CREATE_CRASH_RESULT_METHOD = Method("creteCrashResult", CRASH_RESULT_TYPE, emptyArray())
private val CRASH_TYPE = Type.getType(Crash::class.java)
private val BARRIER_TYPE = Type.getType(BusyWaitingBarrier::class.java)
private val CRASH_AWAIT_SYSTEM_CRASH = Method("awaitSystemCrash", BARRIER_TYPE, arrayOf(Type.BOOLEAN_TYPE))
private val CRASH_AWAIT_SYSTEM_RECOVER = Method("awaitSystemRecover", Type.VOID_TYPE, arrayOf(BARRIER_TYPE))
private val SET_USE_CLOCKS = Method("useClocksOnce", Type.VOID_TYPE, emptyArray())

open class ActorCrashHandlerGenerator {
    open fun addCrashTryBlock(start: Label, end: Label, mv: GeneratorAdapter) {}
    open fun addCrashCatchBlock(
        mv: GeneratorAdapter,
        resLocal: Int,
        iLocal: Int,
        skip: Label,
        _class: Class<*>?
    ) {
    }
}

class DurableActorCrashHandlerGenerator : ActorCrashHandlerGenerator() {
    private lateinit var handlerLabel: Label

    override fun addCrashTryBlock(start: Label, end: Label, mv: GeneratorAdapter) {
        super.addCrashTryBlock(start, end, mv)
        handlerLabel = mv.newLabel()
        mv.visitTryCatchBlock(start, end, handlerLabel, CRASH_ERROR_TYPE.internalName)
    }

    override fun addCrashCatchBlock(mv: GeneratorAdapter, resLocal: Int, iLocal: Int, skip: Label, _class: Class<*>?) {
        super.addCrashCatchBlock(mv, resLocal, iLocal, skip, _class)
        mv.visitLabel(handlerLabel)
        storeExceptionResultFromCrash(mv, resLocal, iLocal, skip, _class)
    }

    private fun storeExceptionResultFromCrash(
        mv: GeneratorAdapter,
        resLocal: Int,
        iLocal: Int,
        skip: Label,
        _class: Class<*>?
    ) {
        mv.pop()

        mv.loadLocal(resLocal)
        mv.loadLocal(iLocal)

        // Create crash result instance
        mv.invokeStatic(TestThreadExecutionGenerator.RESULT_KT_TYPE, RESULT_KT_CREATE_CRASH_RESULT_METHOD)
        mv.checkCast(TestThreadExecutionGenerator.RESULT_TYPE)
        mv.arrayStore(TestThreadExecutionGenerator.RESULT_TYPE)

        // clock increment must go before barrier
        TestThreadExecutionGenerator.incrementClock(mv, iLocal)

        // force read clocks for next actor
        mv.loadThis()
        mv.invokeVirtual(TEST_THREAD_EXECUTION_TYPE, SET_USE_CLOCKS)

        mv.push(true)
        mv.invokeStatic(CRASH_TYPE, CRASH_AWAIT_SYSTEM_CRASH)

        // call recover if exists
        if (_class != null) {
            val method = _class.methods
                .singleOrNull { it.name == "recover" && it.parameterCount == 0 && it.returnType == Void.TYPE }
            val type = Type.getType(_class)
            if (method != null) {
                // Load test instance
                mv.loadThis()
                mv.getField(TEST_THREAD_EXECUTION_TYPE, "testInstance", OBJECT_TYPE)
                mv.checkCast(type)
                mv.invokeVirtual(type, Method.getMethod(method))
            }
        }
        mv.checkCast(BARRIER_TYPE)
        mv.invokeStatic(CRASH_TYPE, CRASH_AWAIT_SYSTEM_RECOVER)

        mv.goTo(skip)
    }
}
