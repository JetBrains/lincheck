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

/**
 * Hyper event is a composite event consisting of multiple atomic events.
 * It allows to view subset of events of an execution as an atomic event by itself.
 * Some notable examples of hyper events are listed below:
 * - pair of consecutive request and response events of the same operation
 *   can be viewed as a composite receive event;
 * - pair of exclusive read and write events of the same atomic operation
 *   can be viewed as a composite read-modify-write event;
 * - all the events between lock acquire and lock release events
 *   can be viewed as a composite critical section event;
 * for other examples see subclasses of this class.
 *
 * We support only sequential hyper events --- that is set of events
 * totally ordered by some criterion.
 *
 * This class of events is called "hyper" after term "hyper pomsets" from [1].
 *
 * [1] Brunet, Paul, and David Pym.
 *    "Pomsets with Boxes: Protection, Separation, and Locality in Concurrent Kleene Algebra."
 *    5th International Conference on Formal Structures for Computation and Deduction. 2020.
 *
 */
abstract class HyperEvent(val events: List<Event>) {

    open val dependencies: List<Event> =
        mutableListOf<Event>().apply {
            events.forEach { addAll(it.dependencies) }
            // retainAll { it !in this }
        }
}

fun Execution.nextAtomicEvent(iThread: Int, pos: Int, replaying: Boolean = false): HyperEvent? {
    return get(iThread)?.nextAtomicEvent(pos, replaying)
}

fun SortedArrayList<Event>.nextAtomicEvent(pos: Int, replaying: Boolean): HyperEvent? {
    val event = getOrNull(pos) ?: return null
    return when(event.label) {
        is ThreadEventLabel -> nextAtomicThreadEvent(event, replaying)
        is MemoryAccessLabel -> nextAtomicMemoryAccessEvent(event, replaying)
        else -> nextAtomicEventDefault(event)
    }
}

private fun SortedArrayList<Event>.nextAtomicEventDefault(firstEvent: Event): HyperEvent {
    check(firstEvent.label.isSend)
    return SingletonEvent(firstEvent)
}

class SingletonEvent(event: Event) : HyperEvent(listOf(event)) {

    val event: Event
        get() = events[0]

    override val dependencies: List<Event>
        get() = event.dependencies

}

/* ======== Send and Receive Events  ======== */

fun SortedArrayList<Event>.nextAtomicSendOrReceiveEvent(firstEvent: Event, replaying: Boolean): HyperEvent {
    if (firstEvent.label.isSend)
        return SingletonEvent(firstEvent)
    check(firstEvent.label.isRequest)
    val requestEvent = firstEvent
    val responseEvent = getOrNull(1 + requestEvent.threadPosition)
        ?: return SingletonEvent(requestEvent)
    check(responseEvent.label.isResponse)
    return ReceiveEvent(requestEvent, responseEvent, replaying)
}

class ReceiveEvent(
    request: Event,
    response: Event,
    replaying: Boolean = false,
) : HyperEvent(listOf(request, response)) {

    init {
        require(request.label.isRequest)
        require(response.label.isResponse)
        require(response.parent == request)
        check(requestPart !in responsePart.dependencies)
    }

    val requestPart: Event
        get() = events[0]

    val responsePart: Event
        get() = events[1]

    override val dependencies: List<Event> =
        // optimization for the common case when request part has no dependencies
        if (requestPart.dependencies.isEmpty())
            responsePart.dependencies
        else requestPart.dependencies + responsePart.dependencies

}

/* ======== Thread Event  ======== */

fun SortedArrayList<Event>.nextAtomicThreadEvent(firstEvent: Event, replaying: Boolean): HyperEvent {
    require(firstEvent.label is ThreadEventLabel)
    return nextAtomicSendOrReceiveEvent(firstEvent, replaying)
}

/* ======== Memory Accesses  ======== */

fun SortedArrayList<Event>.nextAtomicMemoryAccessEvent(firstEvent: Event, replaying: Boolean): HyperEvent {
    require(firstEvent.label is MemoryAccessLabel)
    when(firstEvent.label) {
        is WriteAccessLabel -> return SingletonEvent(firstEvent)
        is ReadAccessLabel -> {
            check(firstEvent.label.isRequest)
            val readRequestEvent = firstEvent
            val readResponseEvent = getOrNull(1 + readRequestEvent.threadPosition)
                ?: return SingletonEvent(readRequestEvent)
            check(readResponseEvent.label.isResponse && readResponseEvent.label is ReadAccessLabel)
            if (!readResponseEvent.label.isExclusive) {
                return ReceiveEvent(readRequestEvent, readResponseEvent, replaying)
            }
            val writeEvent = getOrNull(1 + readResponseEvent.threadPosition)
                ?.takeIf { it.label is WriteAccessLabel && it.label.isExclusive }
                ?: return ReceiveEvent(readRequestEvent, readResponseEvent)
            return ReadModifyWriteEvent(readRequestEvent, readResponseEvent, writeEvent, replaying)
        }
    }
}

class ReadModifyWriteEvent(
    readRequest: Event,
    readResponse: Event,
    write: Event,
    replaying: Boolean = false,
) : HyperEvent(listOf(readRequest, readResponse, write)) {

    init {
        require(readRequest.label.isRequest)
        require(readResponse.label.isResponse)
        require(readRequest.label is ReadAccessLabel && readRequest.label.isExclusive)
        require(readResponse.label is ReadAccessLabel && readResponse.label.isExclusive)
        require(write.label is WriteAccessLabel && readRequest.label.isExclusive)
        require(write.parent == readResponse && readResponse.parent == readRequest)
        require(readRequest.dependencies.isEmpty() && write.dependencies.isEmpty())

        // when replaying, locations do not necessarily match
        if (!replaying) {
            // TODO: make a function checking for labels compatibility
            require(readRequest.label.location == readResponse.label.location)
            require(readResponse.label.location == write.label.location)
        }

        check(readRequestPart !in readResponsePart.dependencies)

    }

    val readRequestPart: Event
        get() = events[0]

    val readResponsePart: Event
        get() = events[1]

    val writeSendPart: Event
        get() = events[2]

    override val dependencies: List<Event>
        get() = readResponsePart.dependencies

}