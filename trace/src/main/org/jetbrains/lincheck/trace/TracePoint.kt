/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace

import org.jetbrains.lincheck.descriptors.*
import org.jetbrains.lincheck.trace.TraceLoopTracePoint.Companion.UNKNOWN_ITERATIONS_COUNT
import org.jetbrains.lincheck.trace.printing.*
import org.jetbrains.lincheck.trace.printing.DefaultTRArrayTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRCatchTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRFieldTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRLineBreakpointSnapshotTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRLocalVariableTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRLoopEndTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRLoopIterationEndTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRLoopIterationTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRLoopTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRMethodCallResultTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRMethodCallTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRThrowTracePointPrinter.append
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

private val EVENT_ID_GENERATOR = AtomicInteger(0)

/**
 * Describes status of tracepoint in trace diff
 */
enum class DiffStatus {
    /**
     * This tracepoint is identical in two traces.
     */
    UNCHANGED,

    /**
     * This tracepoint was found in the first, but not in the second trace, when diff was created.
     */
    REMOVED,

    /**
     * This tracepoint was not found in the first, but found in the second trace, when diff was created.
     */
    ADDED,

    /**
     * Tracepoint is tracepoint from left (old) trace which was edited in right (new) trace.
     *
     * It can be seen as "removed" if no editing information is needed. This tracepoint must be followed
     * with tracepoint of same type with status [EDITED_NEW].
     * Container tracepoint with this status will not have children, as children are linked to next
     * [EDITED_NEW] tracepoint.
     *
     * For example, if it is a method called tracepoint, it has the same method in both traces but differs in arguments values.
     */
    EDITED_OLD,

    /**
     * Tracepoint is tracepoint from right (new) trace which was edited in respect with left (old) trace.
     *
     * It can be seen as "added" if no editing information is needed, and is pair for previous sibling which should be
     * [EDITED_OLD]. Difference between subtrees started from tracepoints which was compared to created here
     * will be attached to this tracepoint, and its partner with [EDITED_OLD] status will not have any children.
     *
     * For example, if it is a method called tracepoint, it has the same method in both traces but differs in arguments values.
     */
    EDITED_NEW;

    fun toLeaf(): DiffStatus =
        when (this) {
            UNCHANGED -> UNCHANGED
            REMOVED -> REMOVED
            ADDED -> ADDED
            EDITED_OLD -> REMOVED
            EDITED_NEW -> ADDED
        }
}

sealed class TracePoint(
    internal val context: TraceContext,
    val threadId: Int,
    val codeLocationId: Int,
    val eventId: Int
) {
    /**
     * Diff status of this trace point.
     *
     * `null` means tracepoint doesn't belong to diff, and is part of simple trace.
     */
    var diffStatus: DiffStatus? = null
        internal set(value)  {
            require(value != null)  { "Diff status cannot be set to null"}
            check(field == null) { "Diff status can be changed only once" }
            field = value
        }

    internal fun copyDiffStatus(other: TracePoint) {
        check(diffStatus == null) { "Diff status can be changed only once" }
        if (other.diffStatus == null) return
        if (this is TraceContainerHeaderTracePoint) {
            diffStatus = other.diffStatus
        } else {
            diffStatus = other.diffStatus?.toLeaf()
        }
    }

    val codeLocation: StackTraceElement get() = context.stackTrace(codeLocationId)
    val activeLocals: List<ActiveLocal> get() = context.activeLocals(codeLocationId) ?: emptyList() // used in plugin
    val accessPath: AccessPath? get() = context.accessPath(codeLocationId)

    /**
     * Renders this trace point as text, with [parent] — this point's parent in the trace tree —
     * providing the context for parent-dependent rendering decisions.
     */
    fun toText(verbose: Boolean, parent: TracePoint? = null): String {
        val sb = StringBuilder()
        toText(DefaultTRTextAppendable(sb, verbose), parent)
        return sb.toString()
    }

    open fun toText(appendable: TraceAppendable, parent: TracePoint?): Unit = toText(appendable)

    abstract fun toText(appendable: TraceAppendable)
}

/**
 * A trace point which has children, written to a trace as two records:
 * this one, opening the container, and a [TraceContainerFooterTracePoint] closing it.
 */
