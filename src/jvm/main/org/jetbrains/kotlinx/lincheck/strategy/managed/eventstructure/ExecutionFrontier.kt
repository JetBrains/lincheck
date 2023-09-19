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
interface ExecutionFrontier<out E : ThreadEvent> {
    val threadMap: ThreadMap<E?>
}

interface MutableExecutionFrontier<E : ThreadEvent> : ExecutionFrontier<E> {
    override val threadMap: MutableThreadMap<E?>
}

val ExecutionFrontier<*>.threadIDs: Set<ThreadID>
    get() = threadMap.keys

val<E : ThreadEvent> ExecutionFrontier<E>.events: List<E>
    get() = threadMap.values.filterNotNull()

operator fun<E : ThreadEvent> ExecutionFrontier<E>.get(tid: ThreadID): E? =
    threadMap[tid]

fun ExecutionFrontier<*>.getPosition(tid: ThreadID): Int =
    get(tid)?.threadPosition ?: -1

fun ExecutionFrontier<*>.getNextPosition(tid: ThreadID): Int =
    1 + getPosition(tid)

operator fun<E : ThreadEvent> ExecutionFrontier<E>.contains(event: E): Boolean {
    val lastEvent = get(event.threadId) ?: return false
    return programOrder.lessOrEqual(event, lastEvent)
}

operator fun<E : ThreadEvent> MutableExecutionFrontier<E>.set(tid: ThreadID, event: E?) {
    require(tid == (event?.threadId ?: tid))
    threadMap[tid] = event
}

fun<E : ThreadEvent> MutableExecutionFrontier<E>.update(event: E) {
    check(event.parent == get(event.threadId))
    set(event.threadId, event)
}

fun<E : ThreadEvent> MutableExecutionFrontier<E>.merge(other: ExecutionFrontier<E>) {
    threadMap.mergeReduce(other.threadMap) { x, y -> when {
        x == null -> y
        y == null -> x
        else -> programOrder.max(x, y)
    }}
}

// TODO: unify semantics with MutableExecution.cut()
// TODO: rename?
inline fun<reified E : ThreadEvent> MutableExecutionFrontier<E>.cut(cutEvents: List<E>) {
    if (cutEvents.isEmpty())
        return
    threadMap.forEach { (tid, event) ->
        // TODO: optimize --- transform cutEvents into vector clock
        // TODO: optimize using binary search
        val pred = event?.pred(inclusive = true) {
            (it is E) && !cutEvents.any { cutEvent ->
                it.causalityClock.observes(cutEvent.threadId, cutEvent.threadPosition)
            }
        }
        set(tid, pred as? E)
    }
}

fun<E : ThreadEvent> ExecutionFrontier(nThreads: Int): ExecutionFrontier<E> =
    MutableExecutionFrontier(nThreads)

fun<E : ThreadEvent> MutableExecutionFrontier(nThreads: Int): MutableExecutionFrontier<E> =
    ExecutionFrontierImpl(ArrayMap(nThreads))

fun<E : ThreadEvent> executionFrontierOf(vararg pairs: Pair<ThreadID, E?>): ExecutionFrontier<E> =
    mutableExecutionFrontierOf(*pairs)

fun<E : ThreadEvent> mutableExecutionFrontierOf(vararg pairs: Pair<ThreadID, E?>): MutableExecutionFrontier<E> =
    ExecutionFrontierImpl(ArrayMap(*pairs))

fun<E : ThreadEvent> ExecutionFrontier<E>.copy(): MutableExecutionFrontier<E> {
    check(this is ExecutionFrontierImpl)
    return ExecutionFrontierImpl(threadMap.copy())
}

private class ExecutionFrontierImpl<E : ThreadEvent>(
    override val threadMap: ArrayMap<E?>
): MutableExecutionFrontier<E>

inline fun<reified E : ThreadEvent> ExecutionFrontier<E>.toExecution(): Execution<E> =
    toMutableExecution()

inline fun<reified E : ThreadEvent> ExecutionFrontier<E>.toMutableExecution(): MutableExecution<E> =
    threadIDs.map { tid ->
        tid to (get(tid)?.threadPrefix<E>(inclusive = true) ?: listOf())
    }.let {
        mutableExecutionOf(*it.toTypedArray())
    }

fun MutableExecutionFrontier<AtomicThreadEvent>.cutDanglingRequestEvents(): List<AtomicThreadEvent> {
    val cutEvents = mutableListOf<AtomicThreadEvent>()
    for (threadId in threadIDs) {
        val lastEvent = get(threadId) ?: continue
        if (lastEvent.label.isRequest) {
            lastEvent.parent?.label?.ensure { !it.isRequest }
            set(threadId, lastEvent.parent)
            cutEvents.add(lastEvent)
        }
    }
    return cutEvents
}