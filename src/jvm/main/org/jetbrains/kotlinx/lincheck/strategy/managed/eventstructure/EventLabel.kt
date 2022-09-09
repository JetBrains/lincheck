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

import org.jetbrains.kotlinx.lincheck.strategy.managed.defaultValueByDescriptor

// TODO: make a constant for default thread ID
abstract class EventLabel {

    abstract val threadId: Int

    abstract val isRequest: Boolean

    abstract val isResponse: Boolean

    abstract val isTotal: Boolean

    /**
     * Synchronizes event label with another label passed as a parameter.
     * For example, write label `wlab = W(x, v)` synchronizes with a read-request label `rlab = R^{req}(x)`
     * and produces the read-response label `lab = R^{rsp}(x, v)`.
     * That is a call `rlab.synchronize(wlab)` returns `lab`.
     * Synchronize operation is expected to be associative and commutative.
     * Thus, it is also declared as infix operation: `a synchronize b`.
     * In terms of synchronization algebra, non-null return value to the call `C = A.synchronize(B)`
     * means that `A \+ B = C` and consequently `A >> C` and `B >> C`
     * (read as A synchronizes with B into C, and A/B synchronizes with C respectively).
     */
    open infix fun synchronize(label: EventLabel): EventLabel? =
        if (label is EmptyLabel) this else null

    // TODO: better name?
    abstract val isCompleted: Boolean

    abstract val isBinarySynchronizing: Boolean

    abstract val isBarrierSynchronizing: Boolean

    // TODO: rename?
    open infix fun aggregate(label: EventLabel): EventLabel? =
        if (label is EmptyLabel) this else null

    fun aggregatesWith(label: EventLabel): Boolean =
        aggregate(label) != null

    open fun replay(label: EventLabel): Boolean =
        (this == label)

    val isThreadInitializer: Boolean by lazy {
        isRequest && (this is ThreadStartLabel)
    }

    fun isMemoryAccessTo(memId: Int, predicate: MemoryAccessLabel.() -> Boolean = { true }) =
        if (this is MemoryAccessLabel && this.memId == memId)
            predicate()
        else false
}

// TODO: maybe call it DualLabelKind?
enum class LabelKind { Request, Response, Total }

enum class SynchronizationKind { Binary, Barrier }

// TODO: rename to BarrierRaceException?
class InvalidBarrierSynchronizationException(message: String): Exception(message)

// TODO: use of word `Atomic` here is perhaps misleading?
//   Maybe rename it to `SingletonEventLabel` or something similar?
//   At least document the meaning of `Atomic` here.
abstract class AtomicEventLabel(
    override val threadId: Int,
    open val kind: LabelKind,
    open val syncKind: SynchronizationKind,
    override val isCompleted: Boolean,
): EventLabel() {

    override val isRequest: Boolean
        get() = (kind == LabelKind.Request)

    override val isResponse: Boolean
        get() = (kind == LabelKind.Response)

    override val isTotal: Boolean
        get() = (kind == LabelKind.Total)

    override val isBinarySynchronizing: Boolean
        get() = (syncKind == SynchronizationKind.Binary)

    override val isBarrierSynchronizing: Boolean
        get() = (syncKind == SynchronizationKind.Barrier)

}

// TODO: rename?
abstract class AggregatedEventLabel(
    val labels: List<AtomicEventLabel>,
) : EventLabel() {

    init {
        require(labels.isNotEmpty())
    }

    override val threadId: Int
        get() = labels.first().threadId

    init {
        require(labels.all { it.threadId == threadId })
    }

    override val isRequest: Boolean
        get() = labels.any { it.isRequest }

    override val isResponse: Boolean
        get() = labels.any { it.isResponse }

    override val isTotal: Boolean
        get() = labels.any { it.isTotal }

    override val isCompleted: Boolean
        get() = labels.all { it.isCompleted }

    override val isBinarySynchronizing: Boolean
        get() = labels.any { it.isBinarySynchronizing }

    override val isBarrierSynchronizing: Boolean
        get() = labels.any { it.isBinarySynchronizing }

}

