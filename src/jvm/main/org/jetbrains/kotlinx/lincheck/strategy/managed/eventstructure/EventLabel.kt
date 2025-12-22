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

import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure.SpanLabelKind.*
import org.jetbrains.kotlinx.lincheck.util.*

/**
 * EventLabel is a base class for the hierarchy of classes
 * representing semantic labels of various events performed by the program.
 *
 * It includes events such as
 * - thread fork, join, start, or finish;
 * - reads and writes from/to shared memory;
 * - mutex locks and unlocks;
 * and other (see subclasses of this class).
 *
 * Some events may form a _span_ - that is a sequence of events.
 * For example, all events belonging to the execution of a method form a span.
 * The start of the method execution and the end of method execution are denoted
 * by two special events - method start event and method exit event respectively.
 * All the events between these two events are considered to form a method's span.
 * The span itself can be considered to be a composite event (see [HyperEvent]).
 * Labels of span-related events, which denote the start of a span, its end,
 * or the span as a whole, should set [spanKind] accordingly.
 * 
 *
 * @property kind The kind of this label (see [LabelKind]).
 * @property spanKind For span-related events, denotes the kind of the span label (see [SpanLabelKind]),
 *   null for the non-span-related events.
 * @property isBlocking Flag indicating that label is blocking.
 *    Mutex lock and thread join are examples of blocking labels.
 * @property isUnblocked Flag indicating that blocking label is unblocked.
 *   For example, thread join-response is unblocked when all the threads it waits for have finished.
 *   For non-blocking labels, it should be set to true.
 */
sealed class EventLabel(
    open val kind: LabelKind,
    open val spanKind: SpanLabelKind? = null,
    val isBlocking: Boolean = false,
    val isUnblocked: Boolean = true,
) {
    
    /**
     * Checks whether this label is send label.
     */
    val isSend: Boolean
        get() = (kind == LabelKind.Send)

    /**
     * Checks whether this label is a request label.
     */
    val isRequest: Boolean
        get() = (kind == LabelKind.Request)

    /**
     * Checks whether this label is a response label.
     */
    val isResponse: Boolean
        get() = (kind == LabelKind.Response)

    /**
     * Checks whether this label is a receive label.
     */
    val isReceive: Boolean
        get() = (kind == LabelKind.Receive)

    /**
     * Checks whether this label is span-related.
     */
    val isSpanLabel: Boolean
        get() = (spanKind != null)

    /**
     * Object accesses by the operation represented by the label.
     * For example, for object field memory access labels, this is the accessed object.
     * If a particular subclass of labels does not access any object,
     * then this property is equal to [NULL_OBJECT_ID].
     */
    open val objectID: ObjectID = NULL_OBJECT_ID

}

/**
 * Kind of label. Can be one of the following:
 * - [LabelKind.Send] --- send label.
 * - [LabelKind.Request] --- request label.
 * - [LabelKind.Response] --- response label.
 * - [LabelKind.Receive] --- receive label.
 *
 * For example, [WriteAccessLabel] is an example of the send label,
 * while [ReadAccessLabel] is split into request and response part.
 *
 * @see EventLabel
 */
enum class LabelKind { Send, Request, Response, Receive }

val LabelKind.repr get() = when (this) {
    LabelKind.Send -> ""
    LabelKind.Request -> "^req"
    LabelKind.Response -> "^rsp"
    LabelKind.Receive -> ""
}

/**
 * Enumeration representing different kinds of span event labels.
 *
 * - [Start] is assigned to the label denoting the start of the span.
 * - [End] is assigned to the label denoting the end of the span.
 * - [Span] is assigned to the label of composite event
 *     containing the whole list of events of the span.
 */
enum class SpanLabelKind { Start, End, Span }

/**
 * Determines the corresponding label kind for a given span label kind.
 */
fun SpanLabelKind.toLabelKind(): LabelKind = when (this) {
    SpanLabelKind.Start -> LabelKind.Request
    SpanLabelKind.End   -> LabelKind.Response
    SpanLabelKind.Span  -> LabelKind.Receive
}

/**
 * Enum class representing different types of event labels.
 */
enum class LabelType {
    Initialization,
    ObjectAllocation,
    ThreadStart,
    ThreadFinish,
    ThreadFork,
    ThreadJoin,
    ReadAccess,
    WriteAccess,
    ReadModifyWriteAccess,
    CoroutineSuspend,
    CoroutineResume,
    Lock,
    Unlock,
    Wait,
    Notify,
    Park,
    Unpark,
    Actor,
    Random,
}

/**
 * Type of the label.
 *
 * @see LabelType
 */
