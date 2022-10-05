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

import org.jetbrains.kotlinx.lincheck.strategy.managed.OpaqueValue
import org.jetbrains.kotlinx.lincheck.strategy.managed.isInstanceOf
import kotlin.reflect.KClass

abstract class EventLabel {

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

    val isInitializer: Boolean by lazy {
        this is InitializationLabel
    }

    val isThreadInitializer: Boolean by lazy {
        isRequest && this is ThreadStartLabel
    }

    inline fun isMemoryAccess(predicate: MemoryAccessPredicate = { true }) =
        if (this is MemoryAccessLabel)
            predicate()
        else false

    inline fun isReadAccess(predicate: MemoryAccessPredicate = { true }) =
        isMemoryAccess { isRead && predicate() }

    inline fun isWriteAccess(predicate: MemoryAccessPredicate = { true }) =
        isMemoryAccess { isWrite && predicate() }

    inline fun isMemoryAccessTo(memId: Int, predicate: MemoryAccessPredicate = { true }) =
        isMemoryAccess { this.memId == memId && predicate() }

    inline fun isWriteAccessTo(memId: Int, predicate: MemoryAccessPredicate = { true }) =
        isMemoryAccessTo(memId) { isWrite && predicate() }

    infix fun toSameLocation(other: EventLabel) =
        this is MemoryAccessLabel && other.isMemoryAccessTo(memId)

