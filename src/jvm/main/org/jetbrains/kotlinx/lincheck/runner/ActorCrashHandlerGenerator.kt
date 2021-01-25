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
import org.jetbrains.kotlinx.lincheck.nvm.Crash
import org.jetbrains.kotlinx.lincheck.nvm.CrashError
import org.objectweb.asm.Label
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method

private val CRASH_ERROR_TYPE = Type.getType(CrashError::class.java)
private val CRASH_RESULT_TYPE = Type.getType(CrashResult::class.java)
private val RESULT_KT_CREATE_CRASH_RESULT_METHOD = Method("creteCrashResult", CRASH_RESULT_TYPE, emptyArray())
private val CRASH_TYPE = Type.getType(Crash::class.java)
private val CRASH_AWAIT_SYSTEM_CRASH = Method("awaitSystemCrash", Type.VOID_TYPE, emptyArray())

open class ActorCrashHandlerGenerator {
    open fun addCrashTryBlock(start: Label, end: Label, mv: GeneratorAdapter) {}
    open fun addCrashCatchBlock(mv: GeneratorAdapter, resLocal: Int, iLocal: Int, skip: Label) {}
}

class DurableActorCrashHandlerGenerator : ActorCrashHandlerGenerator() {
    private lateinit var handlerLabel: Label

    override fun addCrashTryBlock(start: Label, end: Label, mv: GeneratorAdapter) {
        super.addCrashTryBlock(start, end, mv)
        handlerLabel = mv.newLabel()
        mv.visitTryCatchBlock(start, end, handlerLabel, CRASH_ERROR_TYPE.internalName)
    }

    override fun addCrashCatchBlock(mv: GeneratorAdapter, resLocal: Int, iLocal: Int, skip: Label) {
        super.addCrashCatchBlock(mv, resLocal, iLocal, skip)
        mv.visitLabel(handlerLabel)
        storeExceptionResultFromCrash(mv, resLocal, iLocal, skip)
    }

    private fun storeExceptionResultFromCrash(mv: GeneratorAdapter, resLocal: Int, iLocal: Int, skip: Label) {
        mv.pop()

        mv.loadLocal(resLocal)
        mv.loadLocal(iLocal)

        // Create crash result instance
        mv.invokeStatic(TestThreadExecutionGenerator.RESULT_KT_TYPE, RESULT_KT_CREATE_CRASH_RESULT_METHOD)
        mv.checkCast(TestThreadExecutionGenerator.RESULT_TYPE)
        mv.arrayStore(TestThreadExecutionGenerator.RESULT_TYPE)

        // clock increment must go before barrier
        TestThreadExecutionGenerator.incrementClock(mv, iLocal)

        mv.invokeStatic(CRASH_TYPE, CRASH_AWAIT_SYSTEM_CRASH)

        mv.goTo(skip)
    }
}