val EventLabel.type: LabelType get() = when (this) {
    is InitializationLabel          -> LabelType.Initialization
    is ObjectAllocationLabel        -> LabelType.ObjectAllocation
    is ThreadStartLabel             -> LabelType.ThreadStart
    is ThreadFinishLabel            -> LabelType.ThreadFinish
    is ThreadForkLabel              -> LabelType.ThreadFork
    is ThreadJoinLabel              -> LabelType.ThreadJoin
    is ReadAccessLabel              -> LabelType.ReadAccess
    is WriteAccessLabel             -> LabelType.WriteAccess
    is ReadModifyWriteAccessLabel   -> LabelType.ReadModifyWriteAccess
    is CoroutineSuspendLabel        -> LabelType.CoroutineSuspend
    is CoroutineResumeLabel         -> LabelType.CoroutineResume
    is LockLabel                    -> LabelType.Lock
    is UnlockLabel                  -> LabelType.Unlock
    is WaitLabel                    -> LabelType.Wait
    is NotifyLabel                  -> LabelType.Notify
    is ParkLabel                    -> LabelType.Park
    is UnparkLabel                  -> LabelType.Unpark
    is ActorLabel                   -> LabelType.Actor
    is RandomLabel                  -> LabelType.Random
}


/* ************************************************************************* */
/*      Initialization labels                                                */
/* ************************************************************************* */


/**
 * A special label of the virtual root event of every execution.
 *
 * Initialization label stores the initial values for:
 *   - static memory locations, and
 *   - memory locations of external objects - these are the objects
 *     allocated outside the tracked code sections.
 *
 * @property initThreadID thread id used for the special initial thread;
 *   this thread should contain only the initialization event itself.
 * @property mainThreadID thread id of the main thread, starting the execution of a program.
 * @property memoryInitializer a callback performing a load of the initial values
 *   of a passed memory location.
 */
class InitializationLabel(
    val initThreadID: ThreadId,
    val mainThreadID: ThreadId,
    val memoryInitializer: MemoryIDInitializer,
) : EventLabel(LabelKind.Send) {

    private val staticMemory =
        HashMap<StaticFieldMemoryLocation, ValueID>()

    private val _objectsAllocations =
        HashMap<ObjectID, ObjectAllocationLabel>()

    val objectsAllocations: Map<ObjectID, ObjectAllocationLabel>
        get() = _objectsAllocations

    val externalObjects: Set<ObjectID>
        get() = objectsAllocations.keys

    fun getInitialValue(location: StaticFieldMemoryLocation): ValueID {
        return staticMemory.computeIfAbsent(location) { memoryInitializer(it) }
    }

    fun trackExternalObject(className: String, objID: ObjectID) {
        _objectsAllocations[objID] = ObjectAllocationLabel(className, objID, memoryInitializer)
    }

    override fun toString(): String = "Init"

}


/**
 * Represents a label for object allocation events.
 *
 * @property className The name of the class of the allocated object.
 * @property objectID The ID of the allocated object.
 * @property memoryInitializer a callback performing a load of the initial values
 *   of the allocated object's memory locations.
 */
data class ObjectAllocationLabel(
    val className: String,
    override val objectID: ObjectID,
    val memoryInitializer: MemoryIDInitializer,
) : EventLabel(kind = LabelKind.Send) {

    init {
        require(objectID != NULL_OBJECT_ID)
    }

    private val initialValues = HashMap<MemoryLocation, ValueID>()

    fun getInitialValue(location: MemoryLocation): ValueID {
        require(location.objID == objectID)
        return initialValues.computeIfAbsent(location) { memoryInitializer(it) }
    }

    override fun toString(): String =
        "Alloc(${objRepr(className, objectID)})"

}


/**
 * Interprets the initialization label as an object allocation label.
 *
 * Initialization label is only responsible for storing information about the external objects ---
 * these are the objects created outside the tracked code sections.
 * Such objects should be registered via [InitializationLabel.trackExternalObject] method.
 *
 * @param objID The ObjectID of an external object.
 * @return The object allocation label associated with the given ObjectID,
 *   or null if there is no external object with given id exists.
 */
fun InitializationLabel.asObjectAllocationLabel(objID: ObjectID): ObjectAllocationLabel? =
    objectsAllocations[objID]

/**
 * Attempts to interpret a given event label as an object allocation label.
 *
 * @param objID The ObjectID of the object to match.
 * @return The [ObjectAllocationLabel] if the label can be interpreted as it, null otherwise.
 */
fun EventLabel.asObjectAllocationLabel(objID: ObjectID): ObjectAllocationLabel? = when (this) {
    is ObjectAllocationLabel -> takeIf { it.objectID == objID }
    is InitializationLabel -> asObjectAllocationLabel(objID)
    else -> null
}


/* ************************************************************************* */
/*      Thread events labels                                                 */
/* ************************************************************************* */


/**
 * Base class for all thread event labels.
 *
 * @param kind the kind of this label.
 * @param isBlocking flag indicating that label is blocking.
 * @param isUnblocked flag indicating that blocking label is unblocked.
  */
