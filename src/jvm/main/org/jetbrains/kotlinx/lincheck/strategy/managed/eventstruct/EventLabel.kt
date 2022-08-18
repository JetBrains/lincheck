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

    abstract val isRequest: Boolean

    abstract val isResponse: Boolean

    abstract val isTotal: Boolean

    // TODO: better name?
    abstract val isCompleted: Boolean

    abstract val isBinarySynchronizing: Boolean

    abstract val isBarrierSynchronizing: Boolean

    val isThreadInitializer: Boolean
        get() = isRequest && (this is ThreadStartLabel)

}

// TODO: use of word `Atomic` here is perhaps misleading?
//   Maybe rename it to `SingletonEventLabel` or something similar?
//   At least document the meaning of `Atomic` here.
abstract class AtomicEventLabel(
    threadId: Int,
    open val kind: LabelKind,
    open val syncKind: SynchronizationKind,
    override val isCompleted: Boolean,
): EventLabel(threadId) {

    override val isRequest: Boolean =
        (kind == LabelKind.Request)

    override val isResponse: Boolean =
        (kind == LabelKind.Response)

    override val isTotal: Boolean =
        (kind == LabelKind.Total)

    override val isBinarySynchronizing: Boolean =
        (syncKind == SynchronizationKind.Binary)

    override val isBarrierSynchronizing: Boolean =
        (syncKind == SynchronizationKind.Barrier)

}

// TODO: rename to BarrierRaceException?
class InvalidBarrierSynchronizationException(message: String): Exception(message)

data class EmptyLabel(
    override val threadId: Int = 0
): AtomicEventLabel(
    threadId = threadId,
    kind = LabelKind.Total,
    syncKind = SynchronizationKind.Binary,
    isCompleted = true
) {
    override fun synchronize(label: EventLabel) = label

    override fun aggregate(label: EventLabel) = label
}

abstract class ThreadLabel(
    threadId: Int,
    kind: LabelKind,
    syncKind: SynchronizationKind,
    isCompleted: Boolean
): AtomicEventLabel(threadId, kind, syncKind, isCompleted)

data class ThreadForkLabel(
    override val threadId: Int,
    val forkThreadIds: Set<Int>,
): ThreadLabel(
    threadId = threadId,
    kind = LabelKind.Total,
    syncKind = SynchronizationKind.Binary,
    isCompleted = true,
) {
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
): ThreadLabel(
    threadId = threadId,
    kind = kind,
    syncKind = SynchronizationKind.Binary,
    isCompleted = true,
) {

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
): ThreadLabel(
    threadId = threadId,
    kind = LabelKind.Total,
    syncKind = SynchronizationKind.Barrier,
    isCompleted = true,
) {

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
): ThreadLabel(
    threadId = threadId,
    kind = kind,
    syncKind = SynchronizationKind.Barrier,
    isCompleted = (kind == LabelKind.Response) implies joinThreadIds.isEmpty()
) {

    override fun synchronize(label: EventLabel): EventLabel? {
        if (label is ThreadFinishLabel)
            return label.synchronize(this)
        return super.synchronize(label)
    }

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
): AtomicEventLabel(
    threadId = threadId,
    kind = kind,
    syncKind = SynchronizationKind.Binary,
    isCompleted = true
) {

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
        if (isRead && isTotal && isExclusive
            && label is MemoryAccessLabel && label.isWrite && label.isExclusive
            && threadId == label.threadId && memId == label.memId)
            return ReadModifyWriteMemoryAccessLabel(this, label)
        return super.aggregate(label)
    }
}

// TODO: rename?
abstract class CompoundEventLabel(
    threadId: Int,
    val labels: List<AtomicEventLabel>,
) : EventLabel(threadId) {

    override val isRequest: Boolean =
        labels.any { it.isRequest }

    override val isResponse: Boolean =
        labels.any { it.isResponse }

    override val isTotal: Boolean =
        labels.any { it.isTotal }

    override val isCompleted: Boolean =
        labels.all { it.isCompleted }

    override val isBinarySynchronizing: Boolean =
        labels.any { it.isBinarySynchronizing }

    override val isBarrierSynchronizing: Boolean =
        labels.any { it.isBinarySynchronizing }

}

// TODO: MemoryAccessLabel and ReadModifyWriteMemoryAccessLabel likely
//   should have common ancestor in the hierarchy?
data class ReadModifyWriteMemoryAccessLabel(
    val readLabel: MemoryAccessLabel,
    val writeLabel: MemoryAccessLabel,
) : CompoundEventLabel(readLabel.threadId, listOf(readLabel, writeLabel)) {

    init {
        require(readLabel.isRead && readLabel.isExclusive)
        require(writeLabel.isWrite && writeLabel.isExclusive)
        require(readLabel.threadId == writeLabel.threadId)
        require(readLabel.memId == writeLabel.memId)
        // TODO: also check types
        require(readLabel.isTotal && writeLabel.isTotal)
    }

    val typeDesc: String = readLabel.typeDesc

    val memId: Int = readLabel.memId

    val readValue: Any? = readLabel.value

    val writeValue: Any? = writeLabel.value

    override fun synchronize(label: EventLabel): EventLabel? {
        return if (label is EmptyLabel) this else writeLabel.synchronize(label)
    }

    override fun aggregate(label: EventLabel): EventLabel? {
        return super.aggregate(label)
    }

}