sealed class TraceContainerHeaderTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    eventId: Int
) : TracePoint(context, threadId, codeLocationId, eventId) {
    internal var childrenDiffStatuses: EnumSet<DiffStatus>? = null

    val subtreeDiffStatuses: Set<DiffStatus> get() = childrenDiffStatuses ?: SUBTREE_STATUS_UNCHANGED

    /**
     * The closing side of this container.
     *
     * `null` until the container is completed while recording,
     * or until its closing trace point is read back while loading a trace.
     */
    abstract var footerTracePoint: TraceContainerFooterTracePoint?

    /**
     * Returns the closing side of this container,
     * creating it from the container's current state if the container was never completed explicitly.
     */
    abstract fun completeTracePoint(): TraceContainerFooterTracePoint

    /**
     * Returns the closing side of this container, as [completeTracePoint],
     * additionally deriving from the container's [children] the closing data that depends on them.
     *
     * Containers whose closing record does not depend on their children ignore [children].
     */
    open fun completeTracePoint(children: List<TracePoint>): TraceContainerFooterTracePoint =
        completeTracePoint()

    companion object {
        private val SUBTREE_STATUS_UNCHANGED = EnumSet.of(DiffStatus.UNCHANGED)
    }
}

/**
 * Attaches the [footerTracePoint] just read from a trace to the container it closes.
 *
 * @throws IllegalStateException if `this` is not the container [footerTracePoint] belongs to.
 */
internal fun TraceContainerHeaderTracePoint.attachFooterTracePoint(footerTracePoint: TraceContainerFooterTracePoint) {
    check(footerTracePoint.containerEventId == eventId) {
        "Closing trace point refers to container #${footerTracePoint.containerEventId}, " +
        "expected #$eventId, broken file"
    }
    this.footerTracePoint = footerTracePoint
}

/**
 * The closing side of a [TraceContainerHeaderTracePoint], carrying the container data
 * which becomes known only when the container ends.
 *
 * The two sides are linked by [containerEventId], which makes the trace decodable into a tree
 * while reading strictly forward: no seeking to a container's closing record is required.
 */
sealed class TraceContainerFooterTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    /** [TracePoint.eventId] of the container this trace point closes. */
    val containerEventId: Int,
    eventId: Int
) : TracePoint(context, threadId, codeLocationId, eventId)

/**
 * Narrows a closing trace point assigned to [container] to the type that container is closed by.
 *
 * @throws IllegalStateException if [this] closes a different kind of container.
 */
private inline fun <reified T : TraceContainerFooterTracePoint> TraceContainerFooterTracePoint?.asFooterTracePointOf(
    container: TraceContainerHeaderTracePoint
): T? = when (this) {
    null -> null
    is T -> this
    else -> error(
        "${container::class.java.simpleName} cannot be closed by ${this::class.java.simpleName}, broken file"
    )
}

class TraceMethodCallTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val methodId: Int,
    val obj: TraceValue,
    val parameters: List<TraceValue>,
    val flags: Short = 0,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TraceContainerHeaderTracePoint(context, threadId, codeLocationId, eventId) {
    /** Closing side of this call, carrying its outcome; `null` while the call is still running. */
    var resultTracePoint: TraceMethodCallResultTracePoint? = null

    override var footerTracePoint: TraceContainerFooterTracePoint?
        get() = resultTracePoint
        set(value) {
            resultTracePoint = value.asFooterTracePointOf<TraceMethodCallResultTracePoint>(container = this)
        }

    val result: TraceValue get() = resultTracePoint?.result ?: TraceUnfinishedMethodResult
    val exceptionClassName: String? get() = resultTracePoint?.exceptionClassName

    // TODO Make parametrized
    val methodDescriptor: MethodDescriptor get() = context.methodPool[methodId]
    val classDescriptor: ClassDescriptor get() = methodDescriptor.classDescriptor

    // Shortcuts
    val className: String get() = methodDescriptor.className
    val methodName: String get() = methodDescriptor.methodName
    val argumentNames: List<AccessPath?> get() = context.methodCallArgumentNames(codeLocationId) ?: emptyList()
    val argumentTypes: List<Types.Type> get() = methodDescriptor.argumentTypes
    val returnType: Types.Type get() = methodDescriptor.returnType

    fun isStatic(): Boolean = obj is TraceNull

    fun isConstructor(): Boolean = methodName == "<init>"

    /**
     * Checks whether this call happens inside a method of the same (or companion) class.
     *
     * [parentCall] is this call's parent in the trace tree.
     */
    fun isCalledFromDefiningClass(
        parentCall: TraceMethodCallTracePoint? = null,
    ): Boolean {
        val parent = parentCall ?: return false
        return className.let {
            it == parent.className ||
            it.removeCompanionSuffix() == parent.className
        }
    }

    /**
     * Records the value returned by this call, keeping the already recorded [exceptionClassName], if any.
     *
     * @return the closing tracepoint this call is now completed by.
     */
    fun setResult(result: TraceValue): TraceMethodCallResultTracePoint =
        createResultTracePoint(result, exceptionClassName).also { resultTracePoint = it }

    /**
     * Records that this call completed by throwing [exception], keeping the already recorded [result].
     *
     * @return the closing tracepoint this call is now completed by.
     */
    fun setExceptionResult(exception: Throwable): TraceMethodCallResultTracePoint =
        createResultTracePoint(result, exception::class.java.simpleName).also { resultTracePoint = it }

    /**
     * @return `true` if tracing of the thread was ended before this method returned its value, `false` otherwise.
     */
    fun isMethodUnfinished(): Boolean =
        result is TraceUnfinishedMethodResult

    /**
     * Returns `true` if method completion was not tracked and its return value is unknown, `false` otherwise.
     */
    fun isMethodResultUntracked(): Boolean =
        result is TraceUntrackedMethodResult

    /**
     * @return `true` if tracing of the thread was started after this method call and there some missing tracepoints, `false` otherwise.
     */
    fun isMethodIncomplete(): Boolean =
        (flags.toInt() and INCOMPLETE_METHOD_FLAG) != 0

    fun isSuperConstructorCall(): Boolean =
        (flags.toInt() and SUPER_CONSTRUCTOR_CALL_FLAG) != 0

    override fun completeTracePoint(): TraceMethodCallResultTracePoint =
        resultTracePoint ?: createResultTracePoint(result, exceptionClassName).also { resultTracePoint = it }

    /**
     * Completes this call with the [TraceMethodCallResultTracePoint] carried as the last of its [children],
     * if there is one, instead of deriving the closing tracepoint from [result] and [exceptionClassName].
     *
     * @throws IllegalStateException if that closing tracepoint belongs to another call.
     */
    override fun completeTracePoint(children: List<TracePoint>): TraceMethodCallResultTracePoint {
        if (resultTracePoint == null) {
            (children.lastOrNull() as? TraceMethodCallResultTracePoint)?.let { attachFooterTracePoint(it) }
        }
        return completeTracePoint()
    }

    private fun createResultTracePoint(result: TraceValue, exceptionClassName: String?) =
        TraceMethodCallResultTracePoint(
            context = context,
            threadId = threadId,
            codeLocationId = codeLocationId,
            methodCallEventId = eventId,
            result = result,
            exceptionClassName = exceptionClassName,
        )

    override fun toText(appendable: TraceAppendable) {
        appendable.append(tracePoint = this)
    }

    override fun toText(appendable: TraceAppendable, parent: TracePoint?) {
        appendable.append(tracePoint = this, parentCall = parent as? TraceMethodCallTracePoint)
    }

    companion object {
        // Bit flag that tells that the method was not tracked from its start and has some missing tracepoints
        const val INCOMPLETE_METHOD_FLAG: Int = 1
        // Bit flag set on a method-call trace point when the constructor invocation is a super()/this() delegation
        const val SUPER_CONSTRUCTOR_CALL_FLAG: Int = 2
    }
}

class TraceMethodCallResultTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    methodCallEventId: Int,
    val result: TraceValue,
    /** Simple name of the class of the exception thrown by the call, or `null` if it didn't throw. */
    val exceptionClassName: String? = null,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TraceContainerFooterTracePoint(context, threadId, codeLocationId, methodCallEventId, eventId) {

    override fun toText(appendable: TraceAppendable) {
        appendable.append(tracePoint = this)
    }
}

class TraceLoopTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val loopId: Int,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TraceContainerHeaderTracePoint(context, threadId, codeLocationId, eventId) {