sealed class ThreadEventLabel(
    kind: LabelKind,
    isBlocking: Boolean = false,
    isUnblocked: Boolean = true,
): EventLabel(
    kind = kind,
    isBlocking = isBlocking,
    isUnblocked = isUnblocked
)

/**
 * Label representing fork of a set of threads.
 *
 * @param forkThreadIds a set of thread ids this fork spawns.
 */
data class ThreadForkLabel(
    val forkThreadIds: Set<Int>,
): ThreadEventLabel(kind = LabelKind.Send) {

    override fun toString(): String =
        "ThreadFork(${forkThreadIds})"
}

/**
 * Interprets the initialization label as a thread fork of the main thread.
 */
fun InitializationLabel.asThreadForkLabel() =
    ThreadForkLabel(setOf(mainThreadID))

/**
 * Attempts to interpret a given event label as a thread fork label.
 *
 * @return The [ThreadForkLabel] if the label can be interpreted as it, null otherwise.
 */
fun EventLabel.asThreadForkLabel(): ThreadForkLabel? = when (this) {
    is ThreadForkLabel -> this
    is InitializationLabel -> asThreadForkLabel()
    else -> null
}

/**
 * Label of a virtual event put into the beginning of each thread.
 *
 * @param kind the kind of this label: [LabelKind.Request], [LabelKind.Response] or [LabelKind.Receive].
 * @param threadId thread id of starting thread.
 */
data class ThreadStartLabel(
    override val kind: LabelKind,
    val threadId: Int,
): ThreadEventLabel(kind) {

    init {
        require(isRequest || isResponse || isReceive)
    }

    override fun toString(): String =
        "ThreadStart${kind.repr}"
}

/**
 * Label of a virtual event put into the end of each thread.
 *
 * Thread finish label is considered to be always blocked.
 *
 * @param finishedThreadIds set of threads that have been finished.
 */
data class ThreadFinishLabel(
    val finishedThreadIds: Set<Int>
): ThreadEventLabel(
    kind = LabelKind.Send,
    isBlocking = true,
    isUnblocked = false,
) {
    constructor(threadId: Int): this(setOf(threadId))

    override fun toString(): String =
        "ThreadFinish"
}

/**
 * Label representing join of a set of threads.
 *
 * Thread join label is blocking label.
 * It is considered unblocked when the set of threads to-be-joined
 * becomes empty (see [joinThreadIds]).
 *
 * @param kind the kind of this label: [LabelKind.Request], [LabelKind.Response] or [LabelKind.Receive].
 * @param joinThreadIds set of threads this label awaits to join.
 */
data class ThreadJoinLabel(
    override val kind: LabelKind,
    val joinThreadIds: Set<Int>,
): ThreadEventLabel(
    kind = kind,
    isBlocking = true,
    isUnblocked = (kind != LabelKind.Request) implies joinThreadIds.isEmpty(),
) {

    init {
        require(isRequest || isResponse || isReceive)
        require(isReceive implies isUnblocked)
    }

    override fun toString(): String =
        "ThreadJoin${kind.repr}(${joinThreadIds})"
}

/**
 * Attempts to interpret a given event label as a thread event label.
 *
 * @return The [ThreadEventLabel] if the label can be interpreted as it, null otherwise.
 */
fun EventLabel.asThreadEventLabel(): ThreadEventLabel? = when (this) {
    is ThreadEventLabel -> this
    is InitializationLabel -> asThreadForkLabel()
    else -> null
}


/* ************************************************************************* */
/*      Memory access labels                                                 */
/* ************************************************************************* */


/**
 * Base class of shared memory access labels.
 *
 * It stores common information about memory accesses,
 * such as the accessed memory location and read or written value.
 *
 * @param kind The kind of this label.
 * @param location The accessed memory location.
 * @param isExclusive flag indicating whether this access is exclusive.
 *   Memory accesses obtained as a result of executing atomic read-modify-write
 *   instructions (such as CAS) have this flag set.
 * @param kClass class of written or read value.
 * @param codeLocation the code location corresponding to the memory access.
 */
sealed class MemoryAccessLabel(
    kind: LabelKind,
    open val location: MemoryLocation,
    open val readModifyWriteDescriptor: ReadModifyWriteDescriptor? = null,
    open val codeLocation: Int = UNKNOWN_CODE_LOCATION,
): EventLabel(kind) {

    /**
     * Read value for read access.
     */
    abstract val readValue: ValueID

    /**
     * Written value for write access.
     */
    abstract val writeValue: ValueID

    /**
     * Checks whether this memory access is a read access.
     */
    val isRead: Boolean
        get() = (accessKind == MemoryAccessKind.Read || accessKind == MemoryAccessKind.ReadModifyWrite)

    /**
     * Checks whether this memory access is a write access.
     */
    val isWrite: Boolean
        get() = (accessKind == MemoryAccessKind.Write || accessKind == MemoryAccessKind.ReadModifyWrite)

    /**
     * The id of the accessed object.
     */
    override val objectID: ObjectID
        get() = location.objID

    val isExclusive: Boolean
        get() = (readModifyWriteDescriptor != null)

    override fun toString(): String {
        val exclString = if (isExclusive) "_ex" else ""
        val argsString = listOfNotNull(
            "$location",
            if (isRead && kind != LabelKind.Request) "$readValue" else null,
            if (isWrite) "$writeValue" else null,
        ).joinToString()
        return "${accessKind}${kind.repr}${exclString}(${argsString})"
    }

}


