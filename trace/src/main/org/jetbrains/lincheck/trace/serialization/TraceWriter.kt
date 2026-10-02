/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.lincheck.trace.serialization

import org.jetbrains.lincheck.descriptors.*
import org.jetbrains.lincheck.trace.*
import java.io.Closeable
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.OutputStream

/**
 * An abstract serialization writer for trace data, including the tracepoints themselves.
 *
 * One `writeTR<Type>TracePoint` method per tracepoint type;
 * read/write variants share the method of their sealed base class
 * (their bodies are identical, the kind byte tells them apart on the read side).
 * The closing side of a container ([ContainerFooterTracePoint]) is an ordinary tracepoint record,
 * written after all the container's children: with [TraceWriter.writeTracePoint] by a caller which holds it,
 * or by calling [TraceWriter.writeTracePoint] with the container's footer.
 *
 * Each default `writeTR<Type>TracePoint` implementation:
 *   1. pre-registers prerequisite descriptors and values (memoized by the writer),
 *   2. calls [startWriteAnyTracepoint],
 *   3. calls [writeTracePointData] — the top-level dispatcher in `TraceBinarySerialization.kt`
 *      that emits the kind byte, common header, and the subclass-specific body bytes
 *      (including children diff-statuses for containers),
 *   4. calls [endWriteLeafTracepoint], [endWriteContainerTracepointHeader],
 *      or [endWriteContainerTracepoint] for a closing tracepoint.
 */
internal interface TraceWriter : DataOutput, Closeable {
    /**
     * Saves dependencies of [TraceValue], if needed.
     * This must be called before [startWriteAnyTracepoint] for all used [TraceValue]s.
     */
    fun preWriteTraceValue(value: TraceValue)

    /**
     * Saves [TraceValue] itself.
     * Must be called after [startWriteAnyTracepoint].
     */
    fun writeTraceValue(value: TraceValue)

    /**
     * Marks the beginning of a tracepoint (before the first byte of tracepoint is written).
     */
    fun startWriteAnyTracepoint()

    /**
     * Marks the end of the leaf (fix-sized) tracepoint.
     */
    fun endWriteLeafTracepoint()

    /**
     * Mark the end of the container tracepoint's header.
     *
     * The container tracepoints are [MethodCallTracePoint], [LoopTracePoint], and [LoopIterationTracePoint].
     */
    fun endWriteContainerTracepointHeader(id: Int)

    /**
     * Marks the end of the children of the container tracepoint with the given event [id].
     *
     * Must be called after all the container's children are written
     * and right before the container's closing tracepoint, whose position bounds the children range.
     */
    fun endWriteContainerTracepointChildren(id: Int)

    /**
     * Marks the end of the whole container tracepoint with the given event [id],
     * i.e. the end of the body of its closing tracepoint.
     *
     * Only here the container is committed: a buffered writer may fail in the middle of the closing
     * tracepoint and roll it back, and the whole close is then retried from scratch.
     */
    fun endWriteContainerTracepoint(id: Int)

    /**
     * Write [name] of the thread.
     * This must be called before [startWriteAnyTracepoint] for all thread names.
     */
    fun writeThreadName(id: Int, name: String)

    /**
     * Write [ClassDescriptor] from context referred by given `id`, if needed.
     * This must be called before [startWriteAnyTracepoint] for all used class descriptors.
     */
    fun writeClassDescriptor(id: Int)

    /**
     * Write [MethodDescriptor] from context referred by given `id`, if needed.
     * This must be called before [startWriteAnyTracepoint] for all used method descriptors.
     */
    fun writeMethodDescriptor(id: Int)

    /**
     * Write [FieldDescriptor] from context referred by given `id`, if needed.
     * This must be called before [startWriteAnyTracepoint] for all used field descriptors.
     */
    fun writeFieldDescriptor(id: Int)

    /**
     * Write [VariableDescriptor] from context referred by given `id` if needed.
     * This must be called before [startWriteAnyTracepoint] for all used variable descriptors.
     */
    fun writeVariableDescriptor(id: Int)

    /**
     * Write [CodeLocation] from context referred by given code location `id`, if needed.
     * This must be called before [startWriteAnyTracepoint] for all used code locations.
     */
    fun writeCodeLocation(id: Int)

