/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2024 JetBrains s.r.o.
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
import kotlin.collections.mapValues

interface EventAggregator {
    fun aggregate(events: List<AtomicThreadEvent>): List<List<AtomicThreadEvent>>

    fun label(events: List<AtomicThreadEvent>): EventLabel?
    fun dependencies(events: List<AtomicThreadEvent>, remapping: EventRemapping): List<HyperThreadEvent>

    fun isCoverable(
        events: List<AtomicThreadEvent>,
        covering: Covering<ThreadEvent>,
        clock: VectorClock
    ): Boolean
}

// TODO: unify with Remapping class
typealias EventRemapping = Map<AtomicThreadEvent, HyperThreadEvent>

fun Execution<AtomicThreadEvent>.aggregate(
    aggregator: EventAggregator
): Pair<Execution<HyperThreadEvent>, EventRemapping> {
    val clock = MutableVectorClock(1 + maxThreadID)
    val result = MutableExecution<HyperThreadEvent>(1 + maxThreadID)
    val remapping = mutableMapOf<AtomicThreadEvent, HyperThreadEvent>()
    val aggregated = threadMap.mapValues { (_, events) -> aggregator.aggregate(events) }
    val aggregatedClock = MutableVectorClock(1 + maxThreadID).apply {
        for (i in 0 .. maxThreadID)
            this[i] = 0
    }
    while (!clock.observes(this)) {
        var position = -1
        var found = false
        var events: List<AtomicThreadEvent>? = null
        for ((tid, list) in aggregated.entries) {
            position = aggregatedClock[tid]
            events = list.getOrNull(position) ?: continue
            if (aggregator.isCoverable(events, causalityCovering, clock)) {
                found = true
                break
            }
        }
        if (!found) {
            // error("Cannot aggregate events due to cyclic dependencies")
            break
        }
        check(position >= 0)
        check(events != null)
        check(events.isNotEmpty())
        val tid = events.first().threadId
        val label = aggregator.label(events)
        val parent = result[tid]?.lastOrNull()
        if (label != null) {
            val dependencies = aggregator.dependencies(events, remapping)
            val event = HyperThreadEvent(
                label = label,
                parent = parent,
                dependencies = dependencies,
                events = events,
            )
            result.add(event)
            events.forEach {
                remapping.put(it, event).ensureNull()
            }
        } else if (parent != null) {
            // effectively squash skipped events into previous hyper event,
            // such representation is convenient for causality clock maintenance
            // TODO: make sure dependencies of skipped events are propagated correctly
            events.forEach {
                remapping.put(it, parent).ensureNull()
            }
        }
        clock.increment(tid, events.size)
        aggregatedClock.increment(tid)
    }
    return result to remapping
}

fun Relation<AtomicThreadEvent>.existsLifting() = Relation<HyperThreadEvent> { x, y ->
    x.events.any { ex -> ex !in y.events && y.events.any { ey -> this(ex, ey) } }
}

fun Covering<AtomicThreadEvent>.aggregate(remapping: EventRemapping) = Covering<HyperThreadEvent> { event ->
    event.events
        .flatMap { atomicEvent ->
            this(atomicEvent).mapNotNull { remapping[it] }
        }
        .distinct()
}

fun ActorAggregator(execution: Execution<AtomicThreadEvent>) = object : EventAggregator {

    override fun aggregate(events: List<AtomicThreadEvent>): List<List<AtomicThreadEvent>> {
        var pos = 0
        val result = mutableListOf<List<AtomicThreadEvent>>()
        while (pos < events.size) {
            if ((events[pos].label as? ActorLabel)?.spanKind != SpanLabelKind.Start) {
                result.add(listOf(events[pos++]))
                continue
            }
            val start = events[pos]
            val end = events.subList(fromIndex = start.threadPosition, toIndex = events.size).find {
                (it.label as? ActorLabel)?.spanKind == SpanLabelKind.End
            } ?: break
            check((start.label as ActorLabel).actor == (end.label as ActorLabel).actor)
            result.add(events.subList(fromIndex = start.threadPosition, toIndex = end.threadPosition + 1))
            pos = end.threadPosition + 1
        }
        return result
    }

    override fun label(events: List<AtomicThreadEvent>): EventLabel? {
        val start = events.first().takeIf {
            (it.label as? ActorLabel)?.spanKind == SpanLabelKind.Start
        } ?: return null
        val end = events.last().ensure {
            (it.label as? ActorLabel)?.spanKind == SpanLabelKind.End
        }
        check((start.label as ActorLabel).actor == (end.label as ActorLabel).actor)
        return ActorLabel(SpanLabelKind.Span, (start.label as ActorLabel).threadId, (start.label as ActorLabel).actor)
    }

    override fun dependencies(events: List<AtomicThreadEvent>, remapping: EventRemapping): List<HyperThreadEvent> {
        return events
            .flatMap { event ->
                val causalEvents = execution.threadMap.entries.mapNotNull { (tid, thread) ->
                    if (tid != event.threadId)
                        thread.getOrNull(event.causalityClock[tid])
                    else null
                }
                causalEvents.mapNotNull { remapping[it] }
            }
            // TODO: should use covering here instead of dependencies?
            .filter {
                // take the last event before ActorEnd event
                val last = it.events[it.events.size - 2]
                events.first().causalityClock.observes(last)
            }
            .distinct()
    }

    override fun isCoverable(
        events: List<AtomicThreadEvent>,
        covering: Covering<ThreadEvent>,
        clock: VectorClock
    ): Boolean {
        return covering.firstCoverable(events, clock)
    }

}

