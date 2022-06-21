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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstruct

typealias EventID = Int

// TODO: make a constant for default thread ID
abstract class EventLabel(open val threadId: Int = 0) {
    /**
     * Synchronizes event label with another label passed as a parameter.
     * For example a write label `wlab = W(x, v)` synchronizes with a read-request label `rlab = R^{req}(x)`
     * and produces the read-response label `lab = R^{rsp}(x, v)`.
     * That is a call `rlab.synchronize(wlab)` returns `lab`.
     * Synchronize operation is expected to be associative and commutative.
     * Thus it is also declared as infix operation: `a synchronize b`.
     * In terms of synchronization algebra, non-null return value to the call `C = A.synchronize(B)`
     * means that `A \+ B = C` and consequently `A >> C` and `B >> C`
     * (read as A synchronizes with B into C, and A/B synchronizes with C respectively).
     */
    abstract infix fun synchronize(lab: EventLabel): EventLabel?
}

data class EmptyLabel(override val threadId: Int = 0): EventLabel(threadId) {
    override fun synchronize(lab: EventLabel) = lab
}

enum class MemoryAccessKind { ReadRequest, ReadResponse, Write }

data class MemoryAccessLabel(
    override val threadId: Int,
    val kind: MemoryAccessKind,
    val typeDesc: String,
    val memId: Int,
    val value: Any?
): EventLabel(threadId) {
    override fun synchronize(lab: EventLabel): EventLabel? {
        return when {
            (lab is MemoryAccessLabel) && (memId == lab.memId) -> {
                // TODO: perform dynamic type-check of `typeDesc`
                when {
                    (kind == MemoryAccessKind.Write) && (lab.kind == MemoryAccessKind.ReadRequest) ->
                        Triple(lab.threadId, typeDesc, value)
                    (kind == MemoryAccessKind.ReadRequest) && (lab.kind == MemoryAccessKind.Write) ->
                        Triple(threadId, lab.typeDesc, lab.value)
                    else -> null
                }?.let { (threadId, typeDesc, value) -> MemoryAccessLabel(
                    threadId = threadId,
                    kind = MemoryAccessKind.ReadResponse,
                    typeDesc = typeDesc,
                    memId = memId,
                    value = value
                ) }
            }
            (lab is EmptyLabel) -> this
            else -> null
        }
    }
}
