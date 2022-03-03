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

import org.jetbrains.kotlinx.lincheck.CrashResult
import org.jetbrains.kotlinx.lincheck.runner.TestThreadExecution

/**
 * This utility class stores the current [NVMState] object. In order to run several tests in parallel,
 * each iteration should use its own class loader so that the state is unique for each class loader and, therefore,
 * for each iteration.
 * @see org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategyStateHolder
 */
internal object NVMStateHolder {
    @JvmField
    @Volatile
    var state: NVMState? = null

    /** Initialize state with [_state]. */
    fun setState(loader: ClassLoader, _state: NVMState) {
        try {
            val clazz = loader.loadClass(NVMStateHolder::class.java.canonicalName)
            clazz.getField("state")[null] = _state
        } catch (e: Throwable) {
            throw IllegalStateException("Cannot set state to NVMStateHolder", e)
        }
    }

    // auxiliary functions for byte code generation

    @JvmStatic
    fun possiblyCrash(className: String?, fileName: String?, methodName: String?, lineNumber: Int): Unit =
        crash.possiblyCrash(className, fileName, methodName, lineNumber)

    @JvmStatic
    fun awaitSystemCrash(execution: TestThreadExecution?): Unit = crash.awaitSystemCrash(execution)

    @JvmStatic
    fun isCrashed(): Boolean = crash.isCrashed()

    @JvmStatic
    fun resetAllCrashed(): Unit = crash.resetAllCrashed()

    @JvmStatic
    fun registerCrashResult(crashResult: CrashResult): Unit = state!!.registerCrashResult(crashResult)

    private val crash get() = state!!.crash
}
