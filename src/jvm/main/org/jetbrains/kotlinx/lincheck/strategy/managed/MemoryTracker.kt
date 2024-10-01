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

package org.jetbrains.kotlinx.lincheck.strategy.managed

import java.util.*

interface MemoryTracker {

    fun beforeWrite(iThread: Int, codeLocation: Int, location: MemoryLocation, value: Any?)

    fun beforeRead(iThread: Int, codeLocation: Int, location: MemoryLocation)

    fun beforeGetAndSet(iThread: Int, codeLocation: Int, location: MemoryLocation, newValue: Any?)

    fun beforeCompareAndSet(iThread: Int, codeLocation: Int, location: MemoryLocation, expectedValue: Any?, newValue: Any?)

    fun beforeCompareAndExchange(iThread: Int, codeLocation: Int, location: MemoryLocation, expectedValue: Any?, newValue: Any?)

    // TODO: move increment kind enum here?
    fun beforeGetAndAdd(iThread: Int, codeLocation: Int, location: MemoryLocation, delta: Number)

    fun beforeAddAndGet(iThread: Int, codeLocation: Int, location: MemoryLocation, delta: Number)

    fun interceptReadResult(iThread: Int): Any?

    fun reset()

}

/**
 * Tracks memory operations with shared variables.
 */
// abstract class MemoryTracker {
//
//     abstract fun writeValue(iThread: Int, codeLocation: Int, location: MemoryLocation, value: OpaqueValue?)
//
//     abstract fun readValue(iThread: Int, codeLocation: Int, location: MemoryLocation): OpaqueValue?
//
//     abstract fun compareAndSet(iThread: Int, codeLocation: Int, location: MemoryLocation, expected: OpaqueValue?, desired: OpaqueValue?): Boolean
//
//     abstract fun addAndGet(iThread: Int, codeLocation: Int, location: MemoryLocation, delta: Number): OpaqueValue?
//
//     abstract fun getAndAdd(iThread: Int, codeLocation: Int, location: MemoryLocation, delta: Number): OpaqueValue?
//
//     abstract fun getAndSet(iThread: Int, codeLocation: Int, location: MemoryLocation, value: OpaqueValue?): OpaqueValue?
//
//     abstract fun dumpMemory()
//
//     abstract fun reset()
//
// }

typealias MemoryInitializer = (MemoryLocation) -> OpaqueValue?
typealias MemoryIDInitializer = (MemoryLocation) -> ValueID