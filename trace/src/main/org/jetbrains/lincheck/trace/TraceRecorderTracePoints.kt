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
import org.jetbrains.lincheck.trace.storage.AddressIndex
import org.jetbrains.lincheck.util.collections.ChunkedList
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

var INJECTIONS_VOID_OBJECT: Any? = null

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

sealed class TRTracePoint(
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

    internal fun copyDiffStatus(other: TRTracePoint) {
        check(diffStatus == null) { "Diff status can be changed only once" }
        if (other.diffStatus == null) return
        if (this is TRContainerTracePoint) {
            diffStatus = other.diffStatus
        } else {
            diffStatus = other.diffStatus?.toLeaf()
        }
    }

    /**
     * Serializes this tracepoint via the given [TraceWriter] orchestration.
     *
     * Each concrete subclass must:
     *   1. call [saveReferences] (to register prerequisite descriptors via memoization),
     *   2. call `out.startWriteAnyTracepoint()`,
     *   3. call `out.writeTRTracePoint(this)` — the top-level dispatcher in
     *      `TraceBinarySerialization.kt` that emits the kind byte, common header, and the
     *      subclass-specific body bytes (including children diff-statuses for containers),
     *   4. call `out.endWriteLeafTracepoint()` or `out.endWriteContainerTracepointHeader(eventId)`.
     */
    internal abstract fun save(out: TraceWriter)

    internal open fun saveReferences(out: TraceWriter) {
        out.writeCodeLocation(codeLocationId)
    }

    val codeLocation: StackTraceElement get() = context.stackTrace(codeLocationId)
    val activeLocals: List<ActiveLocal> get() = context.activeLocals(codeLocationId) ?: emptyList() // used in plugin
    val accessPath: AccessPath? get() = context.accessPath(codeLocationId)

    fun toText(verbose: Boolean): String {
        val sb = StringBuilder()
        toText(DefaultTRTextAppendable(sb, verbose))
        return sb.toString()
    }

    abstract fun toText(appendable: TRAppendable)
}

sealed class TRContainerTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    var parentTracePoint: TRContainerTracePoint? = null,
    eventId: Int
) : TRTracePoint(context, threadId, codeLocationId, eventId) {
    protected var children: ChunkedList<TRTracePoint> = ChunkedList()
        private set

    protected var childrenAddresses: AddressIndex = AddressIndex.create()
        private set

    internal var childrenDiffStatuses: EnumSet<DiffStatus>? = null

    val subtreeDiffStatuses: Set<DiffStatus> get() = childrenDiffStatuses ?: SUBTREE_STATUS_UNCHANGED

    // We need this to have unmodifiable list here, ad "children" list needs some bookkeeping
    val events: List<TRTracePoint?> get() = children

    private fun TRTracePoint.setParentIfContainer(parent: TRContainerTracePoint) {
        if (this !is TRContainerTracePoint) return
        parentTracePoint = parent
    }

    internal fun addChildAddress(address: Long) {
        childrenAddresses.add(address)
        children.add(null)
    }

    // These two methods are left public intentionally to allow external post-processors
    // to clone traecepoint with children
    fun copyChildrenAddresses(other: TRContainerTracePoint) {
        for (i in 0 ..< other.childrenAddresses.size) {
            addChildAddress(other.childrenAddresses[i])
        }
    }

    fun addChild(child: TRTracePoint, address: Long = -1) {
        childrenAddresses.add(address)
        children.add(child)

        addChildStatus(child)
        child.setParentIfContainer(this)
    }

    internal fun getChildAddress(index: Int): Long {
        require(index in 0 ..< children.size) {
            "Index $index out of range 0..<${children.size}"
        }
        return childrenAddresses[index]
    }

    internal fun replaceChildren(from: TRContainerTracePoint) {
        children = from.children
        childrenAddresses = from.childrenAddresses
        childrenDiffStatuses = null
        // Copy this, as all children could be null in case of compressing post-processor
        val cds = from.childrenDiffStatuses
        if (cds != null) {
            childrenDiffStatuses = EnumSet<DiffStatus>.copyOf(cds)
        }
        // .filter can be very expensive in case of huge children list
        from.children.forEach {
            it?.setParentIfContainer(this)
        }
    }

    internal fun loadChild(index: Int, child: TRTracePoint) {
        require(index in 0 ..< children.size) {
            "Index $index out of range 0..<${children.size}"
        }
        // Should we check for override? Lets skip for now
        children[index] = child
        addChildStatus(child)
        child.setParentIfContainer(this)
    }

    fun unloadChild(index: Int) {
        require(index in 0 ..< children.size) {
            "Index $index out of range 0..<${children.size}"
        }
        children[index] = null
    }

    fun unloadAllChildren() {
        children.forgetAll()
    }

    private fun addChildStatus(child: TRTracePoint) {
        val s = child.diffStatus
        if (s == null || s == DiffStatus.UNCHANGED) return
        if (childrenDiffStatuses == null) {
            childrenDiffStatuses = EnumSet.of(s)
        } else {
            childrenDiffStatuses!!.add(s)
        }
    }

    private fun removeChildStatus(child: TRTracePoint) {
        val s = child.diffStatus
        if (childrenDiffStatuses == null || s == null || s == DiffStatus.UNCHANGED) return
        childrenDiffStatuses!!.remove(s)
        if (childrenDiffStatuses!!.isEmpty()) childrenDiffStatuses = null
    }

    internal abstract fun saveFooter(out: TraceWriter)
    internal abstract fun loadFooter(inp: DataInput)

    companion object {
        private val SUBTREE_STATUS_UNCHANGED = EnumSet.of(DiffStatus.UNCHANGED)
    }
}

class TRMethodCallTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val methodId: Int,
    val obj: TRValue?,
    val parameters: List<TRValue?>,
    val flags: Short = 0,
    parentTracePoint: TRContainerTracePoint? = null,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRContainerTracePoint(context, threadId, codeLocationId, parentTracePoint, eventId) {
    var result: TRValue? = null
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

    fun isStatic(): Boolean = obj == null

    fun isConstructor(): Boolean = methodName == "<init>"

    fun isCalledFromDefiningClass(): Boolean {
        val parent = (parentTracePoint as? TRMethodCallTracePoint) ?: return false
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
        result == TR_OBJECT_UNFINISHED_METHOD_RESULT

    /**
     * Returns `true` if method completion was not tracked and its return value is unknown, `false` otherwise.
     */
    fun isMethodResultUntracked(): Boolean =
        result == TR_OBJECT_UNTRACKED_METHOD_RESULT

    /**
     * @return `true` if tracing of the thread was started after this method call and there some missing tracepoints, `false` otherwise.
     */
    fun isMethodIncomplete(): Boolean =
        (flags.toInt() and INCOMPLETE_METHOD_FLAG) != 0

    override fun save(out: TraceWriter) {
        saveReferences(out)
        out.startWriteAnyTracepoint()
        out.writeTRTracePoint(this)
        // Mark this as container tracepoint which could have children and will have footer
        out.endWriteContainerTracepointHeader(eventId)
    }

    override fun saveReferences(out: TraceWriter) {
        super.saveReferences(out)
        out.writeMethodDescriptor(methodId)
        out.preWriteTRValue(obj)
        parameters.forEach {
            out.preWriteTRValue(it)
        }
    }

    override fun saveFooter(out: TraceWriter) {
        out.preWriteTRValue(result)

        // Mark this as a container tracepoint footer
        out.startWriteContainerTracepointFooter()
        out.writeMethodCallTracePointFooter(this)
        out.endWriteContainerTracepointFooter(eventId)
    }

    override fun loadFooter(inp: DataInput) {
        childrenAddresses.finishWrite()
        inp.readMethodCallTracePointFooter(context, this)
    }

    override fun toText(appendable: TRAppendable) {
        appendable.append(tracePoint = this)
    }

    companion object {
        // Flag that tells that the method was not tracked from its start and has some missing tracepoints
        const val INCOMPLETE_METHOD_FLAG: Int = 1
    }
}

class TRLoopTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val loopId: Int,
    parentTracePoint: TRContainerTracePoint? = null,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRContainerTracePoint(context, threadId, codeLocationId, parentTracePoint, eventId) {

    internal constructor(
        context: TraceContext,
        threadId: Int,
        codeLocationId: Int,
        loopId: Int,
        parentTracePoint: TRContainerTracePoint?,
        eventId: Int,
        iterations: Int
    ) : this(context, threadId, codeLocationId, loopId, parentTracePoint, eventId) {
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

    override fun save(out: TraceWriter) {
        saveReferences(out)
        out.startWriteAnyTracepoint()
        out.writeTRTracePoint(this)
        // Mark this as container tracepoint which could have children and will have footer
        out.endWriteContainerTracepointHeader(eventId)
    }

    override fun saveFooter(out: TraceWriter) {
        // Mark this as a container tracepoint footer
        out.startWriteContainerTracepointFooter()
        out.writeLoopTracePointFooter(this)
        out.endWriteContainerTracepointFooter(eventId)
    }

    override fun loadFooter(inp: DataInput) {
        childrenAddresses.finishWrite()
        inp.readLoopTracePointFooter(this)
    }

    override fun toText(appendable: TRAppendable) {
        appendable.append(tracePoint = this)
    }
}

class TRLoopIterationTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val loopId: Int,
    val loopIteration: Int,
    parentTracePoint: TRContainerTracePoint? = null,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRContainerTracePoint(context, threadId, codeLocationId, parentTracePoint, eventId) {

    override fun save(out: TraceWriter) {
        saveReferences(out)
        out.startWriteAnyTracepoint()
        out.writeTRTracePoint(this)
        // Mark this as container tracepoint which could have children and will have footer
        out.endWriteContainerTracepointHeader(eventId)
    }

    override fun saveFooter(out: TraceWriter) {
        // Mark this as a container tracepoint footer (no footer body bytes for this kind).
        out.startWriteContainerTracepointFooter()
        out.endWriteContainerTracepointFooter(eventId)
    }

    override fun loadFooter(inp: DataInput) {
        childrenAddresses.finishWrite()
    }

    override fun toText(appendable: TRAppendable) {
        appendable.append(tracePoint = this)
    }
}

sealed class TRFieldTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val fieldId: Int,
    val obj: TRValue?,
    val value: TRValue?,
    eventId: Int
) : TRTracePoint(context, threadId, codeLocationId, eventId) {

    internal abstract fun accessSymbol(): String

    // TODO Make parametrized
    val fieldDescriptor: FieldDescriptor get() = context.fieldPool[fieldId]
    val classDescriptor: ClassDescriptor get() = fieldDescriptor.classDescriptor

    // Shortcuts
    val className: String get() = fieldDescriptor.className
    val name: String get() = fieldDescriptor.fieldName
    val isStatic: Boolean get() = fieldDescriptor.isStatic
    val isFinal: Boolean get() = fieldDescriptor.isFinal

    override fun save(out: TraceWriter) {
        saveReferences(out)
        out.startWriteAnyTracepoint()
        out.writeTRTracePoint(this)
        out.endWriteLeafTracepoint()
    }

    override fun saveReferences(out: TraceWriter) {
        super.saveReferences(out)
        out.writeFieldDescriptor(fieldId)
        out.preWriteTRValue(obj)
        out.preWriteTRValue(value)
    }

    override fun toText(appendable: TRAppendable) {
        appendable.append(tracePoint = this)
    }
}

class TRReadFieldTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    fieldId: Int,
    obj: TRValue?,
    value: TRValue?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRFieldTracePoint(context, threadId, codeLocationId,  fieldId, obj, value, eventId) {

    override fun accessSymbol(): String = READ_ACCESS_SYMBOL
}

class TRWriteFieldTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    fieldId: Int,
    obj: TRValue?,
    value: TRValue?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRFieldTracePoint(context, threadId, codeLocationId,  fieldId, obj, value, eventId) {

    override fun accessSymbol(): String = WRITE_ACCESS_SYMBOL
}

sealed class TRLocalVariableTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val localVariableId: Int,
    val value: TRValue?,
    eventId: Int
) : TRTracePoint(context, threadId, codeLocationId, eventId) {

    internal abstract fun accessSymbol(): String

    // TODO Make parametrized
    val variableDescriptor: VariableDescriptor get() = context.variablePool[localVariableId]
    val name: String get() = variableDescriptor.name

    override fun save(out: TraceWriter) {
        saveReferences(out)
        out.startWriteAnyTracepoint()
        out.writeTRTracePoint(this)
        out.endWriteLeafTracepoint()
    }

    override fun saveReferences(out: TraceWriter) {
        super.saveReferences(out)
        out.writeVariableDescriptor(localVariableId)
        out.preWriteTRValue(value)
    }

    override fun toText(appendable: TRAppendable) {
        appendable.append(tracePoint = this)
    }
}

class TRReadLocalVariableTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    localVariableId: Int,
    value: TRValue?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRLocalVariableTracePoint(context, threadId, codeLocationId, localVariableId, value, eventId) {

    override fun accessSymbol(): String = READ_ACCESS_SYMBOL
}

class TRWriteLocalVariableTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    localVariableId: Int,
    value: TRValue?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRLocalVariableTracePoint(context, threadId, codeLocationId, localVariableId, value, eventId) {

    override fun accessSymbol(): String = WRITE_ACCESS_SYMBOL
}

class TRSnapshotLineBreakpointTracePoint(
    context: TraceContext,
    codeLocationId: Int,
    threadId: Int,
    val breakpointUuid: UUID,
    val stackTraceCodeLocationIds: List<Int>,
    val currentTimeMillis: Long,
    val locals: List<TRValue?>,
    val traceId: String?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
): TRTracePoint(context, threadId, codeLocationId, eventId) {

    val threadName: String
        get() = context.getThreadName(threadId)

    val stackTrace: List<StackTraceElement>
        get() = stackTraceCodeLocationIds.map { context.stackTrace(it) }

    override fun save(out: TraceWriter) {
        saveReferences(out)
        out.startWriteAnyTracepoint()
        out.writeTRTracePoint(this)
        out.endWriteLeafTracepoint()
    }

    override fun saveReferences(out: TraceWriter) {
        super.saveReferences(out)
        stackTraceCodeLocationIds.forEach { id ->
            out.writeCodeLocation(id)
        }
        locals.forEach { out.preWriteTRValue(it) }
    }

    override fun toText(appendable: TRAppendable) {
        appendable.append(tracePoint = this)
    }
}

