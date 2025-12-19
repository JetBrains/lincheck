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

import org.jetbrains.lincheck.descriptors.AccessPath
import org.jetbrains.lincheck.descriptors.ClassDescriptor
import org.jetbrains.lincheck.descriptors.CodeLocations
import org.jetbrains.lincheck.descriptors.FieldDescriptor
import org.jetbrains.lincheck.descriptors.MethodDescriptor
import org.jetbrains.lincheck.descriptors.VariableDescriptor
import org.jetbrains.lincheck.descriptors.Types
import org.jetbrains.lincheck.trace.DefaultTRArrayTracePointPrinter.append
import org.jetbrains.lincheck.trace.DefaultTRFieldTracePointPrinter.append
import org.jetbrains.lincheck.trace.DefaultTRLocalVariableTracePointPrinter.append
import org.jetbrains.lincheck.trace.DefaultTRLoopIterationTracePointPrinter.append
import org.jetbrains.lincheck.trace.DefaultTRLoopTracePointPrinter.append
import org.jetbrains.lincheck.trace.DefaultTRMethodCallTracePointPrinter.append
import java.io.DataInput
import java.io.DataOutput
import java.math.BigDecimal
import java.math.BigInteger
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.experimental.or
import kotlin.reflect.KClass

private val EVENT_ID_GENERATOR = AtomicInteger(0)

var INJECTIONS_VOID_OBJECT: Any? = null

/**
 * Describes status of tracepoint in trace diff
 */
enum class DiffStatus {
    /**
     * Trace is not diff at all, or this tracepoint is identical in two traces.
     */
    COMMON,

    /**
     * This tracepoint was found in the first, but not in the second trace, when diff was created.
     */
    REMOVED,

    /**
     * This tracepoint was not found in the first, but found in the second trace, when diff was created.
     */
    ADDED,

    /**
     * This is call tracepoint, and it has the same method in both traces but differs in arguments values.
     */
    ARGS_DIFFERENCE,
}

