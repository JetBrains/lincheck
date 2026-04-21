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
import org.jetbrains.lincheck.util.Logger
import org.jetbrains.lincheck.util.collections.Bitmap
import org.jetbrains.lincheck.util.collections.SimpleBitmap
import java.io.Closeable
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.OutputStream
import kotlin.reflect.KClass

// Buffer for saving trace in one piece
internal const val OUTPUT_BUFFER_SIZE: Int = 16 * 1024 * 1024

/**
 * It is a strategy to collect trace: it can be full-track in memory or streaming to a file on-the-fly
 */
interface TraceCollectingStrategy {
    /**
     * Register the current thread in strategy.
     */
    fun registerCurrentThread(threadId: Int)

    /**
     * Makes sure that thread has written all of its recorded data.
     */
    fun completeThread(thread: Thread)

    /**
     * Must be called when a new tracepoint is created.
     *
     * @param parent Current top of the call stack, if exists.
     * @param created New tracepoint
     */
    fun tracePointCreated(parent: TRContainerTracePoint?, created: TRTracePoint)

    /**
     * Must be called when the container trace point is ended and popped from the trace tree.
     *
     * @param thread thread of the [container] trace point.
     * @param container the completed container trace point.
     */
    fun completeContainerTracePoint(thread: Thread, container: TRContainerTracePoint)

    /**
     * Must be called when the trace is finished
     */
    fun traceEnded()
}


/**
 * It is a strategy to save one tracepoint.
 */
internal interface TraceWriter : DataOutput, Closeable {
    /**
     * Saves dependencies of [TRValue], if needed.
     * This must be called before [startWriteAnyTracepoint] for all used [TRValue]s.
     */
    fun preWriteTRValue(value: TRValue?)

    /**
     * Saves [TRValue] itself.
     * Must be called after [startWriteAnyTracepoint] or [startWriteContainerTracepointFooter].
     */
    fun writeTRValue(value: TRValue?)

    /**
     * Marks the beginning of a tracepoint (before the first byte of tracepoint is written).
     */
    fun startWriteAnyTracepoint()

    /**
     * Marks the end of the leaf (fix-sized) tracepoint.
     */
    fun endWriteLeafTracepoint()

    /**
     * Mark the end of the container tracepoint's header (now only TRMethodCallTracepoint, TRLoopTracePoint, and TRLoopIterationTracePoint are container ones).
     */
    fun endWriteContainerTracepointHeader(id: Int)

    /**
     * Mark the beginning of container tracepoint's footer (After all children are saved).
     */
    fun startWriteContainerTracepointFooter()

    /**
     * Marks the end of container tracepoint's footer.
     */
    fun endWriteContainerTracepointFooter(id: Int)

    /**
     * Write [name] of the thread.
     * This must be called before [startWriteAnyTracepoint] or [startWriteContainerTracepointFooter] for all thread names.
     */
    fun writeThreadName(id: Int, name: String)

    /**
     * Write [ClassDescriptor] from context referred by given `id`, if needed.
     * This must be called before [startWriteAnyTracepoint] or [startWriteContainerTracepointFooter] for all used class descriptors.
     */
    fun writeClassDescriptor(id: Int)

    /**
     * Write [MethodDescriptor] from context referred by given `id`, if needed.
     * This must be called before [startWriteAnyTracepoint] or [startWriteContainerTracepointFooter] for all used method descriptors.
     */
    fun writeMethodDescriptor(id: Int)

    /**
     * Write [FieldDescriptor] from context referred by given `id`, if needed.
     * This must be called before [startWriteAnyTracepoint] or [startWriteContainerTracepointFooter] for all used field descriptors.
     */
    fun writeFieldDescriptor(id: Int)

    /**
     * Write [VariableDescriptor] from context referred by given `id` if needed.
     * This must be called before [startWriteAnyTracepoint] or [startWriteContainerTracepointFooter] for all used variable descriptors.
     */
    fun writeVariableDescriptor(id: Int)

    /**
     * Write [StackTraceElement] from context referred by given code location `id`, if needed.
     * This must be called before [startWriteAnyTracepoint] or [startWriteContainerTracepointFooter] for all used code locations.
     */
    fun writeCodeLocation(id: Int)
}

/**
 * Interface to check and mark if a given piece of reference data was already stored.
 */
internal interface TraceContextSavedState {
    fun isDescriptorSaved(descriptorClass: KClass<*>, id: Int): Boolean
    fun markDescriptorSaved(descriptorClass: KClass<*>, id: Int)
}