    /** Number of completed iterations, or [UNKNOWN_ITERATIONS_COUNT] until the loop footer is available. */
    val iterations: Int get() = loopEndTracePoint?.iterations ?: UNKNOWN_ITERATIONS_COUNT

    /** Closing side of this loop; `null` while the loop is still running. */
    var loopEndTracePoint: TraceLoopEndTracePoint? = null

    override var footerTracePoint: TraceContainerFooterTracePoint?
        get() = loopEndTracePoint
        set(value) {
            loopEndTracePoint = value.asFooterTracePointOf<TraceLoopEndTracePoint>(container = this)
        }

    override fun completeTracePoint(): TraceLoopEndTracePoint =
        loopEndTracePoint ?: createLoopEndTracePoint(UNKNOWN_ITERATIONS_COUNT)

    /** Completes this loop, counting its iterations as the [TraceLoopIterationTracePoint]s among [children]. */
    override fun completeTracePoint(children: List<TracePoint>): TraceLoopEndTracePoint =
        loopEndTracePoint ?: createLoopEndTracePoint(children.count { it is TraceLoopIterationTracePoint })

    private fun createLoopEndTracePoint(iterations: Int): TraceLoopEndTracePoint =
        TraceLoopEndTracePoint(
            context = context,
            threadId = threadId,
            codeLocationId = codeLocationId,
            loopEventId = eventId,
            iterations = iterations,
        ).also { loopEndTracePoint = it }

    override fun toText(appendable: TraceAppendable) {
        appendable.append(tracePoint = this)
    }

    companion object {
        const val UNKNOWN_ITERATIONS_COUNT = -1
    }
}

class TraceLoopEndTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    loopEventId: Int,
    val iterations: Int,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TraceContainerFooterTracePoint(context, threadId, codeLocationId, loopEventId, eventId) {

    override fun toText(appendable: TraceAppendable) {
        appendable.append(tracePoint = this)
    }
}

/** A single iteration of a [TraceLoopTracePoint]. */
class TraceLoopIterationTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val loopId: Int,
    val loopIteration: Int,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TraceContainerHeaderTracePoint(context, threadId, codeLocationId, eventId) {
    /** Closing side of this iteration; `null` while the iteration is still running. */
    var iterationEndTracePoint: TraceLoopIterationEndTracePoint? = null

    override var footerTracePoint: TraceContainerFooterTracePoint?
        get() = iterationEndTracePoint
        set(value) {
            iterationEndTracePoint = value.asFooterTracePointOf<TraceLoopIterationEndTracePoint>(container = this)
        }

    override fun completeTracePoint(): TraceLoopIterationEndTracePoint =
        iterationEndTracePoint ?: TraceLoopIterationEndTracePoint(
            context = context,
            threadId = threadId,
            codeLocationId = codeLocationId,
            loopIterationEventId = eventId,
        ).also { iterationEndTracePoint = it }

    override fun toText(appendable: TraceAppendable) {
        appendable.append(tracePoint = this)
    }
}

/**
 * The closing side of a [TraceLoopIterationTracePoint].
 *
 * An iteration has nothing to report on completion; the record exists so that every container
 * is delimited the same way on the wire.
 */
class TraceLoopIterationEndTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    loopIterationEventId: Int,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TraceContainerFooterTracePoint(context, threadId, codeLocationId, loopIterationEventId, eventId) {

    override fun toText(appendable: TraceAppendable) {
        appendable.append(tracePoint = this)
    }
}

sealed class TraceFieldTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val fieldId: Int,
    val obj: TraceValue,
    val value: TraceValue,
    eventId: Int
) : TracePoint(context, threadId, codeLocationId, eventId) {

    internal abstract fun accessSymbol(): String

    // TODO Make parametrized
    val fieldDescriptor: FieldDescriptor get() = context.fieldPool[fieldId]
    val classDescriptor: ClassDescriptor get() = fieldDescriptor.classDescriptor

    // Shortcuts
    val className: String get() = fieldDescriptor.className
    val name: String get() = fieldDescriptor.fieldName
    val isStatic: Boolean get() = fieldDescriptor.isStatic
    val isFinal: Boolean get() = fieldDescriptor.isFinal

    override fun toText(appendable: TraceAppendable) {
        appendable.append(tracePoint = this)
    }
}

class TraceReadFieldTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    fieldId: Int,
    obj: TraceValue,
    value: TraceValue,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TraceFieldTracePoint(context, threadId, codeLocationId,  fieldId, obj, value, eventId) {

    override fun accessSymbol(): String = READ_ACCESS_SYMBOL
}

class TraceWriteFieldTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    fieldId: Int,
    obj: TraceValue,
    value: TraceValue,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TraceFieldTracePoint(context, threadId, codeLocationId,  fieldId, obj, value, eventId) {

    override fun accessSymbol(): String = WRITE_ACCESS_SYMBOL
}

sealed class TraceLocalVariableTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val localVariableId: Int,
    val value: TraceValue,
    eventId: Int
) : TracePoint(context, threadId, codeLocationId, eventId) {

    internal abstract fun accessSymbol(): String

    // TODO Make parametrized
    val variableDescriptor: VariableDescriptor get() = context.variablePool[localVariableId]
    val name: String get() = variableDescriptor.name

    override fun toText(appendable: TraceAppendable) {
        appendable.append(tracePoint = this)
    }
}

class TraceReadLocalVariableTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    localVariableId: Int,
    value: TraceValue,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TraceLocalVariableTracePoint(context, threadId, codeLocationId, localVariableId, value, eventId) {

    override fun accessSymbol(): String = READ_ACCESS_SYMBOL
}

class TraceWriteLocalVariableTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    localVariableId: Int,
    value: TraceValue,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TraceLocalVariableTracePoint(context, threadId, codeLocationId, localVariableId, value, eventId) {

    override fun accessSymbol(): String = WRITE_ACCESS_SYMBOL
}

class TraceSnapshotLineBreakpointTracePoint(
    context: TraceContext,
    codeLocationId: Int,
    threadId: Int,
    val breakpointUuid: UUID,
    val stackTraceCodeLocationIds: List<Int>,
    val currentTimeMillis: Long,
    val locals: List<TraceValue>,
    val watches: List<TraceValue> = emptyList(),
    val traceId: String?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
): TracePoint(context, threadId, codeLocationId, eventId) {

    val threadName: String
        get() = context.getThreadName(threadId)

    val stackTrace: List<StackTraceElement>
        get() = stackTraceCodeLocationIds.map { context.stackTrace(it) }

    override fun toText(appendable: TraceAppendable) {
        appendable.append(tracePoint = this)
    }
}

sealed class TraceArrayTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val array: TraceValue,
    val index: Int,
    val value: TraceValue,
    eventId: Int
) : TracePoint(context, threadId, codeLocationId, eventId) {

    internal abstract fun accessSymbol(): String

    override fun toText(appendable: TraceAppendable) {
        appendable.append(tracePoint = this)
    }
}

class TraceReadArrayTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    array: TraceValue,
    index: Int,
    value: TraceValue,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TraceArrayTracePoint(context, threadId, codeLocationId, array, index, value, eventId) {

    override fun accessSymbol(): String = READ_ACCESS_SYMBOL
}

class TraceWriteArrayTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    array: TraceValue,
    index: Int,
    value: TraceValue,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TraceArrayTracePoint(context, threadId, codeLocationId, array, index, value, eventId) {

    override fun accessSymbol(): String = WRITE_ACCESS_SYMBOL
}

sealed class TraceExceptionProcessingTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val exception: TraceValue,
    eventId: Int
) : TracePoint(context, threadId, codeLocationId, eventId)

class TraceThrowTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    exception: TraceValue,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TraceExceptionProcessingTracePoint(context, threadId, codeLocationId, exception, eventId) {

    override fun toText(appendable: TraceAppendable) {
        appendable.append(tracePoint = this)
    }

}

class TraceCatchTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    exception: TraceValue,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TraceExceptionProcessingTracePoint(context, threadId, codeLocationId, exception, eventId) {

    override fun toText(appendable: TraceAppendable) {
        appendable.append(tracePoint = this)
    }
}

const val READ_ACCESS_SYMBOL  = "➜"
const val WRITE_ACCESS_SYMBOL = "="

val TraceLoopTracePoint.iterationsAsString: String
    get() = if (iterations == UNKNOWN_ITERATIONS_COUNT) "<unknown>"
            else iterations.toString()