/**
 * Kind of memory access.
 *
 * Memory access can either be a read access, write access, or atomic read-modify-write access
 * (for example, compare-and-set or atomic increment).
 */
enum class MemoryAccessKind { Read, Write, ReadModifyWrite }

/**
 * Kind of the memory access.
 *
 * @see MemoryAccessKind
 */
val MemoryAccessLabel.accessKind: MemoryAccessKind
    get() = when(this) {
        is WriteAccessLabel -> MemoryAccessKind.Write
        is ReadAccessLabel -> MemoryAccessKind.Read
        is ReadModifyWriteAccessLabel -> MemoryAccessKind.ReadModifyWrite
    }

sealed class ReadModifyWriteDescriptor {
    data class GetAndSetDescriptor(val newValue: ValueID): ReadModifyWriteDescriptor()
    data class CompareAndSetDescriptor(val expectedValue: ValueID, val newValue: ValueID): ReadModifyWriteDescriptor()
    data class CompareAndExchangeDescriptor(val expectedValue: ValueID, val newValue: ValueID): ReadModifyWriteDescriptor()
    data class FetchAndAddDescriptor(val delta: ValueID, val kind: IncrementKind): ReadModifyWriteDescriptor()
    enum class IncrementKind { Pre, Post }
}

/**
 * Label denoting a read access to shared memory.
 *
 * @param kind The kind of this label: [LabelKind.Request], [LabelKind.Response], or [LabelKind.Receive].
 * @param location The memory location of this read.
 * @param readValue The read value; for read-request label should be equal to null.
 * @param isExclusive Exclusive access flag.
 * @param kClass The class of read value.
 * @param codeLocation the code location corresponding to the read access.
 */
data class ReadAccessLabel(
    override val kind: LabelKind,
    override val location: MemoryLocation,
    override val readValue: ValueID,
    override val readModifyWriteDescriptor: ReadModifyWriteDescriptor? = null,
    override val codeLocation: Int = UNKNOWN_CODE_LOCATION,
): MemoryAccessLabel(kind, location, readModifyWriteDescriptor, codeLocation) {

    init {
        require(isRequest || isResponse || isReceive)
        require(isRequest implies (value == NULL_OBJECT_ID))
    }

    val value: ValueID
        get() = readValue

    override val writeValue: ValueID = NULL_OBJECT_ID

    override fun toString(): String =
        super.toString()
}

/**
 * Label denoting a write access to shared memory.
 *
 * @param location The memory location affected by this write access.
 * @param writeValue The written value.
 * @param isExclusive Exclusive access flag.
 * @param kClass The class of written value.
 * @param codeLocation the code location corresponding to the write access.
 */
data class WriteAccessLabel(
    override val location: MemoryLocation,
    override val writeValue: ValueID,
    override val readModifyWriteDescriptor: ReadModifyWriteDescriptor? = null,
    override val codeLocation: Int = UNKNOWN_CODE_LOCATION,
): MemoryAccessLabel(LabelKind.Send, location, readModifyWriteDescriptor, codeLocation) {

    val value: ValueID
        get() = writeValue

    override val readValue: ValueID = NULL_OBJECT_ID

    override fun toString(): String =
        super.toString()
}

/**
 * Label denoting read-modify-write (RMW) access to shared memory
 * (for example, compare-and-swap or atomic increment).
 *
 * @param location The memory location affected by this access.
 * @param readValue The read value.
 * @param writeValue The written value.
 * @param kClass the class of written value.
 * @param codeLocation the code location corresponding to the memory access.
 */
data class ReadModifyWriteAccessLabel(
    override val kind: LabelKind,
    override val location: MemoryLocation,
    override val readValue: ValueID,
    override val writeValue: ValueID,
    override val readModifyWriteDescriptor: ReadModifyWriteDescriptor,
    override val codeLocation: Int = UNKNOWN_CODE_LOCATION,
): MemoryAccessLabel(kind, location, readModifyWriteDescriptor, codeLocation) {

    init {
        require(kind == LabelKind.Response || kind == LabelKind.Receive)
    }

    override fun toString(): String =
        super.toString()
}

/**
 * Attempts to create a read-modify-write (RMW) access label based on read and write labels.
 *
 * @param read The read access label.
 * @param write The write access label.
 * @return The resulting read-modify-write access label if the read and write labels match, null otherwise.
 */