sealed class TRTracePoint(
    internal val context: TraceContext,
    val codeLocationId: Int,
    val threadId: Int,
    val eventId: Int
) {
    internal var diffStatusField: DiffStatus? = null
        set(value)  {
            check(field == null) { "Diff status can be changed only once" }
            field = value
        }

    val diffStatus: DiffStatus
        get() = diffStatusField ?: DiffStatus.COMMON

    internal open fun save(out: TraceWriter) {
        saveReferences(out)

        out.startWriteAnyTracepoint()
        out.writeByte(getClassId(this))
        out.writeInt(codeLocationId)
        out.writeInt(threadId)
        out.writeInt(eventId)
        out.writeDiffStatus(diffStatusField)
    }

    internal open fun saveReferences(out: TraceWriter) {
        out.writeCodeLocation(codeLocationId)
    }

    val codeLocation: StackTraceElement get() = CodeLocations.stackTrace(context, codeLocationId)

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
) : TRTracePoint(context, codeLocationId, threadId, eventId) {
    protected var children: ChunkedList<TRTracePoint> = ChunkedList()
        private set

    protected var childrenAddresses: AddressIndex = AddressIndex.create()
        private set

    // TODO: do we need this, why not just leave only children/events
    val events: List<TRTracePoint?> get() = children

    private fun TRTracePoint.setParentIfContainer(parent: TRContainerTracePoint) {
        if (this !is TRContainerTracePoint) return
        parentTracePoint = parent
    }

    internal fun addChildAddress(address: Long) {
        childrenAddresses.add(address)
        children.add(null)
    }

    internal fun addChild(child: TRTracePoint, address: Long = -1) {
        childrenAddresses.add(address)
        children.add(child)
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
        from.children.forEach { it?.setParentIfContainer(this) }
    }

    internal fun loadChild(index: Int, child: TRTracePoint) {
        require(index in 0 ..< children.size) {
            "Index $index out of range 0..<${children.size}"
        }
        // Should we check for override? Lets skip for now
        children[index] = child
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

    internal abstract fun saveFooter(out: TraceWriter)
    internal abstract fun loadFooter(inp: DataInput)
}

class TRMethodCallTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val methodId: Int,
    val obj: TRObject?,
    val parameters: List<TRObject?>,
    flags: Short = 0,
    parentTracePoint: TRContainerTracePoint? = null,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRContainerTracePoint(context, threadId, codeLocationId, parentTracePoint, eventId) {
    var flags: Short = flags
        private set
    var result: TRObject? = null
    var exceptionClassName: String? = null

    // TODO Make parametrized
    val methodDescriptor: MethodDescriptor get() = context.getMethodDescriptor(methodId)
    val classDescriptor: ClassDescriptor get() = methodDescriptor.classDescriptor

    // Shortcuts
    val className: String get() = methodDescriptor.className
    val methodName: String get() = methodDescriptor.methodName
    val argumentNames: List<AccessPath?> get() = context.methodCallArgumentNames(codeLocationId) ?: emptyList()
    val argumentTypes: List<Types.Type> get() = methodDescriptor.argumentTypes
    val returnType: Types.Type get() = methodDescriptor.returnType

    var hasAddedChildren: Boolean
        get() = flags.toInt() and HAS_ADDED_CHILDREN_FLAG != 0
        set(value) {
            if (!value) return
            flags = (flags.toInt() or HAS_ADDED_CHILDREN_FLAG).toShort()
        }

    var hasRemovedChildren: Boolean
        get() = flags.toInt() and HAS_REMOVED_CHILDREN_FLAG != 0
        set(value) {
            if (!value) return
            flags = (flags.toInt() or HAS_REMOVED_CHILDREN_FLAG).toShort()
        }

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
        super.save(out)
        out.writeInt(methodId)
        out.writeTRObject(obj)
        out.writeInt(parameters.size)
        parameters.forEach {
            out.writeTRObject(it)
        }
        out.writeShort(flags.toInt())
        // Mark this as container tracepoint which could have children and will have footer
        out.endWriteContainerTracepointHeader(eventId)
    }

    override fun saveReferences(out: TraceWriter) {
        super.saveReferences(out)
        out.writeMethodDescriptor(methodId)
        out.preWriteTRObject(obj)
        parameters.forEach {
            out.preWriteTRObject(it)
        }
    }

    override fun saveFooter(out: TraceWriter) {
        out.preWriteTRObject(result)

        // Mark this as a container tracepoint footer
        out.startWriteContainerTracepointFooter()
        out.writeTRObject(result)
        out.writeUTF(exceptionClassName ?: "")
        out.endWriteContainerTracepointFooter(eventId)
    }

    override fun loadFooter(inp: DataInput) {
        childrenAddresses.finishWrite()

        result = inp.readTRObject(context)
        exceptionClassName = inp.readUTF()
        if (exceptionClassName?.isEmpty() ?: true) {
            exceptionClassName = null
        }
    }

    override fun toText(appendable: TRAppendable) {
        appendable.append(tracePoint = this)
    }

    companion object {
        // Flag that tells that the method was not tracked from its start and has some missing tracepoints
        const val INCOMPLETE_METHOD_FLAG: Int = 1
        // Flag that tells that method call has child(ren) with [DiffStatus.ADDED] diff status
        const val HAS_ADDED_CHILDREN_FLAG: Int = 2
        // Flag that tells that method call has child(ren) with [DiffStatus.REMOVED] diff status
        const val HAS_REMOVED_CHILDREN_FLAG: Int = 4

        internal fun load(context: TraceContext, inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRMethodCallTracePoint {
            val methodId = inp.readInt()
            val obj = inp.readTRObject(context)
            val pcount = inp.readInt()
            val parameters = mutableListOf<TRObject?>()
            repeat(pcount) {
                parameters.add(inp.readTRObject(context))
            }
            val flags = inp.readShort()

            return TRMethodCallTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = codeLocationId,
                methodId = methodId,
                obj = obj,
                parameters = parameters,
                flags = flags,
                eventId = eventId,
            )
        }
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
        private set

    fun incrementIterations(): Int {
        return iterations++
    }

    override fun save(out: TraceWriter) {
        super.save(out)
        out.writeInt(loopId)

        // Mark this as container tracepoint which could have children and will have footer
        out.endWriteContainerTracepointHeader(eventId)
    }

    override fun saveFooter(out: TraceWriter) {
        // Mark this as a container tracepoint footer
        out.startWriteContainerTracepointFooter()

        out.writeInt(iterations)
        out.endWriteContainerTracepointFooter(eventId)
    }

    override fun loadFooter(inp: DataInput) {
        childrenAddresses.finishWrite()
        iterations = inp.readInt()
    }

    override fun toText(appendable: TRAppendable) {
        appendable.append(tracePoint = this)
    }

    companion object {

        internal fun load(context: TraceContext, inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRLoopTracePoint {
            val loopId = inp.readInt()
            val tracePoint = TRLoopTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = codeLocationId,
                loopId = loopId,
                eventId = eventId,
            )
            return tracePoint
        }
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
        super.save(out)

        out.writeInt(loopId)
        out.writeInt(loopIteration)

        // Mark this as container tracepoint which could have children and will have footer
        out.endWriteContainerTracepointHeader(eventId)
    }

    override fun saveFooter(out: TraceWriter) {
        // Mark this as a container tracepoint footer
        out.startWriteContainerTracepointFooter()
        out.endWriteContainerTracepointFooter(eventId)
    }

    override fun loadFooter(inp: DataInput) {
        childrenAddresses.finishWrite()
    }

    override fun toText(appendable: TRAppendable) {
        appendable.append(tracePoint = this)
    }

    companion object {

        internal fun load(context: TraceContext, inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRLoopIterationTracePoint {
            val loopId = inp.readInt()
            val loopIteration = inp.readInt()

            val tracePoint = TRLoopIterationTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = codeLocationId,
                loopId = loopId,
                loopIteration = loopIteration,
                eventId = eventId,
            )

            return tracePoint
        }
    }
}

sealed class TRFieldTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val fieldId: Int,
    val obj: TRObject?,
    val value: TRObject?,
    eventId: Int
) : TRTracePoint(context, codeLocationId, threadId, eventId) {

    internal abstract fun accessSymbol(): String

    // TODO Make parametrized
    val fieldDescriptor: FieldDescriptor get() = context.getFieldDescriptor(fieldId)
    val classDescriptor: ClassDescriptor get() = fieldDescriptor.classDescriptor

    // Shortcuts
    val className: String get() = fieldDescriptor.className
    val name: String get() = fieldDescriptor.fieldName
    val isStatic: Boolean get() = fieldDescriptor.isStatic
    val isFinal: Boolean get() = fieldDescriptor.isFinal

    override fun save(out: TraceWriter) {
        super.save(out)
        out.writeInt(fieldId)
        out.writeTRObject(obj)
        out.writeTRObject(value)
        out.endWriteLeafTracepoint()
    }

    override fun saveReferences(out: TraceWriter) {
        super.saveReferences(out)
        out.writeFieldDescriptor(fieldId)
        out.preWriteTRObject(obj)
        out.preWriteTRObject(value)
    }

    override fun toText(appendable: TRAppendable) {
        appendable.append(tracePoint = this)
    }
}

class TRReadTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    fieldId: Int,
    obj: TRObject?,
    value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRFieldTracePoint(context, threadId, codeLocationId,  fieldId, obj, value, eventId) {

    override fun accessSymbol(): String = READ_ACCESS_SYMBOL

    internal companion object {
        fun load(context: TraceContext, inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRReadTracePoint {
            return TRReadTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = codeLocationId,
                fieldId = inp.readInt(),
                obj = inp.readTRObject(context),
                value = inp.readTRObject(context),
                eventId = eventId,
            )
        }
    }
}

class TRWriteTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    fieldId: Int,
    obj: TRObject?,
    value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRFieldTracePoint(context, threadId, codeLocationId,  fieldId, obj, value, eventId) {

    override fun accessSymbol(): String = WRITE_ACCESS_SYMBOL

    internal companion object {
        fun load(context: TraceContext, inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRWriteTracePoint {
            return TRWriteTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = codeLocationId,
                fieldId = inp.readInt(),
                obj = inp.readTRObject(context),
                value = inp.readTRObject(context),
                eventId = eventId,
            )
        }
    }
}

sealed class TRLocalVariableTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val localVariableId: Int,
    val value: TRObject?,
    eventId: Int
) : TRTracePoint(context, codeLocationId, threadId, eventId) {

    internal abstract fun accessSymbol(): String

    // TODO Make parametrized
    val variableDescriptor: VariableDescriptor get() = context.getVariableDescriptor(localVariableId)
    val name: String get() = variableDescriptor.name

    override fun save(out: TraceWriter) {
        super.save(out)
        out.writeInt(localVariableId)
        out.writeTRObject(value)
        out.endWriteLeafTracepoint()
    }

    override fun saveReferences(out: TraceWriter) {
        super.saveReferences(out)
        out.writeVariableDescriptor(localVariableId)
        out.preWriteTRObject(value)
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
    value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRLocalVariableTracePoint(context, threadId, codeLocationId, localVariableId, value, eventId) {

    override fun accessSymbol(): String = READ_ACCESS_SYMBOL

    internal companion object {
        fun load(context: TraceContext, inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRReadLocalVariableTracePoint {
            return TRReadLocalVariableTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = codeLocationId,
                localVariableId = inp.readInt(),
                value = inp.readTRObject(context),
                eventId = eventId,
            )
        }
    }
}

class TRWriteLocalVariableTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    localVariableId: Int,
    value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRLocalVariableTracePoint(context, threadId, codeLocationId, localVariableId, value, eventId) {

    override fun accessSymbol(): String = WRITE_ACCESS_SYMBOL

    internal companion object {
        fun load(context: TraceContext, inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRWriteLocalVariableTracePoint {
            return TRWriteLocalVariableTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = codeLocationId,
                localVariableId = inp.readInt(),
                value = inp.readTRObject(context),
                eventId = eventId,
            )
        }
    }
}

sealed class TRArrayTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    val array: TRObject,
    val index: Int,
    val value: TRObject?,
    eventId: Int
) : TRTracePoint(context, codeLocationId, threadId, eventId) {

    internal abstract fun accessSymbol(): String

    override fun save(out: TraceWriter) {
        super.save(out)
        out.writeTRObject(array)
        out.writeInt(index)
        out.writeTRObject(value)
        out.endWriteLeafTracepoint()
    }

    override fun saveReferences(out: TraceWriter) {
        super.saveReferences(out)
        out.preWriteTRObject(array)
        out.preWriteTRObject(value)
    }

    override fun toText(appendable: TRAppendable) {
        appendable.append(tracePoint = this)
    }
}

class TRReadArrayTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    array: TRObject,
    index: Int,
    value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRArrayTracePoint(context, threadId, codeLocationId, array, index, value, eventId) {

    override fun accessSymbol(): String = READ_ACCESS_SYMBOL

    internal companion object {
        fun load(context: TraceContext, inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRReadArrayTracePoint {
            return TRReadArrayTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = codeLocationId,
                array = inp.readTRObject(context) ?: TR_OBJECT_NULL,
                index = inp.readInt(),
                value = inp.readTRObject(context),
                eventId = eventId,
            )
        }
    }
}

class TRWriteArrayTracePoint(
    context: TraceContext,
    threadId: Int,
    codeLocationId: Int,
    array: TRObject,
    index: Int,
    value: TRObject?,
    eventId: Int = EVENT_ID_GENERATOR.getAndIncrement()
) : TRArrayTracePoint(context, threadId, codeLocationId, array, index, value, eventId) {

    override fun accessSymbol(): String = WRITE_ACCESS_SYMBOL

    internal companion object {
        fun load(context: TraceContext, inp: DataInput, codeLocationId: Int, threadId: Int, eventId: Int): TRWriteArrayTracePoint {
            return TRWriteArrayTracePoint(
                context = context,
                threadId = threadId,
                codeLocationId = codeLocationId,
                array = inp.readTRObject(context) ?: TR_OBJECT_NULL,
                index = inp.readInt(),
                value = inp.readTRObject(context),
                eventId = eventId,
            )
        }
    }
}

const val READ_ACCESS_SYMBOL  = "➜"
const val WRITE_ACCESS_SYMBOL = "="

fun loadTRTracePoint(context: TraceContext, inp: DataInput): TRTracePoint {
    val loader = getLoaderByClassId(inp.readByte())
    val codeLocationId = inp.readInt()
    val threadId = inp.readInt()
    val eventId = inp.readInt()
    val diffStatus = inp.readDiffStatus()
    return loader(context, inp, codeLocationId, threadId, eventId).also { if (diffStatus != null) it.diffStatusField = diffStatus }
}

private fun String.escape() = this
    .replace("\\", "\\\\")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

@ConsistentCopyVisibility
data class TRObject private constructor (
    internal val classNameId: Int,
    val identityHashCode: Int,
    internal val primitiveValue: Any?,
    val className: String
) {
    // TODO Make parametrized
    val isPrimitive: Boolean get() = primitiveValue != null
    val isSpecial: Boolean get() = classNameId < 0
    val value: Any? get() = primitiveValue

    internal constructor (classNameId: Int, identityHashCode: Int, primitiveValue: Any):
            this(classNameId, identityHashCode, primitiveValue, primitiveValue.javaClass.name)

    internal constructor(classNameId: Int, identityHashCode: Int, cd: ClassDescriptor):
            this(classNameId, identityHashCode, null, cd.name)

    // TODO: Unify with code like `ObjectLabelFactory.adornedStringRepresentation` placed in hypothetical `core` module
    override fun toString(): String {
        return if (primitiveValue != null) {
            when (primitiveValue) {
                is String -> {
                    when (classNameId) {
                        TR_OBJECT_P_RAW_STRING, TR_OBJECT_P_JAVA_CLASS, TR_OBJECT_P_KOTLIN_CLASS -> primitiveValue
                        TR_OBJECT_P_STRING_BUILDER -> "StringBuilder@$identityHashCode(\"${primitiveValue.escape()}\")"
                        else -> "\"${primitiveValue.escape()}\""
                    }
                }
                is Char -> "'$primitiveValue'"
                is Unit -> "Unit"
                else -> primitiveValue.toString()
            }
        } else if (classNameId == TR_OBJECT_NULL_CLASSNAME || classNameId == TR_OBJECT_VOID_CLASSNAME) {
            className // already set to either 'null' or 'void'
        } else {
            className.adornedClassNameRepresentation() + "@" + identityHashCode
        }
    }
}

const val TR_OBJECT_NULL_CLASSNAME = -1
val TR_OBJECT_NULL = TRObject(TR_OBJECT_NULL_CLASSNAME, 0, ClassDescriptor("null"))

const val TR_OBJECT_VOID_CLASSNAME = -2
val TR_OBJECT_VOID = TRObject(TR_OBJECT_VOID_CLASSNAME, 0, ClassDescriptor("void"))

const val UNFINISHED_METHOD_RESULT_SYMBOL = "<unfinished method>"
const val UNTRACKED_METHOD_RESULT_SYMBOL = "<untracked result>"
val TR_OBJECT_UNFINISHED_METHOD_RESULT = TRObject(TR_OBJECT_P_STRING, 0, UNFINISHED_METHOD_RESULT_SYMBOL)
val TR_OBJECT_UNTRACKED_METHOD_RESULT = TRObject(TR_OBJECT_P_STRING, 0, UNTRACKED_METHOD_RESULT_SYMBOL)

const val TR_OBJECT_P_BYTE = TR_OBJECT_VOID_CLASSNAME - 1
const val TR_OBJECT_P_SHORT = TR_OBJECT_P_BYTE - 1
const val TR_OBJECT_P_INT = TR_OBJECT_P_SHORT - 1
const val TR_OBJECT_P_LONG = TR_OBJECT_P_INT - 1
const val TR_OBJECT_P_FLOAT = TR_OBJECT_P_LONG - 1
const val TR_OBJECT_P_DOUBLE = TR_OBJECT_P_FLOAT - 1
const val TR_OBJECT_P_CHAR = TR_OBJECT_P_DOUBLE - 1
const val TR_OBJECT_P_STRING = TR_OBJECT_P_CHAR - 1
const val TR_OBJECT_P_UNIT = TR_OBJECT_P_STRING - 1
const val TR_OBJECT_P_RAW_STRING = TR_OBJECT_P_UNIT - 1
const val TR_OBJECT_P_BOOLEAN = TR_OBJECT_P_RAW_STRING - 1
const val TR_OBJECT_P_JAVA_CLASS = TR_OBJECT_P_BOOLEAN - 1
const val TR_OBJECT_P_KOTLIN_CLASS = TR_OBJECT_P_JAVA_CLASS - 1
const val TR_OBJECT_P_STRING_BUILDER = TR_OBJECT_P_KOTLIN_CLASS - 1

fun TRObjectOrNull(context: TraceContext, obj: Any?): TRObject? =
    obj?.let { TRObject(context, it) }

fun TRObjectOrVoid(context: TraceContext, obj: Any?): TRObject? =
    if (obj === INJECTIONS_VOID_OBJECT) TR_OBJECT_VOID
    else TRObjectOrNull(context, obj)


const val MAX_TROBJECT_STRING_LENGTH = 50

private fun CharSequence.trimToString(): String {
    val string = toString()
    return if (string.length > MAX_TROBJECT_STRING_LENGTH) "${string.take(MAX_TROBJECT_STRING_LENGTH)}..." else string
}

private val WHITELIST_PACKAGES_FOR_TO_STRING = listOf(
    // StringBuffer, StringBuilder
    "java.lang.",
    // CharBuffer
    "java.nio.",
    // Segment
    "javax.swing.",
    // Kotlin "wrappers"
    "kotlin.text.",
    "kotlin."
)

private fun classNameWhitelisted(obj: Any): Boolean {
    val fullClassName = obj.javaClass.name
    return WHITELIST_PACKAGES_FOR_TO_STRING.any { fullClassName.startsWith(it) }
}

fun TRObject(context: TraceContext, obj: Any): TRObject {
    val defaultTRObject = {
        val classId = context.getOrCreateClassId(obj.javaClass.name)
        TRObject(classId, System.identityHashCode(obj), context.getClassDescriptor(classId))
    }

    return when (obj) {
        is Byte -> TRObject(TR_OBJECT_P_BYTE, 0, obj)
        is Short -> TRObject(TR_OBJECT_P_SHORT, 0, obj)
        is Int -> TRObject(TR_OBJECT_P_INT, 0, obj)
        is Long -> TRObject(TR_OBJECT_P_LONG, 0, obj)
        is Float -> TRObject(TR_OBJECT_P_FLOAT, 0, obj)
        is Double -> TRObject(TR_OBJECT_P_DOUBLE, 0, obj)
        is Char -> TRObject(TR_OBJECT_P_CHAR, 0, obj)
        is String -> TRObject(TR_OBJECT_P_STRING, 0, obj.trimToString())
        is StringBuilder -> TRObject(
            TR_OBJECT_P_STRING_BUILDER, System.identityHashCode(obj), obj.trimToString()
        )

        // If user (traced) code contains CharSequence implementation, it is a bad idea to call it
        // as there is no guarantee that this implementation doesn't have side effects.
        // Guard by Java and Kotlin std lib packages
        is CharSequence if (classNameWhitelisted(obj)) -> runCatching { obj.trimToString() }.let {
            // Some implementations of CharSequence might throw when `subSequence` is invoked at some unexpected moment,
            // like when this sequence is considered "destroyed" at this point
            if (it.isSuccess) TRObject(TR_OBJECT_P_STRING, 0, it.getOrThrow())
            else defaultTRObject()
        }

        is Unit -> TRObject(TR_OBJECT_P_UNIT, 0, obj)
        is Boolean -> TRObject(TR_OBJECT_P_BOOLEAN, 0, obj)

        // Render these types to strings for simplicity
        is Enum<*> -> TRObject(TR_OBJECT_P_RAW_STRING, 0, "${obj.javaClass.simpleName}.${obj.name}")
        is BigInteger -> TRObject(TR_OBJECT_P_RAW_STRING, 0, obj.toString())
        is BigDecimal -> TRObject(TR_OBJECT_P_RAW_STRING, 0, obj.toString())
        is Class<*> -> TRObject(TR_OBJECT_P_JAVA_CLASS, 0, "${obj.simpleName}.class")
        is KClass<*> -> TRObject(TR_OBJECT_P_KOTLIN_CLASS, 0, "${obj.simpleName}.kclass")
        // Generic case
        // TODO Make parametrized
        else -> defaultTRObject()
    }
}

internal fun DataOutput.writeTRObject(value: TRObject?) {
    // null
    if (value == null) {
        writeInt(TR_OBJECT_NULL_CLASSNAME)
        return
    }
    // Negatives are special markers
    writeInt(value.classNameId)
    if (value.classNameId >= 0) {
        writeInt(value.identityHashCode)
        return
    }
    if (value.classNameId > TR_OBJECT_P_BYTE) {
        return
    }
    when (value.primitiveValue) {
        is Byte -> writeByte(value.primitiveValue.toInt())
        is Short -> writeShort(value.primitiveValue.toInt())
        is Int -> writeInt(value.primitiveValue)
        is Long -> writeLong(value.primitiveValue)
        is Float -> writeFloat(value.primitiveValue)
        is Double -> writeDouble(value.primitiveValue)
        is Char -> writeChar(value.primitiveValue.code)
        is String if value.classNameId == TR_OBJECT_P_STRING_BUILDER -> {
            writeInt(value.identityHashCode)
            writeUTF(value.primitiveValue)
        }
        is String -> writeUTF(value.primitiveValue) // Both STRING and RAW_STRING
        is Boolean -> writeBoolean(value.primitiveValue)
        is Unit -> {}
        else -> error("Unknow primitive value ${value.primitiveValue}")
    }
}

internal fun DataInput.readTRObject(context: TraceContext): TRObject? {
    return when (val classNameId = readInt()) {
        TR_OBJECT_NULL_CLASSNAME -> null
        TR_OBJECT_VOID_CLASSNAME -> TR_OBJECT_VOID
        TR_OBJECT_P_BYTE -> TRObject(classNameId, 0, readByte())
        TR_OBJECT_P_SHORT -> TRObject(classNameId, 0, readShort())
        TR_OBJECT_P_INT -> TRObject(classNameId, 0, readInt())
        TR_OBJECT_P_LONG -> TRObject(classNameId, 0, readLong())
        TR_OBJECT_P_FLOAT -> TRObject(classNameId, 0, readFloat())
        TR_OBJECT_P_DOUBLE -> TRObject(classNameId, 0, readDouble())
        TR_OBJECT_P_CHAR -> TRObject(classNameId, 0, readChar())
        TR_OBJECT_P_STRING -> TRObject(classNameId, 0, readUTF())
        TR_OBJECT_P_UNIT -> TRObject(classNameId, 0, Unit)
        TR_OBJECT_P_RAW_STRING -> TRObject(classNameId, 0, readUTF())
        TR_OBJECT_P_BOOLEAN -> TRObject(classNameId, 0, readBoolean())
        TR_OBJECT_P_JAVA_CLASS -> TRObject(classNameId, 0, readUTF())
        TR_OBJECT_P_KOTLIN_CLASS -> TRObject(classNameId, 0, readUTF())
        TR_OBJECT_P_STRING_BUILDER -> TRObject(classNameId, readInt(), readUTF())
        else -> {
            if (classNameId >= 0) {
                TRObject(classNameId, readInt(), context.getClassDescriptor(classNameId))
            } else {
                error("TRObject: Unknown Class Id $classNameId")
            }
        }
    }
}

private typealias TRLoader = (TraceContext, DataInput, Int, Int, Int) -> TRTracePoint

private fun getClassId(point: TRTracePoint): Int {
    return when (point) {
        is TRMethodCallTracePoint -> 0
        is TRReadArrayTracePoint -> 1
        is TRReadLocalVariableTracePoint -> 2
        is TRReadTracePoint -> 3
        is TRWriteArrayTracePoint -> 4
        is TRWriteLocalVariableTracePoint -> 5
        is TRWriteTracePoint -> 6
        is TRLoopTracePoint -> 7
        is TRLoopIterationTracePoint -> 8
    }
}

private fun getLoaderByClassId(id: Byte): TRLoader {
    return when (id.toInt()) {
        0 -> TRMethodCallTracePoint::load
        1 -> TRReadArrayTracePoint::load
        2 -> TRReadLocalVariableTracePoint::load
        3 -> TRReadTracePoint::load
        4 -> TRWriteArrayTracePoint::load
        5 -> TRWriteLocalVariableTracePoint::load
        6 -> TRWriteTracePoint::load
        7 -> TRLoopTracePoint::load
        8 -> TRLoopIterationTracePoint::load
        else -> error("Unknown TRTracePoint class id $id")
    }
}