// auxiliary ghost thread used as
// (1) thread id of empty label (so that we can have single unit empty object of our monoids);
// (2) thread id of auxiliary initialization event (i.e. root of the event structure).
const val GHOST_THREAD_ID = -1

class EmptyLabel: AtomicEventLabel(
    threadId = GHOST_THREAD_ID,
    kind = LabelKind.Total,
    syncKind = SynchronizationKind.Binary,
    isCompleted = true
) {

    override fun synchronize(label: EventLabel) = label

    override fun aggregate(label: EventLabel) = label

    override fun toString(): String = "Empty"

}

class InitializationLabel : AtomicEventLabel(
    threadId = GHOST_THREAD_ID,
    kind = LabelKind.Total,
    // TODO: can barrier-synchronizing events also utilize InitializationLabel?
    syncKind = SynchronizationKind.Binary,
    isCompleted = true,
) {
    override fun synchronize(label: EventLabel): EventLabel? =
        if (label is InitializationLabel) null else label.synchronize(this)

    override fun toString(): String = "Init"

}

abstract class ThreadEventLabel(
    threadId: Int,
    kind: LabelKind,
    syncKind: SynchronizationKind,
    isCompleted: Boolean
): AtomicEventLabel(threadId, kind, syncKind, isCompleted)

data class ThreadForkLabel(
    override val threadId: Int,
    val forkThreadIds: Set<Int>,
): ThreadEventLabel(
    threadId = threadId,
    kind = LabelKind.Total,
    syncKind = SynchronizationKind.Binary,
    isCompleted = true,
) {

    override fun synchronize(label: EventLabel): EventLabel? =
        if (label is ThreadStartLabel)
            label.synchronize(this)
        else super.synchronize(label)

    override fun toString(): String =
        "ThreadFork(${forkThreadIds})"

}

data class ThreadStartLabel(
    override val threadId: Int,
    override val kind: LabelKind,
    val isInitializationThread: Boolean = false,
): ThreadEventLabel(
    threadId = threadId,
    kind = kind,
    syncKind = SynchronizationKind.Binary,
    isCompleted = true,
) {

    override fun synchronize(label: EventLabel): EventLabel? = when {

        isRequest && isInitializationThread && label is InitializationLabel -> {
            ThreadStartLabel(
                threadId = threadId,
                kind = LabelKind.Response,
                isInitializationThread = true,
            )
        }

        isRequest && label is ThreadForkLabel && threadId in label.forkThreadIds -> {
            check(!isInitializationThread)
            ThreadStartLabel(
                threadId = threadId,
                kind = LabelKind.Response,
                isInitializationThread = false,
            )
        }

        else -> super.synchronize(label)
    }

    override fun aggregate(label: EventLabel): EventLabel? =
        if (isRequest && label.isResponse &&
            label is ThreadStartLabel && threadId == label.threadId)
            ThreadStartLabel(threadId, LabelKind.Total)
        else super.aggregate(label)

    override fun toString(): String = "ThreadStart"

}

data class ThreadFinishLabel(
    override val threadId: Int,
    val finishedThreadIds: Set<Int> = setOf(threadId)
): ThreadEventLabel(
    threadId = threadId,
    kind = LabelKind.Total,
    syncKind = SynchronizationKind.Barrier,
    isCompleted = true,
) {

    override fun synchronize(label: EventLabel): EventLabel? = when {

        // TODO: handle cases of invalid synchronization:
        //  - when there are multiple ThreadFinish labels with the same thread id
        //  - when thread finishes outside of matching ThreadFork/ThreadJoin scope
        //  In order to handle the last case we need to add `scope` parameter to Thread labels?.
        //  Throw `InvalidBarrierSynchronizationException` in these cases.
        (label is ThreadJoinLabel && label.joinThreadIds.containsAll(finishedThreadIds)) -> {
            ThreadJoinLabel(
                threadId = label.threadId,
                kind = LabelKind.Response,
                joinThreadIds = label.joinThreadIds - finishedThreadIds,
            )
        }

        (label is ThreadFinishLabel) -> {
            ThreadFinishLabel(
                threadId = threadId,
                finishedThreadIds = finishedThreadIds + label.finishedThreadIds
            )
        }

        else -> super.synchronize(label)
    }

    override fun toString(): String = "ThreadFinish"

}