    /**
     * Writes a tracepoint of any type by dispatching to its type-specific `writeTR<Type>TracePoint` method.
     */
    fun writeTracePoint(tracePoint: TracePoint) {
        when (tracePoint) {
            is MethodCallTracePoint             -> writeMethodCallTracePoint(tracePoint)
            is LoopTracePoint                   -> writeLoopTracePoint(tracePoint)
            is LoopIterationTracePoint          -> writeLoopIterationTracePoint(tracePoint)
            is FieldTracePoint                  -> writeFieldTracePoint(tracePoint)
            is ArrayTracePoint                  -> writeArrayTracePoint(tracePoint)
            is LocalVariableTracePoint          -> writeLocalVariableTracePoint(tracePoint)
            is ExceptionProcessingTracePoint    -> writeExceptionProcessingTracePoint(tracePoint)
            is SnapshotLineBreakpointTracePoint -> writeSnapshotLineBreakpointTracePoint(tracePoint)
            is MethodCallResultTracePoint       -> writeMethodCallResultTracePoint(tracePoint)
            is LoopEndTracePoint                -> writeLoopEndTracePoint(tracePoint)
            is LoopIterationEndTracePoint       -> writeLoopIterationEndTracePoint(tracePoint)
        }
    }

    fun writeMethodCallTracePoint(tracePoint: MethodCallTracePoint) {
        writeCodeLocation(tracePoint.codeLocationId)
        writeMethodDescriptor(tracePoint.methodId)
        preWriteTraceValue(tracePoint.obj)
        tracePoint.parameters.forEach { preWriteTraceValue(it) }
        writeContainerTracepointHeader(tracePoint)
    }

    fun writeLoopTracePoint(tracePoint: LoopTracePoint) {
        writeCodeLocation(tracePoint.codeLocationId)
        writeContainerTracepointHeader(tracePoint)
    }

    fun writeLoopIterationTracePoint(tracePoint: LoopIterationTracePoint) {
        writeCodeLocation(tracePoint.codeLocationId)
        writeContainerTracepointHeader(tracePoint)
    }

    fun writeFieldTracePoint(tracePoint: FieldTracePoint) {
        writeCodeLocation(tracePoint.codeLocationId)
        writeFieldDescriptor(tracePoint.fieldId)
        preWriteTraceValue(tracePoint.obj)
        preWriteTraceValue(tracePoint.value)
        writeLeafTracepoint(tracePoint)
    }

    fun writeArrayTracePoint(tracePoint: ArrayTracePoint) {
        writeCodeLocation(tracePoint.codeLocationId)
        preWriteTraceValue(tracePoint.array)
        preWriteTraceValue(tracePoint.value)
        writeLeafTracepoint(tracePoint)
    }

    fun writeLocalVariableTracePoint(tracePoint: LocalVariableTracePoint) {
        writeCodeLocation(tracePoint.codeLocationId)
        writeVariableDescriptor(tracePoint.localVariableId)
        preWriteTraceValue(tracePoint.value)
        writeLeafTracepoint(tracePoint)
    }

    fun writeExceptionProcessingTracePoint(tracePoint: ExceptionProcessingTracePoint) {
        writeCodeLocation(tracePoint.codeLocationId)
        preWriteTraceValue(tracePoint.exception)
        writeLeafTracepoint(tracePoint)
    }

    fun writeSnapshotLineBreakpointTracePoint(tracePoint: SnapshotLineBreakpointTracePoint) {
        writeCodeLocation(tracePoint.codeLocationId)
        tracePoint.stackTraceCodeLocationIds.forEach { writeCodeLocation(it) }
        tracePoint.locals.forEach { preWriteTraceValue(it) }
        tracePoint.watches.forEach { preWriteTraceValue(it) }
        writeLeafTracepoint(tracePoint)
    }

    fun writeMethodCallResultTracePoint(tracePoint: MethodCallResultTracePoint) {
        writeCodeLocation(tracePoint.codeLocationId)
        preWriteTraceValue(tracePoint.result)
        writeContainerEndTracepoint(tracePoint)
    }

    fun writeLoopEndTracePoint(tracePoint: LoopEndTracePoint) {
        writeCodeLocation(tracePoint.codeLocationId)
        writeContainerEndTracepoint(tracePoint)
    }