fun ReadModifyWriteAccessLabel(read: ReadAccessLabel, write: WriteAccessLabel): ReadModifyWriteAccessLabel? {
    require(read.kind == LabelKind.Response || read.kind == LabelKind.Receive)
    return if (read.isExclusive &&
               read.location == write.location &&
               read.readModifyWriteDescriptor == write.readModifyWriteDescriptor &&
               read.codeLocation == write.codeLocation) {
        ReadModifyWriteAccessLabel(
            kind = read.kind,
            location = read.location,
            readValue = read.value,
            writeValue = write.value,
            readModifyWriteDescriptor = read.readModifyWriteDescriptor!!,
            codeLocation = read.codeLocation,
        )
    }
    else null
}


/**
 * Attempts to create a read-modify-write (RMW) access label based on this read label and given write label.
 *
 * @param write The write access label.
 * @return The resulting read-modify-write access label if the read and write labels match, null otherwise.
 */
fun ReadAccessLabel.getReadModifyWrite(write: WriteAccessLabel): ReadModifyWriteAccessLabel? =
    ReadModifyWriteAccessLabel(this, write)

/**
 * Checks whether this read access label is a valid read part of the given read-modify-write access label.
 *
 * @param label The read-modify-write label to check against.
 * @return true if the given label is a valid read part of the RMW, false otherwise.
 */
fun ReadAccessLabel.isValidReadPart(label: ReadModifyWriteAccessLabel): Boolean =
    location == label.location &&
    isExclusive == label.isExclusive &&
    (isResponse implies (readValue == label.readValue))

/**
 * Checks whether this write access label is a valid write part of the given read-modify-write access label.
 *
 * @param label The read-modify-write label to check against.
 * @return true if the given label is a valid write part of the RMW, false otherwise.
 */
fun WriteAccessLabel.isValidWritePart(label: ReadModifyWriteAccessLabel): Boolean =
    location == label.location &&
    isExclusive == label.isExclusive &&
    writeValue == label.writeValue

/**
 * Checks whether this label can be interpreted as a write access label.
 */
fun EventLabel.isWriteAccess(): Boolean =
    this is InitializationLabel || this is ObjectAllocationLabel || this is WriteAccessLabel

/**
 * Checks whether this label is exclusive write access label.
 */
fun EventLabel.isExclusiveWriteAccess(): Boolean =
    (this is WriteAccessLabel) && isExclusive

/**
 * Checks whether this label can be interpreted as an initializing write access label.
 */
fun EventLabel.isInitializingWriteAccess(): Boolean =
    this is InitializationLabel || this is ObjectAllocationLabel

/**
 * Checks if the initialization label can be interpreted as a write access to the given memory location.
 *
 * Initialization label can represent initializing writes to static memory locations,
 * as well as field memory locations of external objects.
 *
 * @param location The memory location to check for.
 */
fun InitializationLabel.isWriteAccessTo(location: MemoryLocation): Boolean =
    location is StaticFieldMemoryLocation || (location.objID in externalObjects)

/**
 * Checks if the object allocation label can be interpreted as a write access to the given memory location.
 *
 * Object allocation label can represent initializing writes to memory locations of the allocated object.
 *
 * @param location The memory location to check for.
 */
fun ObjectAllocationLabel.isWriteAccessTo(location: MemoryLocation) =
    (location.objID == objectID)

/**
 * Checks if the given event label can be interpreted as a write access to the given memory location.
 *
 * @param location The memory location to check for.
 */
fun EventLabel.isWriteAccessTo(location: MemoryLocation): Boolean = when (this) {
    is WriteAccessLabel         -> (this.location == location)
    is ObjectAllocationLabel    -> isWriteAccessTo(location)
    is InitializationLabel      -> isWriteAccessTo(location)
    else -> false
}

/**
 * Attempts to interpret a given initialization label as a write access label.
 *
 * @param location The memory location to match.
 * @return The [WriteAccessLabel] if the label can be interpreted as it, null otherwise.
 */
fun InitializationLabel.asWriteAccessLabel(location: MemoryLocation): WriteAccessLabel? = when {
    location is StaticFieldMemoryLocation ->
        WriteAccessLabel(
            location = location,
            writeValue = getInitialValue(location),
            codeLocation = INIT_CODE_LOCATION,
        )

    else -> asObjectAllocationLabel(location.objID)?.asWriteAccessLabel(location)
}

/**
 * Attempts to interpret a given object allocation label as a write access label.
 *
 * @param location The memory location to match.
 * @return The [WriteAccessLabel] if the label can be interpreted as it, null otherwise.
 */
fun ObjectAllocationLabel.asWriteAccessLabel(location: MemoryLocation): WriteAccessLabel? =
    if (location.objID == objectID)
        WriteAccessLabel(
            location = location,
            writeValue = getInitialValue(location),
            // TODO: use actual allocation-site code location?
            codeLocation = INIT_CODE_LOCATION,
        )
    else null