data class ThreadJoinLabel(
    override val threadId: Int,
    override val kind: LabelKind,
    val joinThreadIds: Set<Int>,
): ThreadEventLabel(
    threadId = threadId,
    kind = kind,
    syncKind = SynchronizationKind.Barrier,
    isCompleted = (kind == LabelKind.Response) implies joinThreadIds.isEmpty()
) {

    override fun synchronize(label: EventLabel): EventLabel? =
        if (label is ThreadFinishLabel)
            label.synchronize(this)
        else super.synchronize(label)

    override fun aggregate(label: EventLabel): EventLabel? =
        if (isRequest && label.isResponse &&
            label is ThreadJoinLabel && threadId == label.threadId &&
            label.joinThreadIds.isEmpty()) {
            ThreadJoinLabel(threadId, LabelKind.Total, setOf())
        } else super.aggregate(label)

    override fun toString(): String =
        "ThreadJoin(${joinThreadIds})"
}

enum class MemoryAccessKind { Read, Write }

interface MemoryAccessLabel {

    val isRead: Boolean

    val isWrite: Boolean

    val isReadModifyWrite: Boolean
        get() = isRead && isWrite

    val memId: Int

    val typeDescriptor: String

}

data class AtomicMemoryAccessLabel(
    override val threadId: Int,
    override val kind: LabelKind,
    val accessKind: MemoryAccessKind,
    override val typeDescriptor: String,
    override val memId: Int,
    var value: Any?,
    val isExclusive: Boolean = false
): AtomicEventLabel(
    threadId = threadId,
    kind = kind,
    syncKind = SynchronizationKind.Binary,
    isCompleted = true
), MemoryAccessLabel {

    override val isRead: Boolean =
        (accessKind == MemoryAccessKind.Read)

    override val isWrite: Boolean =
        (accessKind == MemoryAccessKind.Write)

    init {
        require((isRead && isRequest) implies (value == null))
        require(isWrite implies isTotal)
    }

    fun equalUpToValue(label: AtomicMemoryAccessLabel): Boolean =
        (threadId == label.threadId) &&
        (kind == label.kind) &&
        (accessKind == label.accessKind) &&
        (typeDescriptor == label.typeDescriptor) &&
        (memId == label.memId) &&
        (isExclusive == label.isExclusive)

    private fun completeReadRequest(value: Any?, typeDescriptor: String): AtomicMemoryAccessLabel {
        require(isRead && isRequest)
        require(this.typeDescriptor == typeDescriptor)
        return AtomicMemoryAccessLabel(
            threadId = threadId,
            kind = LabelKind.Response,
            accessKind = MemoryAccessKind.Read,
            typeDescriptor = typeDescriptor,
            memId = memId,
            value = value,
            isExclusive = isExclusive,
        )
    }

    override fun synchronize(label: EventLabel): EventLabel? = when {

        (isRead && isRequest && label is InitializationLabel) ->
            completeReadRequest(defaultValueByDescriptor(typeDescriptor), typeDescriptor)

        (label is AtomicMemoryAccessLabel && memId == label.memId) -> when {
            isRead && isRequest && label.isWrite ->
                completeReadRequest(label.value, label.typeDescriptor)
            isWrite && label.isRead && label.isRequest ->
                label.completeReadRequest(value, typeDescriptor)
            else -> null
        }

        else -> super.synchronize(label)
    }

    override fun aggregate(label: EventLabel): EventLabel? = when {

        // TODO: perform dynamic type-check of `typeDesc`
        (isRead && isRequest && value == null &&
         label is AtomicMemoryAccessLabel && label.isRead && label.isResponse &&
         threadId == label.threadId && memId == label.memId && isExclusive == label.isExclusive) -> {
            AtomicMemoryAccessLabel(
                threadId = threadId,
                kind = LabelKind.Total,
                accessKind = MemoryAccessKind.Read,
                typeDescriptor = typeDescriptor,
                memId = memId,
                value = label.value,
                isExclusive = isExclusive
            )
        }

        (isRead && isTotal && isExclusive &&
         label is AtomicMemoryAccessLabel && label.isWrite && label.isExclusive &&
         threadId == label.threadId && memId == label.memId) -> {
            ReadModifyWriteMemoryAccessLabel(this, label)
        }

        else -> super.aggregate(label)
    }

    override fun replay(label: EventLabel): Boolean {
        if (label is AtomicMemoryAccessLabel && equalUpToValue(label)) {
            value = label.value
            return true
        }
        return false
    }

    // TODO: move to common class for type descriptor logic
    private val valueString: String = when (typeDescriptor) {
        // for primitive types just print the value
        "I", "Z", "B", "C", "S", "J", "D", "F" -> value.toString()
        // for object types we should not call `toString` because
        // it itself can be transformed and instrumented to call
        // `onSharedVariableRead`, `onSharedVariableWrite` or similar methods,
        // calling those would recursively create new events
        // TODO: perhaps, there is better workaround for this problem?
        else -> if (value == null) "null"
                else (value as Any).javaClass.name + '@' + Integer.toHexString(value.hashCode())
    }

    override fun toString(): String {
        val kindString = when (kind) {
            LabelKind.Request -> "^req"
            LabelKind.Response -> "^rsp"
            LabelKind.Total -> ""
        }
        val exclString = if (isExclusive) "_ex" else ""
        return "${accessKind}${kindString}${exclString}(${memId}, ${valueString})"
    }

}

