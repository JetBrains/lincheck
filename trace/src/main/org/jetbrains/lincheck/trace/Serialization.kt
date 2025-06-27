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

import java.io.*

private const val OUTPUT_BUFFER_SIZE: Int = 16*1024*1024

const val TRACE_MAGIC : Long = 0x706e547124ee5f70L
const val INDEX_MAGIC : Long = TRACE_MAGIC.inv()
const val TRACE_VERSION : Long = 7

private enum class ObjectKind {
    CLASS_DESCRIPTOR, METHOD_DESCRIPTOR, FIELD_DESCRIPTOR, VARIABLE_DESCRIPTOR, STRING, CODE_LOCATION, TRACEPOINT, TRACEPOINT_FOOTER, EOF
}

/**
 * It is a strategy to save tracepoints.
 */
internal interface TraceWriter : DataOutput {
    fun preWriteTRObject(value: TRObject?)
    fun writeTRObject(value: TRObject?)

    fun startWriteAnyTracepoint()

    fun endWriteContainerTracepointHeader(id: Int)
    fun startWriteContainerTracepointFooter(id: Int)

    fun writeClassDescriptor(id: Int)
    fun writeMethodDescriptor(id: Int)
    fun writeFieldDescriptor(id: Int)
    fun writeVariableDescriptor(id: Int)
    fun writeCodeLocation(id: Int)
}

private class LongOutputStream(
    private val out: OutputStream
) : OutputStream() {
    private var position: Long = 0

    val currentPosition: Long get() = position

    override fun write(b: Int) {
        out.write(b)
        position += 1
    }

    override fun write(b: ByteArray?) {
        out.write(b)
        position += b?.size ?: 0
    }

    override fun write(b: ByteArray?, off: Int, len: Int) {
        out.write(b, off, len)
        position += len
    }

    override fun flush() = out.flush()

    override fun close() = out.close()
}