internal inline fun <reified T> TraceContextSavedState.isDescriptorSaved(id: Int) = isDescriptorSaved(T::class, id)
internal inline fun <reified T> TraceContextSavedState.markDescriptorSaved(id: Int) = markDescriptorSaved(T::class, id)

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
    // Stack of "container" tracepoints
    private val containerStack = mutableListOf<Pair<Int, Long>>()

    private var inTracepointBody = false
    private var footerPosition: Long = -1

    protected abstract val currentDataPosition: Long
    abstract val writerId: Int

    override fun close() {
        dataOutput.writeKind(ObjectKind.EOF)
        dataStream.close()

        writeIndexCell(ObjectKind.EOF,-1, -1, -1)
    }

    override fun preWriteTRValue(value: TRValue?) {
        check(!inTracepointBody) { "Cannot write TRObject dependency into tracepoint body" }
        if (value == null || value is TRPrimitive || value.isSpecial) return
        writeClassDescriptor(value.classNameId)
        // Recursively register class descriptors for all field values
        if (value is TRObject) {
            value.fields.values.forEach { fieldValue ->
                preWriteTRValue(fieldValue)
            }
        }
        
        if (value is TRArray) {
            value.capturedElements.forEach { capturedElement ->
                preWriteTRValue(capturedElement)
            }
        }
    }

    override fun writeTRValue(value: TRValue?) {
        check(inTracepointBody) { "Cannot write TRObject outside tracepoint body" }
        dataOutput.writeTRValue(value)
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
        containerStack.add(id to currentDataPosition)
    }

    override fun startWriteContainerTracepointFooter() {
        check(!inTracepointBody) { "Cannot start nested tracepoint footer" }
        inTracepointBody = true
        footerPosition = currentDataPosition

        check(containerStack.isNotEmpty()) {
            "Calls endWriteContainerTracepointHeader() / startWriteContainerTracepointFooter() are not balanced"
        }

        // Start object
        dataOutput.writeKind(ObjectKind.TRACEPOINT_FOOTER)
    }

    override fun endWriteContainerTracepointFooter(id: Int) {
        check(inTracepointBody) { "Cannot end tracepoint footer not in tracepoint" }
        check(footerPosition >= 0) { "Cannot end tracepoint footer not in tracepoint footer" }

        val (storedId, startPos) = containerStack.removeLast()
        check(id == storedId) {
            "Calls endWriteContainerTracepointHeader($storedId) / startWriteContainerTracepointFooter($id) are not balanced"
        }
        writeIndexCell(ObjectKind.TRACEPOINT, id, startPos, footerPosition)

        inTracepointBody = false
        footerPosition = -1
    }

    override fun writeThreadName(id: Int, name: String) {
        check(!inTracepointBody) { "Cannot write thread name inside tracepoint" }

        val position = currentDataPosition
        dataOutput.writeKind(ObjectKind.THREAD_NAME)
        dataOutput.writeInt(id)
        dataOutput.writeUTF(name)
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
        // All strings only once. It will have duplications with class and method descriptors,
        // but size loss is negligible and this way is simpler
        val fileNameId = writeString(stackTrace.fileName)
        val classNameId = writeString(stackTrace.className)
        val methodNameId = writeString(stackTrace.methodName)
        val accessPathId = writeAccessPath(accessPath)
        val argumentNamesIds = argumentNames?.map { writeAccessPath(it) }
        val activeLocalNameIds = activeLocals?.map { writeString(it.localName) }

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
        dataOutput.writeUTF(value)
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
                    location.save(this, context)
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
        footerPosition = -1
    }


    protected abstract fun writeIndexCell(kind: ObjectKind, id: Int, startPos: Long, endPos: Long)
}


internal open class SimpleTraceContextSavedState: TraceContextSavedState {
    protected open val seenClassDescriptors = SimpleBitmap()
    val classDescriptors: Set<Int> = seenClassDescriptors
    protected open val seenMethodDescriptors = SimpleBitmap()
    val methodDescriptors: Set<Int> = seenMethodDescriptors
    protected open val seenFieldDescriptors = SimpleBitmap()
    val fieldDescriptors: Set<Int> = seenFieldDescriptors
    protected open val seenVariableDescriptors = SimpleBitmap()
    val variableDescriptors: Set<Int> = seenVariableDescriptors
    protected open val seenStringDescriptors = SimpleBitmap()
    val strings: Set<Int> = seenStringDescriptors
    protected open val seenCodeLocationDescriptors = SimpleBitmap()
    val codeLocations: Set<Int> = seenCodeLocationDescriptors
    protected open val seenAccessPathDescriptors = SimpleBitmap()
    val accessPaths: Set<Int> = seenAccessPathDescriptors

    override fun isDescriptorSaved(descriptorClass: KClass<*>, id: Int): Boolean {
        val bitmap = getBitmapArray(descriptorClass) ?: return false
        return bitmap.isSet(id)
    }

    override fun markDescriptorSaved(descriptorClass: KClass<*>, id: Int) {
        val bitmap = getBitmapArray(descriptorClass) ?: return
        bitmap.set(id)
    }

    private fun getBitmapArray(descriptorClass: KClass<*>): Bitmap? {
        return when (descriptorClass) {
            ClassDescriptor::class -> seenClassDescriptors
            MethodDescriptor::class -> seenMethodDescriptors
            FieldDescriptor::class -> seenFieldDescriptors
            VariableDescriptor::class -> seenVariableDescriptors
            String::class -> seenStringDescriptors
            CodeLocation::class -> seenCodeLocationDescriptors
            AccessPath::class -> seenAccessPathDescriptors
            else -> {
                Logger.error { "Unknown descriptor class: $descriptorClass" }
                null
            }
        }
    }
}