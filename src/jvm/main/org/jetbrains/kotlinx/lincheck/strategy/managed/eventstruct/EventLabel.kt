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

// TODO: maybe call it DualLabelKind?
enum class LabelKind { Request, Response, Total }

enum class SynchronizationKind { Binary, Barrier }

// TODO: make a constant for default thread ID
abstract class EventLabel(
    open val threadId: Int = 0,
    open val kind: LabelKind,
    open val syncKind: SynchronizationKind,
) {

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
    open infix fun synchronize(label: EventLabel): EventLabel? =
        when {
            (label is EmptyLabel) -> this
            else -> null
        }

    // TODO: rename?
    open infix fun aggregate(label: EventLabel): EventLabel? =
        when {
            (label is EmptyLabel) -> this
            else -> null
        }

    fun aggregatesWith(label: EventLabel): Boolean =
        aggregate(label) != null

    val isRequest: Boolean
        get() = (kind == LabelKind.Request)

    val isResponse: Boolean
        get() = (kind == LabelKind.Response)

    val isTotal: Boolean
        get() = (kind == LabelKind.Total)

    open val isCompleted: Boolean = true

    val isCompetedResponse: Boolean
        get() = isCompleted && isResponse

    val isBinarySynchronizing: Boolean
        get() = (syncKind == SynchronizationKind.Binary)

    val isBarrierSynchronizing: Boolean
        get() = (syncKind == SynchronizationKind.Barrier)

    val isThreadInitializer: Boolean
        get() = isRequest && (this is ThreadStartLabel)

    fun isMemoryAccessTo(memoryLocationId: Int): Boolean =
        this is MemoryAccessLabel && memId == memoryLocationId
}

// TODO: rename to BarrierRaceException?
class InvalidBarrierSynchronizationException(message: String): Exception(message)

data class EmptyLabel(
    override val threadId: Int = 0
): EventLabel(threadId, LabelKind.Total, SynchronizationKind.Binary) {
    override fun synchronize(label: EventLabel) = label
}

abstract class ThreadLabel(
    threadId: Int,
    kind: LabelKind,
    syncKind: SynchronizationKind
): EventLabel(threadId, kind, syncKind)

data class ThreadForkLabel(
    override val threadId: Int,
    val forkThreadIds: Set<Int>,
): ThreadLabel(threadId, LabelKind.Total, SynchronizationKind.Binary) {
    override fun synchronize(label: EventLabel): EventLabel? {
        if (label is ThreadStartLabel && label.isRequest && label.threadId in forkThreadIds)
            return ThreadStartLabel(
                threadId = label.threadId,
                kind = LabelKind.Response
            )
        return super.synchronize(label)
    }
}

data class ThreadStartLabel(
    override val threadId: Int,
    override val kind: LabelKind
): ThreadLabel(threadId, kind, SynchronizationKind.Binary) {

    override fun synchronize(label: EventLabel): EventLabel? {
        if (label is ThreadForkLabel)
            return label.synchronize(this)
        return super.synchronize(label)
    }

    override fun aggregate(label: EventLabel): EventLabel? {
        if (isRequest && label.isResponse &&
            label is ThreadStartLabel && threadId == label.threadId)
            return ThreadStartLabel(threadId, LabelKind.Total)
        return super.aggregate(label)
    }
}

data class ThreadFinishLabel(
    override val threadId: Int,
    val finishedThreadIds: Set<Int> = setOf(threadId)
): ThreadLabel(threadId, LabelKind.Total, SynchronizationKind.Barrier) {

    override fun synchronize(label: EventLabel): EventLabel? {
        // TODO: handle cases of invalid synchronization:
        //  - when there are multiple ThreadFinish labels with the same thread id
        //  - when thread finishes outside of matching ThreadFork/ThreadJoin scope
        //  In order to handle the last case we need to add `scope` parameter to Thread labels?.
        //  Throw `InvalidBarrierSynchronizationException` in these cases.
        if (label is ThreadJoinLabel && label.joinThreadIds.containsAll(finishedThreadIds))
            return ThreadJoinLabel(
                threadId = label.threadId,
                kind = LabelKind.Response,
                joinThreadIds = label.joinThreadIds - finishedThreadIds,
            )
        if (label is ThreadFinishLabel)
            return ThreadFinishLabel(
                threadId = threadId,
                finishedThreadIds = finishedThreadIds + label.finishedThreadIds
            )
        return super.synchronize(label)
    }
}

data class ThreadJoinLabel(
    override val threadId: Int,
    override val kind: LabelKind,
    val joinThreadIds: Set<Int>,
): ThreadLabel(threadId, kind, SynchronizationKind.Barrier) {

    override fun synchronize(label: EventLabel): EventLabel? {
        if (label is ThreadFinishLabel)
            return label.synchronize(this)
        return super.synchronize(label)
    }

    override val isCompleted: Boolean =
        isResponse implies joinThreadIds.isEmpty()

    override fun aggregate(label: EventLabel): EventLabel? {
        if (isRequest && label.isResponse &&
            label is ThreadJoinLabel && threadId == label.threadId &&
            label.joinThreadIds.isEmpty())
            return ThreadJoinLabel(threadId, LabelKind.Total, setOf())
        return super.aggregate(label)
    }
}

enum class MemoryAccessKind { Read, Write }

data class MemoryAccessLabel(
    override val threadId: Int,
    override val kind: LabelKind,
    val accessKind: MemoryAccessKind,
    val typeDesc: String,
    val memId: Int,
    val value: Any?,
    val isExclusive: Boolean = false
): EventLabel(threadId, kind, SynchronizationKind.Binary) {

    val isRead: Boolean = (accessKind == MemoryAccessKind.Read)

    val isWrite: Boolean = (accessKind == MemoryAccessKind.Write)

    init {
        require((isRead && isRequest) implies (value == null))
        require(isWrite implies isTotal)
    }

    override fun synchronize(label: EventLabel): EventLabel? {
        // TODO: perform dynamic type-check of `typeDesc`
        val writeReadSync = { writeLabel : MemoryAccessLabel, readLabel : MemoryAccessLabel ->
            MemoryAccessLabel(
                threadId = readLabel.threadId,
                kind = LabelKind.Response,
                accessKind = MemoryAccessKind.Read,
                typeDesc = writeLabel.typeDesc,
                memId = writeLabel.memId,
                value = writeLabel.value,
                isExclusive = readLabel.isExclusive,
            )
        }
        if ((label is MemoryAccessLabel) && (memId == label.memId)) {
            if (isWrite && label.isRead && label.isRequest)
                return writeReadSync(this, label)
            if (label.isWrite && isRead && isRequest)
                return writeReadSync(label, this)
        }
        return super.synchronize(label)
    }

    override fun aggregate(label: EventLabel): EventLabel? {
        // TODO: perform dynamic type-check of `typeDesc`
        if (isRead && isRequest && value == null
            && label is MemoryAccessLabel && label.isRead && label.isResponse
            && threadId == label.threadId && memId == label.memId && isExclusive == label.isExclusive)
            return MemoryAccessLabel(
                threadId = threadId,
                kind = LabelKind.Total,
                accessKind = MemoryAccessKind.Read,
                typeDesc = typeDesc,
                memId = memId,
                value = label.value,
                isExclusive = isExclusive
            )
        return super.aggregate(label)
    }
}