sealed class TRArrayTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val array: TRValue,
    val index: Int,
    val value: TRValue?,
    eventId: Int
) : TRTracePoint(context, threadId, codeLocationId, eventId) {

    internal abstract fun accessSymbol(): String

    override fun save(out: TraceWriter) {
        saveReferences(out)
        out.startWriteAnyTracepoint()
        out.writeTRTracePoint(this)
        out.endWriteLeafTracepoint()
    }

    override fun saveReferences(out: TraceWriter) {
        super.saveReferences(out)
        out.preWriteTRValue(array)
        out.preWriteTRValue(value)
    }

    override fun toText(appendable: TRAppendable) {
        appendable.append(tracePoint = this)
    }
}

class TRReadArrayTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    array: TRValue,
    index: Int,
    value: TRValue?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRArrayTracePoint(context, threadId, codeLocationId, array, index, value, eventId) {

    override fun accessSymbol(): String = READ_ACCESS_SYMBOL
}

class TRWriteArrayTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    array: TRValue,
    index: Int,
    value: TRValue?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRArrayTracePoint(context, threadId, codeLocationId, array, index, value, eventId) {

    override fun accessSymbol(): String = WRITE_ACCESS_SYMBOL
}

sealed class TRExceptionProcessingTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val exception: TRValue,
    eventId: Int
) : TRTracePoint(context, threadId, codeLocationId, eventId) {

    override fun save(out: TraceWriter) {
        saveReferences(out)
        out.startWriteAnyTracepoint()
        out.writeTRTracePoint(this)
        out.endWriteLeafTracepoint()
    }

    override fun saveReferences(out: TraceWriter) {
        super.saveReferences(out)
        out.preWriteTRValue(exception)
    }
}

class TRThrowTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    exception: TRValue,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRExceptionProcessingTracePoint(context, threadId, codeLocationId, exception, eventId) {

    override fun toText(appendable: TRAppendable) {
        appendable.append(tracePoint = this)
    }

}

class TRCatchTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    exception: TRValue,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRExceptionProcessingTracePoint(context, threadId, codeLocationId, exception, eventId) {

    override fun toText(appendable: TRAppendable) {
        appendable.append(tracePoint = this)
    }

}

const val READ_ACCESS_SYMBOL  = "➜"
const val WRITE_ACCESS_SYMBOL = "="

