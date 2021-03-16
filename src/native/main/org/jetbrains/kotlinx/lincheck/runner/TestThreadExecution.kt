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

class TestThreadExecution(val runner: Runner, val iThread: Int, val actors: List<Actor>): Runnable {
    lateinit var testInstance: Any
    lateinit var objArgs: Array<Any>
    lateinit var allThreadExecutions: Array<TestThreadExecution>

    lateinit var results: Array<Result?> // for ExecutionResult
    lateinit var clocks: Array<IntArray> // for HBClock

    // TODO should be volatilie
    var curClock = 0
    var useClocks = false

    fun readClocks(currentActor: Int) {
        for (i in allThreadExecutions.indices) {
            clocks[currentActor][i] = allThreadExecutions[i].curClock
        }
    }

    fun incClock() {
        curClock++
    }


    override fun run() {
        TODO("Not yet implemented")
    }
}