    infix fun writesToSameLocation(other: EventLabel) =
        this is MemoryAccessLabel && isWrite && other.isWriteAccessTo(memId)
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

class EmptyLabel: AtomicEventLabel(
    kind = LabelKind.Total,
    syncKind = SynchronizationKind.Binary,
    isCompleted = true
) {

    override fun synchronize(label: EventLabel) = label

    override fun aggregate(label: EventLabel) = label

    override fun toString(): String = "Empty"

}

class InitializationLabel : AtomicEventLabel(
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
    kind: LabelKind,
    syncKind: SynchronizationKind,
    isCompleted: Boolean
): AtomicEventLabel(kind, syncKind, isCompleted)

data class ThreadForkLabel(
    val forkThreadIds: Set<Int>,
): ThreadEventLabel(
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
    override val kind: LabelKind,
    val threadId: Int,
    val isInitializationThread: Boolean = false,
): ThreadEventLabel(
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
            ThreadStartLabel(LabelKind.Total, threadId, isInitializationThread)
        else super.aggregate(label)

    override fun toString(): String = "ThreadStart"

}

data class ThreadFinishLabel(
    val finishedThreadIds: Set<Int>
): ThreadEventLabel(
    kind = LabelKind.Total,
    syncKind = SynchronizationKind.Barrier,
    isCompleted = true,
) {

    constructor(threadId: Int): this(setOf(threadId))

    override fun synchronize(label: EventLabel): EventLabel? = when {

        // TODO: handle cases of invalid synchronization:
        //  - when there are multiple ThreadFinish labels with the same thread id
        //  - when thread finishes outside of matching ThreadFork/ThreadJoin scope
        //  In order to handle the last case we need to add `scope` parameter to Thread labels?.
        //  Throw `InvalidBarrierSynchronizationException` in these cases.
        (label is ThreadJoinLabel && label.joinThreadIds.containsAll(finishedThreadIds)) -> {
            ThreadJoinLabel(
                kind = LabelKind.Response,
                joinThreadIds = label.joinThreadIds - finishedThreadIds,
            )
        }

        (label is ThreadFinishLabel) -> {
            ThreadFinishLabel(
                finishedThreadIds = finishedThreadIds + label.finishedThreadIds
            )
        }

        else -> super.synchronize(label)
    }

    override fun toString(): String = "ThreadFinish"

}

data class ThreadJoinLabel(
    override val kind: LabelKind,
    val joinThreadIds: Set<Int>,
): ThreadEventLabel(
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
            label is ThreadJoinLabel &&
            label.joinThreadIds.isEmpty()) {
            ThreadJoinLabel(LabelKind.Total, setOf())
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

    val kClass: KClass<*>

    val isExclusive: Boolean

}

typealias MemoryAccessPredicate = MemoryAccessLabel.() -> Boolean

data class AtomicMemoryAccessLabel(
    override val kind: LabelKind,
    val accessKind: MemoryAccessKind,
    private var memId_: Int,
    private var value_: OpaqueValue?,
    override val kClass: KClass<*>,
    override val isExclusive: Boolean = false
): AtomicEventLabel(
    kind = kind,
    syncKind = SynchronizationKind.Binary,
    isCompleted = true
), MemoryAccessLabel {

    override val memId: Int
        get() = memId_

    val value: OpaqueValue?
        get() = value_

    override val isRead: Boolean =
        (accessKind == MemoryAccessKind.Read)

    override val isWrite: Boolean =
        (accessKind == MemoryAccessKind.Write)

    init {
        require((isRead && isRequest) implies (value == null))
        require(isWrite implies isTotal)
    }

    private fun completeReadRequest(value: OpaqueValue?): AtomicMemoryAccessLabel {
        require(isRead && isRequest)
        // require(value.isInstanceOf(kClass))
        return AtomicMemoryAccessLabel(
            kind = LabelKind.Response,
            accessKind = MemoryAccessKind.Read,
            memId_ = memId,
            value_ = value,
            kClass = kClass,
            isExclusive = isExclusive,
        )
    }

    override fun synchronize(label: EventLabel): EventLabel? = when {

        (isRead && isRequest && label is InitializationLabel) ->
            completeReadRequest(OpaqueValue.default(kClass))

        (label is AtomicMemoryAccessLabel && memId == label.memId) -> when {
            isRead && isRequest && label.isWrite ->
                completeReadRequest(label.value)
            isWrite && label.isRead && label.isRequest ->
                label.completeReadRequest(value)
            else -> null
        }

        else -> super.synchronize(label)
    }

    override fun aggregate(label: EventLabel): EventLabel? = when {

        // TODO: perform dynamic type-check of `typeDesc`
        (isRead && isRequest &&
         label is AtomicMemoryAccessLabel && label.isRead && label.isResponse &&
         memId == label.memId && isExclusive == label.isExclusive) -> {
            AtomicMemoryAccessLabel(
                kind = LabelKind.Total,
                accessKind = MemoryAccessKind.Read,
                memId_ = memId,
                value_ = label.value,
                kClass = kClass,
                isExclusive = isExclusive
            )
        }

        (isRead && !isRequest && isExclusive &&
         label is MemoryAccessLabel && label.isWrite && label.isExclusive &&
         memId == label.memId) -> {
            val writeLabel = when(label) {
                is AtomicMemoryAccessLabel -> label
                is ReadModifyWriteMemoryAccessLabel -> label.writeLabel
                else -> unreachable()
            }
            ReadModifyWriteMemoryAccessLabel(this, writeLabel)
        }

        else -> super.aggregate(label)
    }

    private fun equalUpToReplay(label: AtomicMemoryAccessLabel): Boolean =
        (kind == label.kind) &&
        (accessKind == label.accessKind) &&
        (kClass == label.kClass) &&
        (isExclusive == label.isExclusive)

    override fun replay(label: EventLabel): Boolean {
        if (label is AtomicMemoryAccessLabel && equalUpToReplay(label)) {
            memId_ = label.memId
            value_ = label.value
            return true
        }
        return false
    }

    override fun toString(): String {
        val kindString = when (kind) {
            LabelKind.Request -> "^req"
            LabelKind.Response -> "^rsp"
            LabelKind.Total -> ""
        }
        val exclString = if (isExclusive) "_ex" else ""
        val argsString = "$memId" + if (kind != LabelKind.Request) ", $value" else ""
        return "${accessKind}${kindString}${exclString}(${argsString})"
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
        require(readLabel.memId == writeLabel.memId)
        // TODO: do we need weaker type check?
        //  e.g. for a case when expected and desired values of CAS are of different classes
        require(readLabel.kClass == writeLabel.kClass)
        require(!readLabel.isRequest && writeLabel.isTotal)
    }

    override val isRead: Boolean = true

    override val isWrite: Boolean = true

    override val memId: Int = readLabel.memId

    override val kClass: KClass<*> = readLabel.kClass

    override val isExclusive: Boolean = true

    override fun synchronize(label: EventLabel): EventLabel? =
        if (label is EmptyLabel) this else writeLabel.synchronize(label)

    override fun aggregate(label: EventLabel): EventLabel? {
        return super.aggregate(label)
    }

    override fun replay(label: EventLabel): Boolean =
        if (label is ReadModifyWriteMemoryAccessLabel) {
            readLabel.replay(label.readLabel) && writeLabel.replay(label.writeLabel)
        } else false

    override fun toString(): String {
        val kindString = if (readLabel.isResponse) "^rsp" else ""
        return "ReadModifyWrite${kindString}(${memId}, ${readLabel.value}, ${writeLabel.value})"
    }

}