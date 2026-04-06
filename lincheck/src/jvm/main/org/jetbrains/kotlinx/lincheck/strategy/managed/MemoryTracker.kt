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

import org.jetbrains.kotlinx.lincheck.util.ThreadId
import org.jetbrains.lincheck.util.AtomicMethodDescriptor
import org.jetbrains.lincheck.util.AtomicMethodKind
import org.jetbrains.lincheck.util.MemoryOrdering
import org.jetbrains.lincheck.util.isAtomic
import org.jetbrains.lincheck.util.isAtomicArray
import org.jetbrains.lincheck.util.isUnsafe

/**
 * Tracks memory operations with shared variables.
 */
interface MemoryTracker {

    fun beforeWrite(iThread: Int, codeLocation: Int, location: MemoryLocation, memoryOrder: MemoryOrdering, value: Any?)

    fun beforeRead(iThread: Int, codeLocation: Int, location: MemoryLocation, memoryOrder: MemoryOrdering)

    fun beforeGetAndSet(iThread: Int, codeLocation: Int, location: MemoryLocation, memoryOrder: MemoryOrdering, newValue: Any?)

    fun beforeCompareAndSet(iThread: Int, codeLocation: Int, location: MemoryLocation, memoryOrder: MemoryOrdering, expectedValue: Any?, newValue: Any?)

    fun beforeCompareAndExchange(iThread: Int, codeLocation: Int, location: MemoryLocation, memoryOrder: MemoryOrdering, expectedValue: Any?, newValue: Any?)

    // TODO: move increment kind enum here?
    fun beforeGetAndAdd(iThread: Int, codeLocation: Int, location: MemoryLocation, memoryOrder: MemoryOrdering, delta: Number)

    fun beforeAddAndGet(iThread: Int, codeLocation: Int, location: MemoryLocation, memoryOrder: MemoryOrdering, delta: Number)

    fun interceptReadResult(iThread: Int): Any?

    fun interceptArrayCopy(iThread: Int, codeLocation: Int, srcArray: Any?, srcPos: Int, dstArray: Any?, dstPos: Int, length: Int)

    fun reset()
}

internal fun MemoryTracker.trackAtomicMethodMemoryAccess(
    owner: Any?,
    codeLocation: Int,
    params: Array<Any?>,
    methodDescriptor: AtomicMethodDescriptor?,
    iThread: ThreadId,
    location: MemoryLocation?,
): Boolean {
    if (methodDescriptor == null) return false
    if (location == null) return false

    var argOffset = 0
    // atomic reflection case (AFU, VarHandle or Unsafe) - the first argument is a reflection object
    argOffset += if (!isAtomic(owner) && !isAtomicArray(owner)) 1 else 0
    // Unsafe has an additional offset argument
    argOffset += if (isUnsafe(owner)) 1 else 0
    // array accesses (besides Unsafe) take index as an additional argument
    argOffset += if (location is ArrayElementMemoryLocation && !isUnsafe(owner)) 1 else 0
    when (methodDescriptor.kind) {
        AtomicMethodKind.SET -> {
            // TODO: add memory ordering
            beforeWrite(iThread, codeLocation, location, methodDescriptor.ordering,
                value = params[argOffset]
            )
        }
        AtomicMethodKind.GET -> {
            // TODO: add memory ordering
            beforeRead(iThread, codeLocation, location, methodDescriptor.ordering)
        }
        AtomicMethodKind.GET_AND_SET -> {
            beforeGetAndSet(iThread, codeLocation, location, methodDescriptor.ordering,
                newValue = params[argOffset]
            )
        }
        AtomicMethodKind.COMPARE_AND_SET, AtomicMethodKind.WEAK_COMPARE_AND_SET -> {
            beforeCompareAndSet(iThread, codeLocation, location, methodDescriptor.ordering,
                expectedValue = params[argOffset],
                newValue = params[argOffset + 1]
            )
        }
        AtomicMethodKind.COMPARE_AND_EXCHANGE -> {
            beforeCompareAndExchange(iThread, codeLocation, location, methodDescriptor.ordering,
                expectedValue = params[argOffset],
                newValue = params[argOffset + 1]
            )
        }
        AtomicMethodKind.GET_AND_ADD -> {
            beforeGetAndAdd(iThread, codeLocation, location, methodDescriptor.ordering,
                delta = (params[argOffset] as Number)
            )
        }
        AtomicMethodKind.ADD_AND_GET -> {
            beforeAddAndGet(iThread, codeLocation, location, methodDescriptor.ordering,
                delta = (params[argOffset] as Number)
            )
        }
        AtomicMethodKind.GET_AND_INCREMENT -> {
            beforeGetAndAdd(iThread, codeLocation, location, methodDescriptor.ordering, delta = 1.convert(location.type))
        }
        AtomicMethodKind.INCREMENT_AND_GET -> {
            beforeAddAndGet(iThread, codeLocation, location, methodDescriptor.ordering, delta = 1.convert(location.type))
        }
        AtomicMethodKind.GET_AND_DECREMENT -> {
            beforeGetAndAdd(iThread, codeLocation, location, methodDescriptor.ordering, delta = (-1).convert(location.type))
        }
        AtomicMethodKind.DECREMENT_AND_GET -> {
            beforeAddAndGet(iThread, codeLocation, location, methodDescriptor.ordering, delta = (-1).convert(location.type))
        }
    }
    return (methodDescriptor.kind != AtomicMethodKind.SET)
}

typealias MemoryInitializer = (MemoryLocation) -> OpaqueValue?
typealias MemoryIDInitializer = (MemoryLocation) -> ValueID