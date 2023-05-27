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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.utils.*

interface ExecutionFrontier {
    val threadMap: ThreadMap<Event>
}

interface MutableExecutionFrontier : ExecutionFrontier {
    override val threadMap: MutableThreadMap<Event>
}

val ExecutionFrontier.threadIDs: Set<ThreadID>
    get() = threadMap.keys

operator fun ExecutionFrontier.get(tid: ThreadID): Event? =
    threadMap[tid]

fun ExecutionFrontier.getPosition(tid: ThreadID): Int =
    get(tid)?.threadPosition ?: -1

fun ExecutionFrontier.getNextPosition(tid: ThreadID): Int =
    1 + getPosition(tid)

operator fun ExecutionFrontier.contains(event: Event): Boolean {
    val lastEvent = get(event.threadId) ?: return false
    return programOrder.lessOrEqual(event, lastEvent)
}

operator fun MutableExecutionFrontier.set(tid: ThreadID, event: Event?) {
    if (event == null) {
        threadMap.remove(tid)
        return
    }
    require(tid == event.threadId)
    threadMap[tid] = event
}

fun MutableExecutionFrontier.update(event: Event) {
    check(event.parent == get(event.threadId))
    set(event.threadId, event)
}

fun MutableExecutionFrontier.merge(other: ExecutionFrontier) {
    threadMap.mergeReduce(other.threadMap, programOrder::max)
}

fun MutableExecutionFrontier.cut(cutEvents: List<Event>) {
    if (cutEvents.isEmpty())
        return
    threadMap.forEach { tid, event ->
        // TODO: optimize --- transform cutEvents into vector clock
        // TODO: optimize using binary search
        val pred = event.pred { !cutEvents.any { cutEvent ->
            it.causalityClock.observes(cutEvent.threadId, cutEvent)
        }}
        set(tid, pred)
    }
}

fun ExecutionFrontier.copy(): MutableExecutionFrontier {
    check(this is ExecutionFrontierImpl)
    return ExecutionFrontierImpl(nThreads).also {
        for (i in 0 until nThreads) {
            it[i] = this[i] ?: continue
        }
    }
}

fun ExecutionFrontier.toExecution(): MutableExecution =
    threadIDs.map { tid ->
        tid to get(tid)!!.threadPrefix(inclusive = true)
    }.let {
        mutableExecutionOf(*it.toTypedArray())
    }

private class ExecutionFrontierImpl(val nThreads: Int): MutableExecutionFrontier {
    override val threadMap = ArrayMap<Event>(nThreads)
}

/**
 * ExecutionFrontier represents a frontier of an execution,
 * that is the set of program-order maximal events of the execution.
 */
// class ExecutionFrontier(frontier: Map<Int, Event> = emptyMap()) {
//
//     private val frontier: VectorClock<Int, Event> =
//         VectorClock(programOrder, frontier)
//
//     val mapping: Map<Int, Event>
//         get() = frontier.clock
//
//     operator fun get(iThread: Int): Event? =
//         frontier[iThread]
//
//     operator fun set(iThread: Int, event: Event) {
//         check(iThread == event.threadId)
//         // TODO: properly document this precondition
//         //  (i.e. we expect frontier to be updated to some offspring of frontier's execution)
//         // check(programOrder.nullOrLessOrEqual(event.parent, frontier[iThread]))
//         frontier[iThread] = event
//     }
//
//     fun merge(other: ExecutionFrontier) {
//         frontier.merge(other.frontier)
//     }
//
//     fun copy(): ExecutionFrontier =
//         ExecutionFrontier(mapping)
//
//     fun cut(cutEvents: List<Event>): ExecutionFrontier {
//         return if (cutEvents.isEmpty())
//             copy()
//         else ExecutionFrontier(frontier.clock.mapNotNull { (threadId, frontEvent) ->
//             var event: Event = frontEvent
//             // TODO: optimize --- transform cutEvents into vector clock
//             cutEvents.forEach { cutEvent ->
//                 // TODO: optimize using binary search
//                 while (event.causalityClock.observes(cutEvent.threadId, cutEvent)) {
//                     event = event.parent ?: return@mapNotNull null
//                 }
//             }
//             threadId to event
//         }.toMap())
//     }
//
//     fun toVectorClock(): VectorClock<Int, Event> =
//         frontier.copy()
//
// }
//
// // TODO: ensure that vector clock is indexed by thread ids: VectorClock<ThreadID, Event>
// fun VectorClock<Int, Event>.toFrontier(): ExecutionFrontier =
//     ExecutionFrontier(this.clock)