    fun writeLoopIterationEndTracePoint(tracePoint: LoopIterationEndTracePoint) {
        writeCodeLocation(tracePoint.codeLocationId)
        writeContainerEndTracepoint(tracePoint)
    }
}

private fun TraceWriter.writeLeafTracepoint(tracePoint: TracePoint) {
    startWriteAnyTracepoint()
    writeTracePointData(tracePoint)
    endWriteLeafTracepoint()
}

// Marks the tracepoint as a container which could have children.
private fun TraceWriter.writeContainerTracepointHeader(tracePoint: ContainerHeaderTracePoint) {
    startWriteAnyTracepoint()
    writeTracePointData(tracePoint)
    endWriteContainerTracepointHeader(tracePoint.eventId)
}

// Closes the container: everything written between its header and this record is its children.
private fun TraceWriter.writeContainerEndTracepoint(tracePoint: ContainerFooterTracePoint) {
    // Must be called after the closing tracepoint's own prerequisites, so that they still fall
    // into the children range, and before its first byte, which bounds that range.
    endWriteContainerTracepointChildren(tracePoint.containerEventId)
    startWriteAnyTracepoint()
    writeTracePointData(tracePoint)
    endWriteContainerTracepoint(tracePoint.containerEventId)
}

/** A container tracepoint whose header is written and whose closing tracepoint is not committed yet. */
private class OpenContainer(val id: Int, val startPosition: Long) {
    /** Data position of the first byte of the closing tracepoint, i.e. the end of the children range. */
    var childrenEndPosition: Long = startPosition
}

/**
 * [dataStream] responsible for operations like `close()` and [dataOutput] for real data output.
 *
 * As this class is used with both JDK's [DataOutputStream] and project-local [ByteBufferOutputStream],
 * and [OutputStream] is abstract class and not an interface, it is impossible to make one property which
 * is compatible with both [DataOutputStream] and [ByteBufferOutputStream] at the same time.
 *
 * `dataStream` can be relaxed to [Closeable], but it will hide its intention even more.
 */