fun SynchronizationAlgebra.aggregator() = object : EventAggregator {

    override fun aggregate(events: List<AtomicThreadEvent>): List<List<AtomicThreadEvent>> =
        events.squash { x, y -> synchronizable(x.label, y.label) }

    override fun label(events: List<AtomicThreadEvent>): EventLabel =
        synchronize(events).ensureNotNull()

    override fun dependencies(events: List<AtomicThreadEvent>, remapping: EventRemapping): List<HyperThreadEvent> {
        return events
            // TODO: should use covering here instead of dependencies?
            .flatMap { event -> event.dependencies.mapNotNull { remapping[it] } }
            .distinct()
    }

    override fun isCoverable(
        events: List<AtomicThreadEvent>,
        covering: Covering<ThreadEvent>,
        clock: VectorClock
    ): Boolean {
        return covering.allCoverable(events, clock)
    }

}

private val ReceiveAggregationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? =
        if (!label.isSpanLabel && (label.isRequest || label.isResponse)) SynchronizationType.Binary else null

    override fun synchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        label.isSpanLabel || other.isSpanLabel ->
            null
        label.isRequest && other.isResponse && other.isValidResponse(label) ->
            other.getReceive()
        else -> null
    }

}

fun ThreadStartLabel.getReceive(): EventLabel =
    copy(kind = LabelKind.Receive)

fun ThreadJoinLabel.getReceive(): EventLabel? =
    if (isUnblocked) copy(kind = LabelKind.Receive) else null

fun ReadAccessLabel.getReceive(): ReadAccessLabel =
    copy(kind = LabelKind.Receive)

fun ReadModifyWriteAccessLabel.getReceive(): ReadModifyWriteAccessLabel =
    copy(kind = LabelKind.Receive)

fun ParkLabel.getReceive(): EventLabel =
    copy(kind = LabelKind.Receive)

fun CoroutineSuspendLabel.getReceive(): EventLabel =
    copy(kind = LabelKind.Receive)

fun EventLabel.getReceive(): EventLabel? = when (this) {
    is ThreadStartLabel             -> getReceive()
    is ThreadJoinLabel              -> getReceive()
    is ReadAccessLabel              -> getReceive()
    is ReadModifyWriteAccessLabel   -> getReceive()
    is ParkLabel                    -> getReceive()
    is CoroutineSuspendLabel        -> getReceive()
    else                            -> null
}

private val MemoryAccessAggregationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when(label) {
        is MemoryAccessLabel        -> SynchronizationType.Binary
        else                        -> null
    }

    override fun synchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        // read request synchronizes with read response
        label is ReadAccessLabel && label.isRequest && other is ReadAccessLabel && other.isResponse
                && other.isValidResponse(label) ->
            other.getReceive()

        // exclusive read response/receive synchronizes with exclusive write
        label is ReadAccessLabel && (label.isResponse || label.isReceive) && other is WriteAccessLabel ->
            label.getReadModifyWrite(other)

        // exclusive read request synchronizes with read-modify-write response
        label is ReadAccessLabel && label.isRequest && other is ReadModifyWriteAccessLabel && other.isResponse
                && label.isValidReadPart(other) ->
            other.getReceive()

        else -> null
    }

}

val MutexAggregationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when (label) {
        is MutexLabel   -> SynchronizationType.Binary
        else            -> null
    }

    override fun synchronize(label: EventLabel, other: EventLabel): EventLabel? = when {
        // unlock label can be merged with the subsequent wait request
        label is UnlockLabel && other is WaitLabel && other.isRequest && !other.isUnlocking
                && label.mutexID == other.mutexID ->
            WaitLabel(LabelKind.Request, label.mutexID, isUnlocking = true)

        // wait response label can be merged with the subsequent lock request
        label is WaitLabel && label.isResponse && !label.isLocking && other is LockLabel && other.isRequest
                && label.mutexID == other.mutexID ->
            WaitLabel(LabelKind.Response, label.mutexID, isLocking = true)

        // TODO: do we need to merge lock request/response (?)
        else -> null
    }

}

val ThreadAggregationAlgebra = object : SynchronizationAlgebra {

    override fun syncType(label: EventLabel): SynchronizationType? = when (label) {
        is ActorLabel           -> null
        is MemoryAccessLabel    -> MemoryAccessAggregationAlgebra.syncType(label)
        is MutexLabel           -> MutexAggregationAlgebra.syncType(label)
        else                    -> ReceiveAggregationAlgebra.syncType(label)
    }

    override fun synchronize(label: EventLabel, other: EventLabel): EventLabel? = when (label) {
        is ActorLabel           -> null
        is MemoryAccessLabel    -> MemoryAccessAggregationAlgebra.synchronize(label, other)
        is MutexLabel           -> MutexAggregationAlgebra.synchronize(label, other)
        else                    -> ReceiveAggregationAlgebra.synchronize(label, other)
    }

}