// TODO: MemoryAccessLabel and ReadModifyWriteMemoryAccessLabel likely
//   should have common ancestor in the hierarchy?
data class ReadModifyWriteMemoryAccessLabel(
    val readLabel: AtomicMemoryAccessLabel,
    val writeLabel: AtomicMemoryAccessLabel,
) : AggregatedEventLabel(listOf(readLabel, writeLabel)), MemoryAccessLabel {

    init {
        require(readLabel.isRead && readLabel.isExclusive)
        require(writeLabel.isWrite && writeLabel.isExclusive)
        require(readLabel.threadId == writeLabel.threadId)
        require(readLabel.memId == writeLabel.memId)
        // TODO: also check types
        require(readLabel.isTotal && writeLabel.isTotal)
    }

    override val isRead: Boolean = true

    override val isWrite: Boolean = true

    override val memId: Int = readLabel.memId

    override val typeDescriptor: String = readLabel.typeDescriptor

    val readValue: Any?
        get() = readLabel.value

    val writeValue: Any?
        get() = writeLabel.value

    override fun synchronize(label: EventLabel): EventLabel? =
        if (label is EmptyLabel) this else writeLabel.synchronize(label)

    override fun aggregate(label: EventLabel): EventLabel? {
        return super.aggregate(label)
    }

    override fun replay(label: EventLabel): Boolean =
        if (label is ReadModifyWriteMemoryAccessLabel &&
            readLabel.equalUpToValue(label.readLabel) &&
            writeLabel.equalUpToValue(label.writeLabel)) {
            readLabel.value = label.readValue
            writeLabel.value = label.writeValue
            true
        } else false

    override fun toString(): String =
        "ReadModifyWrite(${memId}, ${readValue}, ${writeValue})"

}