internal abstract class ContextAwareTraceWriter(
    val context: TraceContext,
    protected val dataStream: OutputStream,
    protected val dataOutput: DataOutput
): TraceWriter, DataOutput by dataOutput {
    protected abstract val contextState: TraceContextSavedState
    // Stack of container tracepoints whose closing tracepoint is not written yet
    private val containerStack = mutableListOf<OpenContainer>()

    private var inTracepointBody = false

    protected abstract val currentDataPosition: Long
    abstract val writerId: Int

    override fun close() {
        dataOutput.writeKind(ObjectKind.EOF)
        dataStream.close()

        writeIndexCell(ObjectKind.EOF,-1, -1, -1)
    }

    override fun preWriteTraceValue(value: TraceValue) {
        check(!inTracepointBody) { "Cannot write TraceObject dependency into tracepoint body" }
        // Only types that carry a real [ClassDescriptor] need pre-registration on the wire;
        // [TraceValue.classId] returns `null` for sentinels, primitives, strings, etc.
        val classId = value.classId ?: return
        writeClassDescriptor(classId)
        // Recursively register class descriptors for all field values.
        if (value is TraceObjectSnapshot) {
            value.fields.values.forEach { fieldValue -> preWriteTraceValue(fieldValue) }
        }
        if (value is TraceArraySnapshot) {
            value.capturedElements.forEach { capturedElement -> preWriteTraceValue(capturedElement) }
        }
        // Both halves of an entry are arbitrary values, so a key needs registering just like a value.
        if (value is TraceMapSnapshot) {
            value.capturedEntries.forEach { (key, entryValue) ->
                preWriteTraceValue(key)
                preWriteTraceValue(entryValue)
            }
        }
    }

    override fun writeTraceValue(value: TraceValue) {
        check(inTracepointBody) { "Cannot write TraceObject outside tracepoint body" }
        dataOutput.writeTraceValue(value)
    }

    override fun startWriteAnyTracepoint() {
        check(!inTracepointBody) { "Cannot start nested tracepoint body" }
        dataOutput.writeKind(ObjectKind.TRACEPOINT)
        inTracepointBody = true
    }

    override fun endWriteLeafTracepoint() {
        check(inTracepointBody) { "Cannot end tracepoint body not in tracepoint" }
        inTracepointBody = false
    }

    override fun endWriteContainerTracepointHeader(id: Int) {
        check(inTracepointBody) { "Cannot end tracepoint header not in tracepoint" }
        inTracepointBody = false

        // Store where container content starts
        containerStack.add(OpenContainer(id, currentDataPosition))
    }

    override fun endWriteContainerTracepointChildren(id: Int) {
        check(!inTracepointBody) { "Cannot end container children inside a tracepoint body" }
        // The descriptors needed by the closing tracepoint are written before it,
        // so they fall inside the children range, which is what the reader expects.
        currentContainer(id).childrenEndPosition = currentDataPosition
    }

    override fun endWriteContainerTracepoint(id: Int) {
        check(inTracepointBody) { "Cannot end container tracepoint not in tracepoint" }
        inTracepointBody = false

        // Pop and index the container only now, when its closing tracepoint is written completely:
        // a writer that overflows in the middle of that record rolls the data back and retries the
        // whole close, which must find the container still open.
        val container = currentContainer(id)
        containerStack.removeLast()
        writeIndexCell(ObjectKind.TRACEPOINT, id, container.startPosition, container.childrenEndPosition)
    }

    /** The innermost open container, which must be the one with the given event [id]. */
    private fun currentContainer(id: Int): OpenContainer {
        val container = containerStack.lastOrNull()
        check(container != null && container.id == id) {
            "Container tracepoint $id is closed while ${container?.id?.let { "container $it" } ?: "no container"} " +
            "is open: endWriteContainerTracepointHeader() / endWriteContainerTracepoint() calls are not balanced"
        }
        return container
    }

    override fun writeThreadName(id: Int, name: String) {
        check(!inTracepointBody) { "Cannot write thread name inside tracepoint" }

        val position = currentDataPosition
        dataOutput.writeKind(ObjectKind.THREAD_NAME)
        dataOutput.writeThreadName(id, name)
        writeIndexCell(ObjectKind.THREAD_NAME, id, position, -1)
    }

    override fun writeClassDescriptor(id: Int) {
        check(!inTracepointBody) { "Cannot save reference data inside tracepoint" }
        if (contextState.isDescriptorSaved<ClassDescriptor>(id)) return
        // Write class descriptor into data and position into index
        val position = currentDataPosition
        dataOutput.writeKind(ObjectKind.CLASS_DESCRIPTOR)
        dataOutput.writeInt(id)
        dataOutput.writeClassDescriptor(context.classPool[id])
        contextState.markDescriptorSaved<ClassDescriptor>(id)

        writeIndexCell(ObjectKind.CLASS_DESCRIPTOR, id, position, -1)
    }

    override fun writeMethodDescriptor(id: Int) {
        check(!inTracepointBody) { "Cannot save reference data inside tracepoint" }
        if (contextState.isDescriptorSaved<MethodDescriptor>(id)) return
        val descriptor = context.methodPool[id]
        writeClassDescriptor(descriptor.classId)

        // Write method descriptor into data and position into index
        val position = currentDataPosition
        dataOutput.writeKind(ObjectKind.METHOD_DESCRIPTOR)
        dataOutput.writeInt(id)
        dataOutput.writeMethodDescriptor(descriptor)
        contextState.markDescriptorSaved<MethodDescriptor>(id)

        writeIndexCell(ObjectKind.METHOD_DESCRIPTOR, id, position, -1)
    }

    override fun writeFieldDescriptor(id: Int) {
        check(!inTracepointBody) { "Cannot save reference data inside tracepoint" }
        if (contextState.isDescriptorSaved<FieldDescriptor>(id)) return
        val descriptor = context.fieldPool[id]
        writeClassDescriptor(descriptor.classId)
        // Write field descriptor into data and position into index
        val position = currentDataPosition
        dataOutput.writeKind(ObjectKind.FIELD_DESCRIPTOR)
        dataOutput.writeInt(id)
        dataOutput.writeFieldDescriptor(descriptor)
        contextState.markDescriptorSaved<FieldDescriptor>(id)

        writeIndexCell(ObjectKind.FIELD_DESCRIPTOR, id, position, -1)
    }

    override fun writeVariableDescriptor(id: Int) {
        check(!inTracepointBody) { "Cannot save reference data inside tracepoint" }
        if (contextState.isDescriptorSaved<VariableDescriptor>(id)) return
        // Write variable descriptor into data and position into index
        val position = currentDataPosition
        dataOutput.writeKind(ObjectKind.VARIABLE_DESCRIPTOR)
        dataOutput.writeInt(id)
        dataOutput.writeVariableDescriptor(context.variablePool[id])
        contextState.markDescriptorSaved<VariableDescriptor>(id)

        writeIndexCell(ObjectKind.VARIABLE_DESCRIPTOR, id, position, -1)
    }

    override fun writeCodeLocation(id: Int) {
        check(!inTracepointBody) {
            "Cannot save reference data inside tracepoint"
        }
        if (id == UNKNOWN_CODE_LOCATION_ID) return
        if (contextState.isDescriptorSaved<CodeLocation>(id)) return

        // Code location with id UNKNOWN_CODE_LOCATION_ID is not considered here,
        // so context will contain a requested code location
        val codeLocation = context.codeLocationsPool[id] // make a single context search instead of 4
        val stackTrace = codeLocation.stackTraceElement
        val accessPath = codeLocation.accessPath
        val argumentNames = codeLocation.argumentNames
        val activeLocals = codeLocation.activeLocals
        val loopIds = when (codeLocation) {
            is LoopHeaderCodeLocation -> codeLocation.loopIds
            else -> null
        }
        // All strings only once. It will have duplications with class and method descriptors,
        // but size loss is negligible and this way is simpler
        val fileNameId = if (stackTrace.fileName != FALLBACK_STRING) writeString(stackTrace.fileName) else -1
        val classNameId = if (stackTrace.className != FALLBACK_STRING) writeString(stackTrace.className) else -1
        val methodNameId = if (stackTrace.methodName != FALLBACK_STRING) writeString(stackTrace.methodName) else -1
        val accessPathId = writeAccessPath(accessPath)
        val argumentNamesIds = argumentNames?.map { writeAccessPath(it) }
        val activeLocalNameIds = activeLocals?.map {
            if (it.localName != FALLBACK_STRING) writeString(it.localName) else -1
        }

        // Code location into data and position into index
        val position = currentDataPosition
        dataOutput.writeKind(ObjectKind.CODE_LOCATION)
        dataOutput.writeCodeLocationKind(codeLocation.kind)
        dataOutput.writeInt(id)
        dataOutput.writeInt(fileNameId)
        dataOutput.writeInt(classNameId)
        dataOutput.writeInt(methodNameId)
        dataOutput.writeInt(stackTrace.lineNumber)
        dataOutput.writeInt(accessPathId)
        dataOutput.writeInt(argumentNamesIds?.size ?: 0)
        argumentNamesIds?.forEach { dataOutput.writeInt(it) }
        dataOutput.writeInt(activeLocalNameIds?.size ?: 0)
        activeLocalNameIds?.forEach { dataOutput.writeInt(it) }
        activeLocals?.forEach { dataOutput.writeInt(it.localKind.ordinal) }
        dataOutput.writeInt(loopIds?.size ?: 0)
        loopIds?.forEach { dataOutput.writeInt(it) }
        contextState.markDescriptorSaved<CodeLocation>(id)

        writeIndexCell(ObjectKind.CODE_LOCATION, id, position, -1)
    }

    protected open fun writeString(value: String?): Int {
        check(!inTracepointBody) { "Cannot save reference data inside tracepoint" }
        if (value == null) return -1

        val id = context.stringPool.register(value)
        if (contextState.isDescriptorSaved<String>(id)) return id

        val position = currentDataPosition
        dataOutput.writeKind(ObjectKind.STRING)
        dataOutput.writeInt(id)
        dataOutput.writeString(value)
        contextState.markDescriptorSaved<String>(id)

        // It cannot fail
        writeIndexCell(ObjectKind.STRING, id, position, -1)

        return id
    }

    /**
     * Writes access path [value] to the output stream.
     *
     * The method gets an order of all access paths that are inside the [value], in which
     * they should be serialized (innermost go first -- top-sort).
     * After that method first serializes all dependencies of the all access locations inside
     * each access path and then saves access paths in top-sort order.
     *
     * ```
     * first: [variable descriptor 1] [field descriptor 1] [variable descriptor 2] ...
     * then: [ACCESS_LOCATION] [id 1] [locations count] [location type] [data] [location type] [data] ...
     *       [ACCESS_LOCATION] [id 2] [locations count] [location type] [data] ...
     * ```
     *
     * Such order is required, because [AccessPath] is a recursive structure, which may contain another access paths inside.
     * They should come first in the serialization order for easier deserialization later. So when we need to construct
     * an [AccessLocation] which expects [AccessPath] as an argument, we would be sure that it is
     * present in the trace context and can be retrieved via id. So such locations are serialized the following way:
     * ```
     * [location type] [another access path id]
     * ```
     *
     * Also, each location inside access path may contain variable/field descriptors, which also should be
     * serialized beforehand for easier deserialization later. Their structure looks similar way:
     * ```
     * [location type] [field/variable descriptor id]
     * ```
     */
    private fun writeAccessPath(value: AccessPath?): Int {
        check(!inTracepointBody) { "Cannot save reference data inside tracepoint" }
        if (value == null) return -1

        val savingOrder = collectAccessPathsInSavingOrder(value)
        val id = writeAccessPaths(value, savingOrder)
        return id
    }

    private fun writeAccessPaths(root: AccessPath, savingOrder: List<AccessPath>): Int {
        var rootId = -1

        savingOrder
            // first, we save all references of every location inside each access path
            .onEach { value ->
                value.locations.forEach { location ->
                    location.saveReferences(this, context)
                }
            }
            // then, save the access paths in correct order
            .onEach { value ->
                val position = currentDataPosition
                val id = context.accessPathPool.register(value)
                if (value == root) rootId = id
                if (contextState.isDescriptorSaved<AccessPath>(id)) return@onEach

                dataOutput.writeKind(ObjectKind.ACCESS_PATH)
                dataOutput.writeInt(id)
                dataOutput.writeInt(value.locations.size)

                value.locations.forEach { location ->
                    dataOutput.writeAccessLocation(context, location)
                }

                contextState.markDescriptorSaved<AccessPath>(id)
                writeIndexCell(ObjectKind.ACCESS_PATH, id, position, -1)
            }

        check(rootId >= 0) { "Root access path $root was not added to the saved access paths: $savingOrder" }
        return rootId
    }

    /**
     * @return all `AccessPath`s, reachable from [value], in top-sort order (from innermost to outermost).
     */
    private fun collectAccessPathsInSavingOrder(value: AccessPath): List<AccessPath> {
        val order = mutableListOf<AccessPath>()
        collectAccessPathsInSavingOrder(value, mutableSetOf(), order)
        return order
    }

    private fun collectAccessPathsInSavingOrder(current: AccessPath, visited: MutableSet<AccessPath>, order: MutableList<AccessPath>) {
        visited.add(current)
        current.locations.forEach { location ->
            if (location is ArrayElementByNameAccessLocation && !visited.contains(location.indexAccessPath)) {
                collectAccessPathsInSavingOrder(location.indexAccessPath, visited, order)
            }
        }
        order.add(current)
    }

    protected fun resetTracepointState() {
        inTracepointBody = false
    }


    protected abstract fun writeIndexCell(kind: ObjectKind, id: Int, startPos: Long, endPos: Long)
}

