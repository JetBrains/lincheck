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

import org.jetbrains.kotlinx.lincheck.ensure
import org.jetbrains.kotlinx.lincheck.utils.*

// TODO: implement VectorClock interface??
interface ExecutionFrontier {
    val threadMap: ThreadMap<ThreadEvent?>
}

interface MutableExecutionFrontier : ExecutionFrontier {
    override val threadMap: MutableThreadMap<ThreadEvent?>
}

val ExecutionFrontier.threadIDs: Set<ThreadID>
    get() = threadMap.keys

val ExecutionFrontier.events: List<ThreadEvent>
    get() = threadMap.values.filterNotNull()

operator fun ExecutionFrontier.get(tid: ThreadID): ThreadEvent? =
    threadMap[tid]

fun ExecutionFrontier.getPosition(tid: ThreadID): Int =
    get(tid)?.threadPosition ?: -1

fun ExecutionFrontier.getNextPosition(tid: ThreadID): Int =
    1 + getPosition(tid)

operator fun ExecutionFrontier.contains(event: ThreadEvent): Boolean {
    val lastEvent = get(event.threadId) ?: return false
    return programOrder.lessOrEqual(event, lastEvent)
}

operator fun MutableExecutionFrontier.set(tid: ThreadID, event: ThreadEvent?) {
    require(tid == (event?.threadId ?: tid))
    threadMap[tid] = event
}

fun MutableExecutionFrontier.update(event: ThreadEvent) {
    check(event.parent == get(event.threadId))
    set(event.threadId, event)
}

fun MutableExecutionFrontier.merge(other: ExecutionFrontier) {
    threadMap.mergeReduce(other.threadMap) { x, y -> when {
        x == null -> y
        y == null -> x
        else -> programOrder.max(x, y)
    }}
}

// TODO: unify semantics with MutableExecution.cut()
// TODO: rename?
fun MutableExecutionFrontier.cut(cutEvents: List<ThreadEvent>) {
    if (cutEvents.isEmpty())
        return
    threadMap.forEach { (tid, event) ->
        // TODO: optimize --- transform cutEvents into vector clock
        // TODO: optimize using binary search
        val pred = event?.pred(inclusive = true) { !cutEvents.any { cutEvent ->
            it.causalityClock.observes(cutEvent.threadId, cutEvent.threadPosition)
        }}
        set(tid, pred)
    }
}

fun ExecutionFrontier(nThreads: Int): ExecutionFrontier =
    MutableExecutionFrontier(nThreads)

fun MutableExecutionFrontier(nThreads: Int): MutableExecutionFrontier =
    ExecutionFrontierImpl(ArrayMap(nThreads))

fun executionFrontierOf(vararg pairs: Pair<ThreadID, ThreadEvent?>): ExecutionFrontier =
    mutableExecutionFrontierOf(*pairs)

fun mutableExecutionFrontierOf(vararg pairs: Pair<ThreadID, ThreadEvent?>): MutableExecutionFrontier =
    ExecutionFrontierImpl(ArrayMap(*pairs))

fun ExecutionFrontier.copy(): MutableExecutionFrontier {
    check(this is ExecutionFrontierImpl)
    return ExecutionFrontierImpl(threadMap.copy())
}

private class ExecutionFrontierImpl(override val threadMap: ArrayMap<ThreadEvent?>): MutableExecutionFrontier

fun ExecutionFrontier.toExecution(): Execution =
    toMutableExecution()

fun ExecutionFrontier.toMutableExecution(): MutableExecution =
    threadIDs.map { tid ->
        tid to (get(tid)?.threadPrefix(inclusive = true) ?: listOf())
    }.let {
        mutableExecutionOf(*it.toTypedArray())
    }

fun MutableExecutionFrontier.cutDanglingRequestEvents() {
    for (threadId in threadIDs) {
        val lastEvent = get(threadId) ?: continue
        if (lastEvent.label.isRequest && !lastEvent.label.isBlocking) {
            lastEvent.parent?.label?.ensure { !it.isRequest }
            set(threadId, lastEvent.parent)
        }
    }
}