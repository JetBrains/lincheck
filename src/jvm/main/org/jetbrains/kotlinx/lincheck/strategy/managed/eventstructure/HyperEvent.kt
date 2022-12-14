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
            retainAll { it !in this }
        }
}

fun Execution.nextAtomicEvent(iThread: Int, pos: Int, replaying: Boolean = false): HyperEvent? {
    return get(iThread)?.nextAtomicEvent(pos, replaying)
}

fun List<Event>.nextAtomicEvent(pos: Int, replaying: Boolean): HyperEvent? {
    val event = getOrNull(pos) ?: return null
    return when(event.label) {
        is ThreadEventLabel -> nextAtomicThreadEvent(event, replaying)
        is MemoryAccessLabel -> nextAtomicMemoryAccessEvent(event, replaying)
        is MutexLabel -> nextAtomicMutexEvent(event, replaying)
        is ParkingEventLabel -> nextAtomicParkingEvent(event, replaying)
        else -> SingletonEvent(event)
    }
}

class SingletonEvent(event: Event) : HyperEvent(listOf(event)) {

    val event: Event
        get() = events[0]

    override val dependencies: List<Event>
        get() = event.dependencies

}

/* ======== Send and Receive Events  ======== */

fun List<Event>.nextAtomicSendOrReceiveEvent(firstEvent: Event, replaying: Boolean): HyperEvent {
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
        require(response.isValidResponse(request, replaying))
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

fun List<Event>.nextAtomicThreadEvent(firstEvent: Event, replaying: Boolean): HyperEvent {
    require(firstEvent.label is ThreadEventLabel)
    return nextAtomicSendOrReceiveEvent(firstEvent, replaying)
}

/* ======== Memory Accesses  ======== */

fun List<Event>.nextAtomicMemoryAccessEvent(firstEvent: Event, replaying: Boolean): HyperEvent {
    require(firstEvent.label is MemoryAccessLabel)
    return when(firstEvent.label) {
        is WriteAccessLabel -> SingletonEvent(firstEvent)

        is ReadAccessLabel -> {
            check(firstEvent.label.isRequest)
            val readRequestEvent = firstEvent
            readRequestEvent.label as ReadAccessLabel

            val readResponseEvent = getOrNull(1 + readRequestEvent.threadPosition)
                ?: return SingletonEvent(readRequestEvent)

            check(readResponseEvent.label.isResponse && readResponseEvent.label is ReadAccessLabel)
            if (!readResponseEvent.label.isExclusive) {
                return ReceiveEvent(readRequestEvent, readResponseEvent, replaying)
            }

            val writeEvent = getOrNull(1 + readResponseEvent.threadPosition)
                ?.takeIf { it.label is WriteAccessLabel && it.label.isExclusive }
                ?: return ReceiveEvent(readRequestEvent, readResponseEvent, replaying)

            ReadModifyWriteEvent(readRequestEvent, readResponseEvent, writeEvent, replaying)
        }
    }
}

class ReadModifyWriteEvent(
    readRequest: Event,
    readResponse: Event,
    writeSend: Event,
    replaying: Boolean = false,
) : HyperEvent(listOf(readRequest, readResponse, writeSend)) {

    init {
        require(readResponse.isValidResponse(readRequest, relaxedCheck = replaying))
        require(writeSend.isWritePartOfAtomicUpdate(readResponse, relaxedCheck = replaying))
        check(readRequest.dependencies.isEmpty() && writeSend.dependencies.isEmpty())
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

/* ======== Mutex Event  ======== */

fun List<Event>.nextAtomicMutexEvent(firstEvent: Event, replaying: Boolean): HyperEvent {
    require(firstEvent.label is MutexLabel)
    return when(firstEvent.label) {
        is LockLabel -> SingletonEvent(firstEvent)

        is UnlockLabel -> {
            val unlockEvent = firstEvent
            unlockEvent.label as UnlockLabel

            val waitRequestEvent = getOrNull(1 + unlockEvent.threadPosition)
                ?.takeIf { it.label is WaitLabel && it.label.operatesOnSameMutex(unlockEvent.label, replaying) }
                ?: return SingletonEvent(unlockEvent)

            UnlockAndWait(unlockEvent, waitRequestEvent, replaying)
        }

        is WaitLabel -> {
            val waitResponseEvent = firstEvent
            waitResponseEvent.label as WaitLabel
            check(waitResponseEvent.label.isResponse)

            val lockRequestEvent = getOrNull(1 + waitResponseEvent.threadPosition)
                ?: return SingletonEvent(waitResponseEvent)
            WakeUpAndTryLock(waitResponseEvent, lockRequestEvent, replaying)
        }

        is NotifyLabel -> SingletonEvent(firstEvent)
    }
}

class UnlockAndWait(
    unlock: Event,
    waitRequest: Event,
    replaying: Boolean = false,
) : HyperEvent(listOf(unlock, waitRequest)) {

    init {
        require(unlock.label is UnlockLabel)
        require(waitRequest.label is WaitLabel && waitRequest.label.isRequest)
        require(waitRequest.label.operatesOnSameMutex(unlock.label, replaying))
        require(waitRequest.parent == unlock)
        check(unlock.dependencies.isEmpty() && waitRequest.dependencies.isEmpty())
    }

    val unlockPart: Event
        get() = events[0]

    val waitRequestPart: Event
        get() = events[1]

    override val dependencies: List<Event> = listOf()

}

class WakeUpAndTryLock(
    waitResponse: Event,
    lockRequest: Event,
    replaying: Boolean = false,
) : HyperEvent(listOf(waitResponse, lockRequest)) {

    init {
        require(waitResponse.label is WaitLabel && waitResponse.label.isResponse)
        require(lockRequest.label is LockLabel && lockRequest.label.isRequest)
        require(lockRequest.label.operatesOnSameMutex(waitResponse.label, replaying))
        require(lockRequest.parent == waitResponse)
        check(lockRequest.dependencies.isEmpty())
    }

    val waitResponsePart: Event
        get() = events[0]

    val lockRequestPart: Event
        get() = events[1]

    val lockResponsePart: Event
        get() = events[2]

    override val dependencies: List<Event> = waitResponse.dependencies

}

fun List<Event>.nextCriticalSectionEvents(firstEvent: Event): List<CriticalSectionEvent> {
    require(firstEvent.label is LockLabel && firstEvent.label.isRequest)
    var pos = firstEvent.threadPosition
    var sectionStart = firstEvent
    val sections = mutableListOf<CriticalSectionEvent>()
    val mutex = firstEvent.label.mutex
    // TODO: handle non-reentrant locks too
    var counter = 0
    while (pos < size) {
        val event = get(pos++)
        if (event.label !is MutexLabel || event.label.mutex != mutex)
            continue
        when (event.label) {
            is LockLabel -> if (event.label.isResponse) {
                // TODO: check that response's parent is actually a previous event in the list?
                check(event.isValidResponse())
                counter++
            }

            is UnlockLabel -> {
                counter--
                if (counter == 0) {
                    sections.add(CriticalSectionEvent(
                        subList(sectionStart.threadPosition, pos)
                    ))
                    break
                }
            }

            is WaitLabel -> {
                if (event.label.isRequest) {
                    sections.add(CriticalSectionEvent(
                        subList(sectionStart.threadPosition, pos)
                    ))
                }
                if (event.label.isResponse) {
                    check(event.isValidResponse())
                    sectionStart = event
                }
            }

            is NotifyLabel -> continue
        }
    }
    return sections
}

class CriticalSectionEvent(events: List<Event>) : HyperEvent(events) {

    init {
        require(events.size >= 2)
        val startEvent = events.first()
        val finishEvent = events.last()
        require(isValidStartEvent(startEvent))
        require(isValidFinishEvent(finishEvent))
        if (startEvent.label.isRequest) {
            val responseEvent = events[1]
            require(responseEvent.isValidResponse(startEvent))
        }
    }

    val isCompleted: Boolean
        get() = !isAborted

    val isAborted: Boolean
        get() = events.last().label is ThreadFinishLabel

    companion object {

        fun isValidStartEvent(event: Event): Boolean =
            (event.label is LockLabel && event.label.isRequest) ||
            (event.label is WaitLabel && event.label.isResponse)

        fun isValidFinishEvent(event: Event): Boolean =
            (event.label is UnlockLabel) ||
            (event.label is WaitLabel && event.label.isRequest) ||
            (event.label is ThreadFinishLabel)

        fun checkLockNestedness(mutex: Any, events: List<Event>): Boolean {
            var counter = 0
            for (event in events) {
                when(event.label) {
                    is LockLabel    -> if (event.label.mutex == mutex) counter++
                    is UnlockLabel  -> if (event.label.mutex == mutex) counter--
                }
            }
            return (counter == 0)
        }

    }

}

/* ======== Park and Unpark Events  ======== */

fun List<Event>.nextAtomicParkingEvent(firstEvent: Event, replaying: Boolean): HyperEvent {
    require(firstEvent.label is ParkingEventLabel)
    return SingletonEvent(firstEvent)
}