private class TwoStreamTraceWriter private constructor(
    private val pos: LongOutputStream,
    private val index: DataOutputStream,
    private val context: TraceContext,
    private val data: DataOutputStream = DataOutputStream(pos),
): TraceWriter, Closeable, DataOutput by data {
    constructor(dataStream: OutputStream, indexStream: OutputStream, context: TraceContext) :
            this(
                pos = LongOutputStream( dataStream.buffered(OUTPUT_BUFFER_SIZE)),
                index = DataOutputStream(indexStream.buffered(OUTPUT_BUFFER_SIZE)),
                context = context
            )

    private val seenClassDescriptors = Array(context.classDescriptors.size, { _ -> false })
    private val seenMethodDescriptors = Array(context.methodDescriptors.size, { _ -> false })
    private val seenFieldDescriptors = Array(context.fieldDescriptors.size, { _ -> false })
    private val seenVariableDescriptors = Array(context.variableDescriptors.size, { _ -> false })
    private val seenCodeLocations = Array(context.codeLocations.size, { _ -> false })

    private val stringCache = mutableMapOf<String, Int>()

    // Stack of "container" tracepoints
    private val containerStack = mutableListOf<Pair<Int, Long>>()

    init {
        data.writeLong(TRACE_MAGIC)
        data.writeLong(TRACE_VERSION)

        index.writeLong(INDEX_MAGIC)
        index.writeLong(TRACE_VERSION)
    }

    override fun close() {
        index.writeLong(INDEX_MAGIC)
        data.writeKind(ObjectKind.EOF)
        data.close()
        index.close()
    }

    override fun preWriteTRObject(value: TRObject?) {
        if (value == null || value.isPrimitive || value.isSpecial) return
        writeClassDescriptor(value.classNameId)
    }

    override fun writeTRObject(value: TRObject?) = data.writeTRObject(value)

    override fun startWriteAnyTracepoint() = data.writeKind(ObjectKind.TRACEPOINT)

    override fun endWriteContainerTracepointHeader(id: Int) {
        // Store where container content starts
        containerStack.add(id to pos.currentPosition)
    }

    override fun startWriteContainerTracepointFooter(id: Int) {
        check(containerStack.isNotEmpty()) {
            "Calls endWriteContainerTracepointHeader() / startWriteContainerTracepointFooter($id) are not balanced"
        }
        val (storedId, startPos) = containerStack.removeLast()
        check(id == storedId) {
            "Calls endWriteContainerTracepointHeader($storedId) / startWriteContainerTracepointFooter($id) are not balanced"
        }
        writeIndexCell(ObjectKind.TRACEPOINT, id, startPos, pos.currentPosition)

        // Start object
        data.writeKind(ObjectKind.TRACEPOINT_FOOTER)
    }

    override fun writeClassDescriptor(id: Int) {
        if (seenClassDescriptors[id]) return

        // Write class descriptor into data and position into index
        val position = pos.currentPosition
        data.writeKind(ObjectKind.CLASS_DESCRIPTOR)
        data.writeInt(id)
        data.writeClassDescriptor(context.getClassDescriptor(id))

        writeIndexCell(ObjectKind.CLASS_DESCRIPTOR, id, position)
        seenClassDescriptors[id] = true
    }

    override fun writeMethodDescriptor(id: Int) {
        if (seenMethodDescriptors[id]) return

        val descriptor = context.getMethodDescriptor(id)
        writeClassDescriptor(descriptor.classId)
        // Write method descriptor into data and position into index
        val position = pos.currentPosition
        data.writeKind(ObjectKind.METHOD_DESCRIPTOR)
        data.writeInt(id)
        data.writeMethodDescriptor(descriptor)

        writeIndexCell(ObjectKind.METHOD_DESCRIPTOR, id, position)
        seenMethodDescriptors[id] = true
    }

    override fun writeFieldDescriptor(id: Int) {
        if (seenFieldDescriptors[id]) return

        val descriptor = context.getFieldDescriptor(id)
        writeClassDescriptor(descriptor.classId)
        // Write field descriptor into data and position into index
        val position = pos.currentPosition
        data.writeKind(ObjectKind.FIELD_DESCRIPTOR)
        data.writeInt(id)
        data.writeFieldDescriptor(descriptor)

        writeIndexCell(ObjectKind.FIELD_DESCRIPTOR, id, position)
        seenFieldDescriptors[id] = true
    }

    override fun writeVariableDescriptor(id: Int) {
        if (seenVariableDescriptors[id]) return

        // Write variable descriptor into data and position into index
        val position = pos.currentPosition
        data.writeKind(ObjectKind.VARIABLE_DESCRIPTOR)
        data.writeInt(id)
        data.writeVariableDescriptor(context.getVariableDescriptor(id))

        writeIndexCell(ObjectKind.VARIABLE_DESCRIPTOR, id, position)
        seenVariableDescriptors[id] = true
    }

    override fun writeCodeLocation(id: Int) {
        if (id == UNKNOWN_CODE_LOCATION_ID || seenCodeLocations[id]) return

        val codeLocation = context.stackTrace(id)
        // All strings only once. It will have duplications with class and method descriptors,
        // but size loss is negligible and this way is simplier
        val fileNameId = writeString(codeLocation.fileName)
        val classNameId = writeString(codeLocation.className)
        val methodNameId = writeString(codeLocation.methodName)

        // Code location into data and position into index
        val position = pos.currentPosition
        data.writeKind(ObjectKind.CODE_LOCATION)
        data.writeInt(id)
        data.writeInt(fileNameId)
        data.writeInt(classNameId)
        data.writeInt(methodNameId)
        data.writeInt(codeLocation.lineNumber)

        writeIndexCell(ObjectKind.CODE_LOCATION, id, position)
        seenCodeLocations[id] = true
    }

    private fun writeString(value: String?): Int {
        if (value == null) return -1

        var id = stringCache[value]
        if (id != null) return id

        id = stringCache.size
        stringCache[value] = id

        val position = pos.currentPosition
        data.writeKind(ObjectKind.STRING)
        data.writeInt(id)
        data.writeUTF(value)

        writeIndexCell(ObjectKind.STRING, id, position)

        return id
    }

    private fun writeIndexCell(kind: ObjectKind, id: Int, startPos: Long, endPos: Long = -1) {
        index.writeByte(kind.ordinal)
        index.writeInt(id)
        index.writeLong(startPos)
        index.writeLong(endPos)
    }
}

fun saveRecorderTrace(data: OutputStream, index: OutputStream, context: TraceContext, rootCallsPerThread: List<TRTracePoint>) {
    check(context == TRACE_CONTEXT) { "Now only global TRACE_CONTEXT is supported" }

    TwoStreamTraceWriter(data, index, context).use { tw ->
        rootCallsPerThread.forEach { root ->
            saveTRTracepoint(tw, root)
        }
    }
}

