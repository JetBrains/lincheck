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

import kotlinx.coroutines.delay
import org.jetbrains.kotlinx.lincheck.Result

abstract class TestNodeExecution {
    var runner: Runner? = null
    var testInstance: Any? = null
    lateinit var objArgs: Array<Any>
    lateinit var allTestNodeExecutions: Array<TestNodeExecution>
    lateinit var results: Array<Result?>
    lateinit var clocks: Array<IntArray>
    @Volatile
    var curClock = 0
    var useClocks = false

    fun readClocks(currentActor: Int) {
        for (i in allTestNodeExecutions.indices) {
            clocks[currentActor][i] = allTestNodeExecutions[i].curClock
        }
    }

    fun incClock() {
        curClock++
    }

    abstract suspend fun runOperation(i: Int): Any?
}