internal fun AccessLocation.saveReferences(out: TraceWriter, traceContext: TraceContext) {
    when (this) {
        is LocalVariableAccessLocation       -> saveReferences(out, traceContext)
        is StaticFieldAccessLocation         -> saveReferences(out, traceContext)
        is ObjectFieldAccessLocation         -> saveReferences(out, traceContext)
        is ArrayElementByIndexAccessLocation -> { /* no-op */ }
        is ArrayElementByNameAccessLocation  -> { /* no-op */ }
    }
}

private fun LocalVariableAccessLocation.saveReferences(out: TraceWriter, traceContext: TraceContext) {
    val variableDescriptorId = traceContext.variablePool.register(variableDescriptor)
    out.writeVariableDescriptor(variableDescriptorId)
}

private fun StaticFieldAccessLocation.saveReferences(out: TraceWriter, traceContext: TraceContext) {
    val fieldDescriptorId = traceContext.fieldPool.register(fieldDescriptor)
    out.writeFieldDescriptor(fieldDescriptorId)
}

private fun ObjectFieldAccessLocation.saveReferences(out: TraceWriter, traceContext: TraceContext) {
    val fieldDescriptorId = traceContext.fieldPool.register(fieldDescriptor)
    out.writeFieldDescriptor(fieldDescriptorId)
}