/**
 * Attempts to interpret a given label as a write access label.
 *
 * @param location The memory location to match.
 * @return The [WriteAccessLabel] if the label can be interpreted as it, null otherwise.
 */
fun EventLabel.asWriteAccessLabel(location: MemoryLocation): WriteAccessLabel? = when (this) {
    is WriteAccessLabel         -> this.takeIf { it.location == location }
    is ObjectAllocationLabel    -> asWriteAccessLabel(location)
    is InitializationLabel      -> asWriteAccessLabel(location)
    else -> null
}

/**
 * Checks if the given event label can be interpreted as a memory access to the given memory location.
 *
 * @param location The memory location to check for.
 */
fun EventLabel.isMemoryAccessTo(location: MemoryLocation): Boolean = when (this) {
    is MemoryAccessLabel        -> (this.location == location)
    is ObjectAllocationLabel    -> isWriteAccessTo(location)
    is InitializationLabel      -> isWriteAccessTo(location)
    else -> false
}

/**
 * Attempts to interpret a given label as a memory access label.
 *
 * @param location The memory location to match.
 * @return The [MemoryAccessLabel] if the label can be interpreted as it, null otherwise.
 */
fun EventLabel.asMemoryAccessLabel(location: MemoryLocation): MemoryAccessLabel? = when (this) {
    is MemoryAccessLabel        -> this.takeIf { it.location == location }
    is ObjectAllocationLabel    -> asWriteAccessLabel(location)
    is InitializationLabel      -> asWriteAccessLabel(location)
    else -> null
}


/* ************************************************************************* */
/*      Mutex events labels                                                  */
/* ************************************************************************* */


/**
 * Base class of all mutex operations event labels.
 * 
 * It stores common information about mutex operations, 
 * such as the accessed lock object.
 *
 * @param kind The kind of this label.
 * @param mutexID The id of the mutex object to perform operation on.
 * @param isBlocking Flag indicating whether this label is blocking.
 * @param isUnblocked Flag indicating whether this blocking label is already unblocked.
 */
sealed class MutexLabel(
    kind: LabelKind,
    open val mutexID: ObjectID,
    isBlocking: Boolean = false,
    isUnblocked: Boolean = true,
): EventLabel(
    kind = kind, 
    isBlocking = isBlocking, 
    isUnblocked = isUnblocked
) {

    /**
     * The id of the accessed object.
     */
    override val objectID: ObjectID
        get() = mutexID

    override fun toString(): String {
        return "${operationKind}${kind.repr}($mutexID)"
    }

}


/**
 * Kind of mutex operation.
 *
 * Mutex operation can either be lock, unlock, wait or notify.
 */
enum class MutexOperationKind { Lock, Unlock, Wait, Notify }

/**
 * Kind of mutex operation.
 * 
 * @see MutexOperationKind
 */
val MutexLabel.operationKind: MutexOperationKind
    get() = when(this) {
        is LockLabel    -> MutexOperationKind.Lock
        is UnlockLabel  -> MutexOperationKind.Unlock
        is WaitLabel    -> MutexOperationKind.Wait
        is NotifyLabel  -> MutexOperationKind.Notify
    }


/**
 * Label denoting lock of a mutex.
 *
 * @param kind The kind of this label: [LabelKind.Request] or [LabelKind.Response].
 * @param mutexID The id of the locked mutex object.
 * @param isReentry Flag indicating whether this lock operation is re-entry lock.
 * @param reentrancyDepth The re-entrance depth of this lock operation.
 * @param isSynthetic Flag indicating whether this lock operation is synthetic.
 *   For example, a wait-response operation can be represented as a wait-response event,
 *   followed by a synthetic lock operation.
 */
data class LockLabel(
    override val kind: LabelKind,
    override val mutexID: ObjectID,
    val isReentry: Boolean = false,
    val reentrancyDepth: Int = 1,
    val isSynthetic: Boolean = false,
) : MutexLabel(
    kind = kind,
    mutexID = mutexID,
    isBlocking = true,
    isUnblocked = (kind != LabelKind.Request),
) {
    init {
        require(isRequest || isResponse)
    }

    override fun toString(): String =
        super.toString()
}

/**
 * Label denoting unlock of a mutex.
 *
 * @param mutexID The id of the locked mutex object.
 * @param isReentry Flag indicating whether this unlock operation is re-entry lock.
 * @param reentrancyDepth The re-entrance depth of this unlock operation.
 * @param isSynthetic Flag indicating whether this lock operation is synthetic.
 *   For example, a wait-response operation can be represented as a wait-response event,
 *   followed by a synthetic lock operation.
 */
