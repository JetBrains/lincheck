/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

import org.jetbrains.kotlinx.lincheck.util.*

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
    threadMap.mergeReduce(other.threadMap) { x, y -> when {
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

fun<E : ThreadEvent> ExecutionFrontier(nThreads: Int): ExecutionFrontier<E> =
    MutableExecutionFrontier(nThreads)

fun<E : ThreadEvent> MutableExecutionFrontier(nThreads: Int): MutableExecutionFrontier<E> =
    ExecutionFrontierImpl(ArrayIntMap(nThreads))

fun<E : ThreadEvent> executionFrontierOf(vararg pairs: Pair<ThreadId, E?>): ExecutionFrontier<E> =
    mutableExecutionFrontierOf(*pairs)

fun<E : ThreadEvent> mutableExecutionFrontierOf(vararg pairs: Pair<ThreadId, E?>): MutableExecutionFrontier<E> =
    ExecutionFrontierImpl(ArrayIntMap(*pairs))

fun<E : ThreadEvent> ExecutionFrontier<E>.copy(): MutableExecutionFrontier<E> {
    check(this is ExecutionFrontierImpl)
    return ExecutionFrontierImpl(threadMap.copy())
}

private class ExecutionFrontierImpl<E : ThreadEvent>(
    override val threadMap: ArrayIntMap<E?>
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