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

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.*
import kotlin.native.concurrent.*
import kotlin.native.concurrent.AtomicInt
import kotlin.reflect.*

class NativeTestThreadExecution(val testInstance: Any, val threadExecution: TestThreadExecution) : Runnable {
    override fun run() {
        threadExecution.run(testInstance)
    }
}

class TestThreadExecution(val iThread: Int, val actors: List<Actor>) {
    val allThreadExecutions = AtomicReference<LincheckAtomicArray<TestThreadExecution>>(LincheckAtomicArray(0))

    val results = AtomicReference<LincheckAtomicArray<Result?>>(LincheckAtomicArray(0)) // for ExecutionResult
    val clocks = AtomicReference<LincheckAtomicArray<LincheckAtomicIntArray>>(LincheckAtomicArray(0)) // for HBClock

    var curClock = AtomicInt(0)
    var useClocks = AtomicInt(0) // 0 -- false, 1 -- true

    fun readClocks(currentActor: Int) {
        val arr = allThreadExecutions.value.toArray()
        for (i in arr.indices) {
            clocks.value.array[currentActor].value!!.array[i].value = arr[i].curClock.value
        }
    }

    fun incClock() {
        curClock.increment()
    }

    fun run(testInstance: Any) {
        //printErr("RUN $iThread #1")
        //runner.onStart(iThread)
        actors.forEachIndexed { index, actor ->
            //printErr("RUN $iThread #2 $index")
            readClocks(index)
            //runner.onActorStart(iThread)
            //Load arguments for operation
            val result: Result = try {
                val r = actor.function(testInstance, actor.arguments)
                //printErr("ValueResult")
                ValueResult(r)
            } catch (e: Throwable) {
                if (actor.handledExceptions.any { it.safeCast(e) != null }) { // Do a cast. If == null, cast failed and e is not an instance of it
                    //printErr("ExceptionResult")
                    ExceptionResult(e::class, false)
                } else {
                    //printErr("FailureResult with $e")
                    //runner.onFailure(iThread, e)
                    throw e
                }
            }
            results.value.array[index].value = result
            incClock()
        }
        //printErr("RUN $iThread #finish ")
        //runner.onFinish(iThread)
    }
}