data class UnlockLabel(
    override val mutexID: ObjectID,
    val isReentry: Boolean = false,
    val reentrancyDepth: Int = 1,
    val isSynthetic: Boolean = false,
) : MutexLabel(LabelKind.Send, mutexID) {

    override fun toString(): String =
        super.toString()
}

/**
 * Label denoting wait on a mutex.
 *
 * @param kind The kind of this label: [LabelKind.Request] or [LabelKind.Response].
 * @param mutexID The id of the mutex object to wait on.
 * @param isLocking Flag indicating whether this wait operation also performs lock of the mutex.
 * @param isUnlocking Flag indicating whether this wait operation also performs unlock of the mutex.
 */
data class WaitLabel(
    override val kind: LabelKind,
    override val mutexID: ObjectID,
    val isLocking: Boolean = false,
    val isUnlocking: Boolean = false,
) : MutexLabel(
    kind = kind,
    mutexID = mutexID,
    isBlocking = true,
    isUnblocked = (kind != LabelKind.Request),
) {
    init {
        require(isRequest || isResponse)
        require(isRequest implies !isLocking)
        require(isResponse implies !isUnlocking)
    }

    override fun toString(): String =
        super.toString()
}

/**
 * Label denoting notification of a mutex.
 *
 * @param mutexID The id of the mutex object to notify.
 * @param isBroadcast Flag indicating that this notification is broadcast,
 *   that is created by a `notifyAll()` method call.
 */
data class NotifyLabel(
    override val mutexID: ObjectID,
    val isBroadcast: Boolean
) : MutexLabel(LabelKind.Send, mutexID) {

    override fun toString(): String =
        super.toString()
}

/**
 * Checks whether this label can be interpreted as an initializing synthetic unlock label.
 */
fun EventLabel.isInitializingUnlock(): Boolean =
    this is InitializationLabel || this is ObjectAllocationLabel

/**
 * Interprets the initialization label as an unlock label.
 *
 * Initialization label can represent the first synthetic unlock label of some external objects.
 *
 * @param mutexID The id of an external object on which unlock is performed.
 * @return The unlock label associated with the given mutex,
 *   or null if there is no external mutex object with given id exists.
 */
fun InitializationLabel.asUnlockLabel(mutexID: ObjectID) =
    asObjectAllocationLabel(mutexID)?.asUnlockLabel(mutexID)

/**
 * Interprets the object allocation label as an unlock label.
 *
 * Object allocation label can represent the first synthetic unlock label of
 * the given mutex object.
 *
 * @param mutexID The id of an external object on which unlock is performed.
 * @return The unlock label associated with the given mutex,
 *   or null if there is no external mutex object with given id exists.
 */
fun ObjectAllocationLabel.asUnlockLabel(mutexID: ObjectID) =
    if (mutexID == objectID) UnlockLabel(mutexID = objectID, isSynthetic = true) else null


/**
 * Attempts to interpret a given label as an unlock label.
 *
 * @param mutexID The id of the mutex object to match.
 * @return The [UnlockLabel] if the label can be interpreted as it, null otherwise.
 *
 */
fun EventLabel.asUnlockLabel(mutexID: ObjectID): UnlockLabel? = when (this) {
    is UnlockLabel -> this.takeIf { it.mutexID == mutexID }
    is ObjectAllocationLabel -> asUnlockLabel(mutexID)
    is InitializationLabel -> asUnlockLabel(mutexID)
    else -> null
}

/**
 * Attempts to interpret a given label a notify label.
 *
 * @param mutexID The id of the mutex object to match.
 * @return The [NotifyLabel] if the label can be interpreted as it, null otherwise.
 *
 */
fun EventLabel.asNotifyLabel(mutexID: ObjectID): NotifyLabel? = when (this) {
    is NotifyLabel -> this.takeIf { it.mutexID == mutexID }
    else -> null
}


/* ************************************************************************* */
/*      Parking labels                                                       */
/* ************************************************************************* */


/**
 * Base class for park and unpark event labels.
 *
 * @param kind The kind of this label.
 * @param threadId The thread id of parked or unparked thread.
 * @param isBlocking Flag indicating that label is blocking.
 * @param isUnblocked Flag indicating that blocking label is unblocked.
 */
sealed class ParkingEventLabel(
    kind: LabelKind,
    open val threadId: Int,
    isBlocking: Boolean = false,
    isUnblocked: Boolean = true,
): EventLabel(
    kind = kind, 
    isBlocking = isBlocking, 
    isUnblocked = isUnblocked
) {

    override fun toString(): String {
        val argsString = if (operationKind == ParkingOperationKind.Unpark) "($threadId)" else ""
        return "${operationKind}${kind.repr}${argsString}"
    }

}

/**
 * Kind of parking operation.
 */
enum class ParkingOperationKind { Park, Unpark }

/**
 * Kind of parking operation.
 *
 * @see ParkingOperationKind
 */
