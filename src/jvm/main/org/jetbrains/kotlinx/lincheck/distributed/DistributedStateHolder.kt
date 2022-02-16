/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.distributed


class DistributedState(val previousDebugMode: String?) {
    /**
     * Indicates if the crash can be added before accessing database.
     * Set to false after the execution is over or than the event is created (see [org.jetbrains.kotlinx.lincheck.distributed.event.Event])
     */
    internal var canCrashBeforeAccessingDatabase = false
}

object DistributedStateHolder {
    @JvmField
    @Volatile
    var state: DistributedState? = null

    /** Initialize state with [_state]. */
    fun setState(loader: ClassLoader, _state: DistributedState) {
        try {
            val clazz = loader.loadClass(DistributedStateHolder::class.java.canonicalName)
            clazz.getField("state")[null] = _state
        } catch (e: Throwable) {
            throw IllegalStateException("Cannot set state to NVMStateHolder", e)
        }
    }

    var canCrashBeforeAccessingDatabase: Boolean
        get() = state!!.canCrashBeforeAccessingDatabase
        set(value) {
            state!!.canCrashBeforeAccessingDatabase = value
        }

    fun resetProperty() {
        if (state == null) return
        if (state!!.previousDebugMode != null) System.setProperty("kotlinx.coroutines.debug", state!!.previousDebugMode!!)
        else System.clearProperty("kotlinx.coroutines.debug")
    }
}