private fun saveTRTracepoint(writer: TraceWriter, tracepoint: TRTracePoint) {
    tracepoint.save(writer)
    if (tracepoint is TRMethodCallTracePoint) {
        tracepoint.events.forEach {
            saveTRTracepoint(writer, it)
        }
        tracepoint.saveFooter(writer)
    }
}

fun loadRecordedTrace(inp: InputStream): Pair<TraceContext, List<TRTracePoint>> {
    DataInputStream(inp.buffered(OUTPUT_BUFFER_SIZE)).use { input ->
        val magic = input.readLong()
        if (magic != TRACE_MAGIC) {
            error("Wrong magic 0x${(magic.toString(16))}, expected ${TRACE_MAGIC.toString(16)}")
        }

        val version = input.readLong()
        if (version != TRACE_VERSION) {
            error("Wrong version $version (expected $TRACE_VERSION)")
        }

        val context = TRACE_CONTEXT // TraceContext()
        val roots = mutableListOf<TRTracePoint>()
        val stringCache = mutableListOf<String?>()
        // Load objects
        val eof = loadObjectsEagerly(input, context, stringCache, roots)
        check(eof == ObjectKind.EOF) {
            "Input contains unbalanced method call tracepoint: footer without main data"
        }

        return context to roots
    }
}

private fun loadObjectsEagerly(input: DataInput, context: TraceContext, stringCache: MutableList<String?>, tracepoints: MutableList<TRTracePoint>): ObjectKind {
    while (true) {
        val kind = input.readKind()
        when (kind) {
            ObjectKind.CLASS_DESCRIPTOR -> loadClassDescriptor(input, context)
            ObjectKind.METHOD_DESCRIPTOR -> loadMethodDescriptor(input, context)
            ObjectKind.FIELD_DESCRIPTOR -> loadFieldDescriptor(input, context)
            ObjectKind.VARIABLE_DESCRIPTOR -> loadVariableDescriptor(input, context)
            ObjectKind.STRING -> loadString(input, stringCache)
            ObjectKind.CODE_LOCATION -> loadCodeLocation(input, context, stringCache)
            ObjectKind.TRACEPOINT -> tracepoints.add(loadTracePointEagerly(input, context, stringCache))
            ObjectKind.TRACEPOINT_FOOTER -> return kind
            ObjectKind.EOF -> return kind
        }
    }
}

private fun loadClassDescriptor(
    input: DataInput,
    context: TraceContext
) {
    val id = input.readInt()
    val descriptor = input.readClassDescriptor()
    context.restoreClassDescriptor(id,descriptor)
}

private fun loadMethodDescriptor(
    input: DataInput,
    context: TraceContext
) {
    val id = input.readInt()
    val descriptor = input.readMethodDescriptor(context)
    context.restoreMethodDescriptor(id,descriptor)
}

private fun loadFieldDescriptor(
    input: DataInput,
    context: TraceContext
) {
    val id = input.readInt()
    val descriptor = input.readFieldDescriptor(context)
    context.restoreFieldDescriptor(id,descriptor)
}

private fun loadVariableDescriptor(
    input: DataInput,
    context: TraceContext
) {
    val id = input.readInt()
    val descriptor = input.readVariableDescriptor()
    context.restoreVariableDescriptor(id,descriptor)
}

private fun loadString(
    input: DataInput,
    stringCache: MutableList<String?>
) {
    val id = input.readInt()
    val value = input.readUTF()
    while (stringCache.size <= id) {
        stringCache.add(null)
    }
    stringCache[id] = value
}

private fun loadCodeLocation(
    input: DataInput,
    context: TraceContext,
    stringCache: MutableList<String?>
) {
    val id = input.readInt()

    val fileNameId = input.readInt()
    val classNameId = input.readInt()
    val methodNameId = input.readInt()
    val lineNumber = input.readInt()

    val ste = StackTraceElement(
        /* declaringClass = */ stringCache[classNameId],
        /* methodName = */ stringCache[methodNameId],
        /* fileName = */ stringCache[fileNameId],
        /* lineNumber = */ lineNumber
    )
    context.restoreCodeLocation(id, ste)
}

private fun loadTracePointEagerly(input: DataInput, context: TraceContext, stringCache: MutableList<String?>): TRTracePoint {
    val tracePoint = loadTRTracePoint(input)
    if (tracePoint !is TRMethodCallTracePoint) return tracePoint
    // Load all children
    val kind = loadObjectsEagerly(input, context, stringCache, tracePoint.events)
    check(kind == ObjectKind.TRACEPOINT_FOOTER) {
        "Input contains unbalanced method call tracepoint: tracepoint without footer"
    }
    tracePoint.loadFooter(input)
    return tracePoint
}