val ParkingEventLabel.operationKind: ParkingOperationKind
    get() = when (this) {
        is ParkLabel    -> ParkingOperationKind.Park
        is UnparkLabel  -> ParkingOperationKind.Unpark
    }

/**
 * Label denoting park operation of a thread.
 *
 * @param kind the kind of this label: [LabelKind.Request] or [LabelKind.Response].
 * @param threadId the thread id of the parked thread.
 */
data class ParkLabel(
    override val kind: LabelKind,
    override val threadId: Int,
) : ParkingEventLabel(
    kind = kind,
    threadId = threadId,
    isBlocking = true,
    isUnblocked = (kind != LabelKind.Request),
) {
    init {
        require(isRequest || isResponse || isReceive)
    }

    override fun toString(): String =
        super.toString()
}

/**
 * Label denoting unpark operation of a thread.
 *
 * @param threadId the thread id of the thread to unpark.
 */
data class UnparkLabel(
    override val threadId: Int,
) : ParkingEventLabel(LabelKind.Send, threadId) {

    override fun toString(): String =
        super.toString()
}


/* ************************************************************************* */
/*      Coroutine labels                                                     */
/* ************************************************************************* */


/**
 * Base class of all coroutine operations event labels.
 *
 * It stores common information about coroutine operation,
 * such as the thread id and actor id of the coroutine.
 *
 * @param kind The kind of this label.
 * @param threadId The thread id of the coroutine.
 * @param actorId The actor id of the coroutine.
 * @param isBlocking Flag indicating whether this label is blocking.
 * @param isUnblocked Flag indicating whether this blocking label is already unblocked.
 */
sealed class CoroutineLabel(
    override val kind: LabelKind,
    open val threadId: Int,
    open val actorId: Int,
    isBlocking: Boolean = false,
    isUnblocked: Boolean = true,
) : EventLabel(
    kind = kind, 
    isBlocking = isBlocking, 
    isUnblocked = isUnblocked
) {

    override fun toString(): String {
        val operationKind = when (this) {
            is CoroutineSuspendLabel -> "Suspend"
            is CoroutineResumeLabel  -> "Resume"
        }
        val status = when {
            this is CoroutineSuspendLabel && !cancelled -> ": resumed"
            this is CoroutineSuspendLabel &&  cancelled -> ": cancelled"
            else -> ""
        }
        return "${operationKind}${kind.repr}($threadId, $actorId)$status"
    }

}

/**
 * Label denoting coroutine suspend operation.
 *
 * @param kind The kind of this label: [LabelKind.Request] or [LabelKind.Response].
 * @param threadId The thread id of the coroutine.
 * @param actorId The actor id of the coroutine.
 * @param cancelled The flag indicating whether the coroutine was canceled.
 * @param promptCancellation The flag indicating whether the coroutine is subject
 *   to the prompt cancellation guarantee.
 */
data class CoroutineSuspendLabel(
    override val kind: LabelKind,
    override val threadId: Int,
    override val actorId: Int,
    val cancelled: Boolean = false,
    val promptCancellation: Boolean = false,
    // TODO: should we also keep resume value?
) : CoroutineLabel(
    kind = kind,
    threadId = threadId,
    actorId = actorId,
    isBlocking = true,
    isUnblocked = (kind != LabelKind.Request),
) {
    init {
        require(isRequest || isResponse || isReceive)
        require(promptCancellation implies isRequest)
        require(cancelled implies (isResponse || isReceive))
    }

    override fun toString(): String =
        super.toString()

}

/**
 * Label denoting coroutine resumption operation.
 *
 * @param threadId The thread id of the coroutine to be resumed.
 * @param actorId The actor id of the coroutine to be resumed.
 */
data class CoroutineResumeLabel(
    override val threadId: Int,
    override val actorId: Int,
    // TODO: should we also keep resume value?
) : CoroutineLabel(LabelKind.Send, threadId, actorId) {

    override fun toString(): String =
        super.toString()

}


/* ************************************************************************* */
/*      Miscellaneous                                                        */
/* ************************************************************************* */


/**
 * Label denoting an actor operation: either start or an end of actor method execution.
 *
 * @property spanKind The kind of the actor label (see [SpanLabelKind]).
 * @property threadId The id of the thread on which the actor is executing.
 * @property actor The actor descriptor.
 */
// TODO: generalize actor labels to method call/return labels?
data class ActorLabel(
    override val spanKind: SpanLabelKind,
    val threadId: ThreadId,
    val actor: Actor
) : EventLabel(
    kind = spanKind.toLabelKind(), 
    spanKind = spanKind
)

/**
 * Label denoting a call of the random number generator.
 *
 * @property value The generated random value.
 */
data class RandomLabel(val value: Int): EventLabel(kind = LabelKind.Send)


// special code location used for initializing write events
private const val INIT_CODE_LOCATION = -1

// special code location denoting unknown code location
private const val UNKNOWN_CODE_LOCATION = -2