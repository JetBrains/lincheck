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
import org.jetbrains.lincheck.trace.printing.*
import org.jetbrains.lincheck.trace.serialization.*
import org.jetbrains.lincheck.trace.printing.DefaultTRArrayTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRCatchTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRFieldTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRLineBreakpointSnapshotTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRLocalVariableTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRLoopIterationTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRLoopTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRMethodCallTracePointPrinter.append
import org.jetbrains.lincheck.trace.printing.DefaultTRThrowTracePointPrinter.append
import java.io.DataInput
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
        if (this is TraceContainerTracePoint) {
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

sealed class TraceContainerTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    eventId: Int
) : TracePoint(context, threadId, codeLocationId, eventId) {
    internal var childrenDiffStatuses: EnumSet<DiffStatus>? = null

    val subtreeDiffStatuses: Set<DiffStatus> get() = childrenDiffStatuses ?: SUBTREE_STATUS_UNCHANGED

    internal abstract fun loadFooter(inp: DataInput)

    companion object {
        private val SUBTREE_STATUS_UNCHANGED = EnumSet.of(DiffStatus.UNCHANGED)
    }
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
) : TraceContainerTracePoint(context, threadId, codeLocationId, eventId) {
    var result: TraceValue = TraceUnfinishedMethodResult
    var exceptionClassName: String? = null

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

    fun setExceptionResult(exception: Throwable) {
        exceptionClassName = exception::class.java.simpleName
    }

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

    override fun loadFooter(inp: DataInput) {
        inp.readMethodCallTracePointFooter(context, this)
    }

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

class TraceLoopTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val loopId: Int,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TraceContainerTracePoint(context, threadId, codeLocationId, eventId) {

    internal constructor(
        context: TraceContext,
        threadId: Int,
        codeLocationId: Int,
        loopId: Int,
        eventId: Int,
        iterations: Int
    ) : this(context, threadId, codeLocationId, loopId, eventId) {
        this.iterations = iterations
    }

    // This field is not serialized to disk, because it is computable from the number of children of the
    // loop trace point. Basically the number of children is equal to the number of loop iterations.
    // On trace point footer loading this variable will be restored.
    var iterations: Int = 0
        internal set

    fun incrementIterations(): Int {
        return iterations++
    }

    override fun loadFooter(inp: DataInput) {
        inp.readLoopTracePointFooter(this)
    }

    override fun toText(appendable: TraceAppendable) {
        appendable.append(tracePoint = this)
    }
}

class TraceLoopIterationTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val loopId: Int,
    val loopIteration: Int,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TraceContainerTracePoint(context, threadId, codeLocationId, eventId) {

    override fun loadFooter(inp: DataInput) {}

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

