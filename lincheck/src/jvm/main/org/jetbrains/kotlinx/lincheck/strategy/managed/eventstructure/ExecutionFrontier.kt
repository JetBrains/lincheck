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

import org.jetbrains.kotlinx.lincheck.util.*
import org.jetbrains.lincheck.util.max
import org.jetbrains.lincheck.util.mergeReduce
import org.jetbrains.lincheck.util.orEqual
import org.jetbrains.lincheck.util.refine
import kotlin.to

// TODO: implement VectorClock interface??
interface ExecutionFrontier<out E : ThreadEvent> {
    val threadMap: ThreadMap<E?>
}

interface MutableExecutionFrontier<E : ThreadEvent> : ExecutionFrontier<E> {
    override val threadMap: MutableThreadMap<E?>
}

val ExecutionFrontier<*>.threadIDs: Set<ThreadId>
    get() = threadMap.keys

val<E : ThreadEvent> ExecutionFrontier<E>.events: List<E>
    get() = threadMap.values.filterNotNull()

operator fun<E : ThreadEvent> ExecutionFrontier<E>.get(tid: ThreadId): E? =
    threadMap[tid]

fun ExecutionFrontier<*>.getPosition(tid: ThreadId): Int =
    get(tid)?.threadPosition ?: -1

fun ExecutionFrontier<*>.getNextPosition(tid: ThreadId): Int =
    1 + getPosition(tid)

operator fun<E : ThreadEvent> ExecutionFrontier<E>.contains(event: E): Boolean {
    val lastEvent = get(event.threadId) ?: return false
    return programOrder.orEqual(event, lastEvent)
}

operator fun<E : ThreadEvent> MutableExecutionFrontier<E>.set(tid: ThreadId, event: E?) {
    require(tid == (event?.threadId ?: tid))
    threadMap[tid] = event
}

fun<E : ThreadEvent> MutableExecutionFrontier<E>.update(event: E) {
    check(event.parent == get(event.threadId))
    set(event.threadId, event)
}

fun<E : ThreadEvent> MutableExecutionFrontier<E>.merge(other: ExecutionFrontier<E>) {
    threadMap.mergeReduce(other.threadMap as MutableMap<ThreadId, E?>) { x, y -> when {
        x == null -> y
        y == null -> x
        else -> programOrder.max(x, y)
    }}
}

fun<E : ThreadEvent> ExecutionFrontier<E>.isBlockedDanglingRequest(event: E): Boolean =
    event.label.isRequest &&
    event.label.isBlocking &&
    event == this[event.threadId]

fun <E : ThreadEvent> ExecutionFrontier<E>.getDanglingRequests(): List<E>  {
    return threadMap.mapNotNull { (_, lastEvent) ->
        lastEvent?.takeIf { it.label.isRequest && !it.label.isSpanLabel }
    }
}

inline fun <reified E : ThreadEvent> MutableExecutionFrontier<E>.cut(event: E) {
    // TODO: optimize for a case of a single event
    cut(listOf(event))
}

inline fun <reified E : ThreadEvent> MutableExecutionFrontier<E>.cut(events: List<E>) {
    if (events.isEmpty())
        return
    // TODO: optimize --- extract sublist of maximal events having no causal successors,
    //   to remove them faster without the need to compute vector clocks
    threadMap.forEach { (tid, lastEvent) ->
        // find the program-order latest event, not observing any of the cut events
        // TODO: optimize --- transform events into vector clock
        // TODO: optimize using binary search
        val pred = lastEvent?.pred(inclusive = true) {
            (it is E) && !events.any { cutEvent ->
                it.causalityClock.observes(cutEvent.threadId, cutEvent.threadPosition)
            }
        }
        set(tid, pred as? E)
    }
}

fun<E : ThreadEvent> ExecutionFrontier(): ExecutionFrontier<E> =
    MutableExecutionFrontier()

fun<E : ThreadEvent> MutableExecutionFrontier(): MutableExecutionFrontier<E> =
    ExecutionFrontierImpl(mutableMapOf())

fun<E : ThreadEvent> executionFrontierOf(vararg pairs: Pair<ThreadId, E?>): ExecutionFrontier<E> =
    mutableExecutionFrontierOf(*pairs)

fun<E : ThreadEvent> mutableExecutionFrontierOf(vararg pairs: Pair<ThreadId, E?>): MutableExecutionFrontier<E> =
    ExecutionFrontierImpl(mutableThreadMapOf(*pairs))

fun<E : ThreadEvent> ExecutionFrontier<E>.copy(): MutableExecutionFrontier<E> {
    check(this is ExecutionFrontierImpl)
    return ExecutionFrontierImpl(threadMap.toMutableMap()) // NOTE: This replaces a copy operation. I hope that this is actually a copy
}

private class ExecutionFrontierImpl<E : ThreadEvent>(
    override val threadMap: MutableThreadMap<E?>
): MutableExecutionFrontier<E>

inline fun<reified E : ThreadEvent> ExecutionFrontier<E>.toExecution(): Execution<E> =
    toMutableExecution()

inline fun<reified E : ThreadEvent> ExecutionFrontier<E>.toMutableExecution(): MutableExecution<E> =
    threadIDs.map { tid ->
        val events = get(tid)?.threadPrefix(inclusive = true)
        tid to (events?.refine<E>() ?: listOf())
    }.let {
        mutableExecutionOf(*it.toTypedArray())
    }