private fun DataOutput.writeType(value: Types.Type) {
    when (value) {
        is Types.ArrayType -> {
            writeByte(0)
            writeType(value.elementType)
        }
        is Types.BooleanType -> writeByte(1)
        is Types.ByteType -> writeByte(2)
        is Types.CharType -> writeByte(3)
        is Types.DoubleType -> writeByte(4)
        is Types.FloatType -> writeByte(5)
        is Types.IntType -> writeByte(6)
        is Types.LongType -> writeByte(7)
        is Types.ObjectType -> {
            writeByte(8)
            writeUTF(value.className)
        }
        is Types.ShortType ->writeByte(9)
        is Types.VoidType -> writeByte(10)
    }
}

private fun DataOutput.writeMethodType(value: Types.MethodType) {
    writeType(value.returnType)
    writeInt(value.argumentTypes.size)
    value.argumentTypes.forEach {
        writeType(it)
    }
}

private fun DataInput.readType(): Types.Type {
    val type = readByte()
    return when (type.toInt()) {
        0 -> Types.ArrayType(readType())
        1 -> Types.BooleanType()
        2 -> Types.ByteType()
        3 -> Types.CharType()
        4 -> Types.DoubleType()
        5 -> Types.FloatType()
        6 -> Types.IntType()
        7 -> Types.LongType()
        8 -> Types.ObjectType(readUTF())
        9 -> Types.ShortType()
        10 -> Types.VoidType()
        else -> error("Unknown Type id $type")
    }
}

private fun DataInput.readMethodType(): Types.MethodType {
    val returnType = readType()
    val count = readInt()
    val argumentTypes = mutableListOf<Types.Type>()
    repeat(count) {
        argumentTypes.add(readType())
    }
    return Types.MethodType(argumentTypes, returnType)
}

private fun DataOutput.writeClassDescriptor(value: ClassDescriptor) {
    writeUTF(value.name)
}

private fun DataInput.readClassDescriptor(): ClassDescriptor {
    return ClassDescriptor(readUTF())
}

private fun DataOutput.writeMethodDescriptor(value: MethodDescriptor) {
   writeInt(value.classId)
   writeMethodSignature(value.methodSignature)
}

private fun DataInput.readMethodDescriptor(context: TraceContext): MethodDescriptor {
    return MethodDescriptor(context,readInt(), readMethodSignature())
}

private fun DataOutput.writeMethodSignature(value: MethodSignature) {
    writeUTF(value.name)
    writeMethodType(value.methodType)
}

private fun DataInput.readMethodSignature(): MethodSignature {
    return MethodSignature(readUTF(), readMethodType())
}

private fun DataOutput.writeFieldDescriptor(value: FieldDescriptor) {
    writeInt(value.classId)
    writeUTF(value.fieldName)
    writeBoolean(value.isStatic)
    writeBoolean(value.isFinal)
}

private fun DataInput.readFieldDescriptor(context: TraceContext): FieldDescriptor {
    return FieldDescriptor(
        context = context,
        classId = readInt(),
        fieldName = readUTF(),
        isStatic = readBoolean(),
        isFinal = readBoolean()
    )
}

private fun DataOutput.writeVariableDescriptor(value: VariableDescriptor) {
    writeUTF(value.name)
}

private fun DataInput.readVariableDescriptor(): VariableDescriptor {
    return VariableDescriptor(readUTF())
}


private fun DataOutput.writeKind(value: ObjectKind) = writeByte(value.ordinal)

private fun DataInput.readKind(): ObjectKind {
    val ordinal = readByte()
    val values = ObjectKind.entries
    if (ordinal < 0 || ordinal > values.size) {
        throw InvalidObjectException("Cannot read ObjectKind: unknown ordinal $ordinal")
    }
    return values[ordinal.toInt()]
}

/**
 * Converts a string representing a class name in internal format (e.g., "com/example/MyClass")
 * into a canonical class name format with (e.g., "com.example.MyClass").
 */
private fun String.toCanonicalClassName() =
    this.replace('/', '.')

/**
 * Converts a string representing a class name in canonical format (e.g., "com.example.MyClass")
 * into an internal class name format with (e.g., "com/example/MyClass").
 */
private fun String.toInternalClassName() =
    this.replace('.', '/')
