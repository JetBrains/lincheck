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
import java.io.DataInput
import java.io.DataOutput
import java.io.IOException
import java.util.EnumSet

/**
 * This file contains utilities for saving trace data in binary format using [DataInput] and [DataOutput].
 * This code is not split into serialization and deserialization parts to keep pairs of functions together,
 * so it is easy to verify that serialization and deserialization are inverses of each other.
 */

internal const val TRACE_MAGIC : Long = 0x706e547124ee5f70L
internal const val INDEX_MAGIC : Long = TRACE_MAGIC.inv()
internal const val TRACE_VERSION : Long = 23

// Buffer for saving trace in one piece
internal const val OUTPUT_BUFFER_SIZE: Int = 16 * 1024 * 1024

internal const val BLOCK_HEADER_SIZE: Int = Byte.SIZE_BYTES + Int.SIZE_BYTES
internal const val BLOCK_FOOTER_SIZE: Int = Byte.SIZE_BYTES

internal const val INDEX_CELL_SIZE: Int = Byte.SIZE_BYTES + Int.SIZE_BYTES + Long.SIZE_BYTES * 2

// ======== Trace File Header ========

// The trace data file and the index file each start with a magic-and-version pair: the data
// file uses [TRACE_MAGIC], the index file uses [INDEX_MAGIC], both followed by [TRACE_VERSION].

internal fun DataOutput.writeTraceHeader() {
    writeLong(TRACE_MAGIC)
    writeLong(TRACE_VERSION)
}

internal fun DataOutput.writeTraceIndexHeader() {
    writeLong(INDEX_MAGIC)
    writeLong(TRACE_VERSION)
}

internal fun DataInput.checkTraceHeader() {
    val magic = readLong()
    check(magic == TRACE_MAGIC) {
        "Wrong trace data magic 0x${magic.toString(16)}, expected 0x${TRACE_MAGIC.toString(16)}"
    }
    val version = readLong()
    check(version == TRACE_VERSION) {
        "Wrong trace data version $version, expected $TRACE_VERSION"
    }
}

internal fun DataInput.checkTraceIndexHeader() {
    val magic = readLong()
    check(magic == INDEX_MAGIC) {
        "Wrong trace index magic 0x${magic.toString(16)}, expected 0x${INDEX_MAGIC.toString(16)}"
    }
    val version = readLong()
    check(version == TRACE_VERSION) {
        "Wrong trace index version $version, expected $TRACE_VERSION"
    }
}

// ======== Object Kinds ========

internal enum class ObjectKind {
    THREAD_NAME,
    CLASS_DESCRIPTOR,
    METHOD_DESCRIPTOR,
    FIELD_DESCRIPTOR,
    VARIABLE_DESCRIPTOR,
    STRING,
    ACCESS_PATH,
    CODE_LOCATION,
    TRACEPOINT,
    TRACEPOINT_FOOTER,
    BLOCK_START,
    BLOCK_END,
    EOF,
}

// TODO: enum-ordinal write/read uses a signed byte (writeByte / readByte) — ordinals
//       beyond 127 would round-trip as negative bytes and be rejected by the bounds checks below.
//       Switch to writeByte / readUnsignedByte (or a wider type) if any enum here grows past ~127 entries.

internal fun DataOutput.writeKind(value: ObjectKind) {
    writeByte(value.ordinal)
}

internal fun DataInput.readKind(): ObjectKind {
    val ordinal = readByte().toInt()
    val values = ObjectKind.entries
    if (ordinal !in values.indices) {
        throw IOException("Cannot read ObjectKind: unknown ordinal $ordinal")
    }
    return values[ordinal]
}

// ======== Strings ========

internal fun DataOutput.writeString(value: String) {
    writeUTF(value)
}

internal fun DataInput.readString(): String {
    return readUTF()
}

internal fun DataOutput.writeNullableString(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeString(value)
}

internal fun DataInput.readNullableString(): String? {
    val hasString = readBoolean()
    return if (hasString) readString() else null
}

// ======== Thread Names ========

// The body of an `ObjectKind.THREAD_NAME` record: a thread id followed by its display name.
// The kind discriminator is written / consumed by the caller (see `TraceWriter.writeThreadName`
// and `loadThreadName` in `TraceReader.kt`).

internal fun DataOutput.writeThreadName(id: Int, name: String) {
    writeInt(id)
    writeString(name)
}

internal fun DataInput.readThreadName(): Pair<Int, String> {
    val id = readInt()
    val name = readString()
    return id to name
}

// ======== Types ========

internal fun DataOutput.writeType(value: Types.Type) {
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
            writeString(value.className)
        }
        is Types.ShortType -> writeByte(9)
        is Types.VoidType -> writeByte(10)
    }
}

internal fun DataInput.readType(): Types.Type {
    val type = readByte()
    return when (type.toInt()) {
        0 -> Types.ArrayType(readType())
        1 -> Types.BOOLEAN_TYPE
        2 -> Types.BYTE_TYPE
        3 -> Types.CHAR_TYPE
        4 -> Types.DOUBLE_TYPE
        5 -> Types.FLOAT_TYPE
        6 -> Types.INT_TYPE
        7 -> Types.LONG_TYPE
        8 -> Types.ObjectType(readString())
        9 -> Types.SHORT_TYPE
        10 -> Types.VOID_TYPE
        else -> error("Unknown Type id $type")
    }
}

// ======== Method Types ========

internal fun DataOutput.writeMethodType(value: Types.MethodType) {
    writeType(value.returnType)
    writeInt(value.argumentTypes.size)
    value.argumentTypes.forEach {
        writeType(it)
    }
}

internal fun DataInput.readMethodType(): Types.MethodType {
    val returnType = readType()
    val count = readInt()
    val argumentTypes = mutableListOf<Types.Type>()
    repeat(count) {
        argumentTypes.add(readType())
    }
    return Types.MethodType(argumentTypes, returnType)
}

// ======== Method Signatures ========

internal fun DataOutput.writeMethodSignature(value: MethodSignature) {
    writeString(value.name)
    writeMethodType(value.methodType)
}

internal fun DataInput.readMethodSignature(): MethodSignature {
    return MethodSignature(readString(), readMethodType())
}

// ======== Class Descriptors ========

internal fun DataOutput.writeClassDescriptor(value: ClassDescriptor) {
    writeString(value.name)
}

internal fun DataInput.readClassDescriptor(context: TraceContext): ClassDescriptor {
    return ClassDescriptor(context, readString())
}

// ======== Method Descriptors ========

internal fun DataOutput.writeMethodDescriptor(value: MethodDescriptor) {
    writeInt(value.classId)
    writeMethodSignature(value.methodSignature)
}

internal fun DataInput.readMethodDescriptor(context: TraceContext): MethodDescriptor {
    return MethodDescriptor(context, readInt(), readMethodSignature())
}

// ======== Field Descriptors ========

internal fun DataOutput.writeFieldDescriptor(value: FieldDescriptor) {
    writeInt(value.classId)
    writeString(value.fieldName)
    writeType(value.type)
    writeBoolean(value.isStatic)
    writeBoolean(value.isFinal)
    writeBoolean(value.isVolatile)
}

internal fun DataInput.readFieldDescriptor(context: TraceContext): FieldDescriptor {
    return FieldDescriptor(
        context = context,
        classId = readInt(),
        fieldName = readString(),
        type = readType(),
        fieldKind = FieldKind.fromIsStatic(isStatic = readBoolean()),
        isFinal = readBoolean(),
        isVolatile = readBoolean(),
    )
}

// ======== Variable Descriptors ========

internal fun DataOutput.writeVariableDescriptor(value: VariableDescriptor) {
    writeString(value.name)
    writeType(value.type)
}

internal fun DataInput.readVariableDescriptor(context: TraceContext): VariableDescriptor {
    return VariableDescriptor(context, readString(), readType())
}

// ======== Code Locations ========

internal enum class CodeLocationKind {
    LINE,
    ACCESS,
    METHOD_CALL,
}

internal val CodeLocation.kind: CodeLocationKind get() = when (this) {
    is LineCodeLocation -> CodeLocationKind.LINE
    is AccessCodeLocation -> CodeLocationKind.ACCESS
    is MethodCallCodeLocation -> CodeLocationKind.METHOD_CALL
}

internal fun DataOutput.writeCodeLocationKind(value: CodeLocationKind) {
    writeByte(value.ordinal)
}

internal fun DataInput.readCodeLocationKind(): CodeLocationKind {
    val ordinal = readByte().toInt()
    val values = CodeLocationKind.entries
    if (ordinal !in values.indices) {
        throw IOException("Cannot read CodeLocationKind: unknown ordinal $ordinal")
    }
    return values[ordinal]
}

// ======== Access Locations ========

// Contract: the `write*` and `read*` functions in this section only handle the access location's payload —
// they assume that all prerequisite descriptors (variables / fields)
// and access paths have already been registered in `context` by the surrounding orchestration code.
//
// The `write*` functions fail-fast via `check(...)` if a prerequisite is missing;
// the `read*` functions throw if the descriptor is not found in `context`
// (they rely on `context` lookups to return the already-registered descriptor by id).
//
// Why these `check(...)` calls are unique to this section:
// access-location writers take a typed object (e.g., `StaticFieldAccessLocation`) and resolve it
// to a descriptor id at serialization time via a pool lookup.
// Trace-point and `TRValue` writers receive already-resolved ids and never look up the pool,
// so they have nothing to assert at serialization time.
// Piggybacking the `check` on the lookup we have to do anyway turns
// an otherwise silent "wrote a bogus id" into a clear precondition violation
// pointing at the call site that produced it.

internal enum class AccessLocationKind {
    LOCAL_VARIABLE,
    STATIC_FIELD,
    OBJECT_FIELD,
    ARRAY_ELEMENT_BY_INDEX,
    ARRAY_ELEMENT_BY_NAME,
}

internal val AccessLocation.kind: AccessLocationKind get() = when (this) {
    is LocalVariableAccessLocation       -> AccessLocationKind.LOCAL_VARIABLE
    is StaticFieldAccessLocation         -> AccessLocationKind.STATIC_FIELD
    is ObjectFieldAccessLocation         -> AccessLocationKind.OBJECT_FIELD
    is ArrayElementByIndexAccessLocation -> AccessLocationKind.ARRAY_ELEMENT_BY_INDEX
    is ArrayElementByNameAccessLocation  -> AccessLocationKind.ARRAY_ELEMENT_BY_NAME
    else -> error("Unknown AccessLocation subtype: ${this::class}")
}

internal fun DataOutput.writeAccessLocationKind(value: AccessLocationKind) {
    writeByte(value.ordinal)
}

internal fun DataInput.readAccessLocationKind(): AccessLocationKind {
    val ordinal = readByte().toInt()
    val values = AccessLocationKind.entries
    if (ordinal !in values.indices) {
        throw IOException("Cannot read AccessLocationKind: unknown ordinal $ordinal")
    }
    return values[ordinal]
}

internal fun DataOutput.writeAccessLocation(context: TraceContext, value: AccessLocation) {
    writeAccessLocationKind(value.kind)
    when (value) {
        is LocalVariableAccessLocation       -> writeLocalVariableAccessLocation(context, value)
        is StaticFieldAccessLocation         -> writeStaticFieldAccessLocation(context, value)
        is ObjectFieldAccessLocation         -> writeObjectFieldAccessLocation(context, value)
        is ArrayElementByIndexAccessLocation -> writeArrayElementByIndexAccessLocation(context, value)
        is ArrayElementByNameAccessLocation  -> writeArrayElementByNameAccessLocation(context, value)
    }
}

internal fun DataInput.readAccessLocation(context: TraceContext): AccessLocation {
    return when (readAccessLocationKind()) {
        AccessLocationKind.LOCAL_VARIABLE           -> readLocalVariableAccessLocation(context)
        AccessLocationKind.STATIC_FIELD             -> readStaticFieldAccessLocation(context)
        AccessLocationKind.OBJECT_FIELD             -> readObjectFieldAccessLocation(context)
        AccessLocationKind.ARRAY_ELEMENT_BY_INDEX   -> readArrayElementByIndexAccessLocation(context)
        AccessLocationKind.ARRAY_ELEMENT_BY_NAME    -> readArrayElementByNameAccessLocation(context)
    }
}

private fun DataOutput.writeLocalVariableAccessLocation(context: TraceContext, value: LocalVariableAccessLocation) {
    check(context.variablePool.contains(value.variableDescriptor.key)) {
        "Access location references must be saved beforehand, but location $value has unsaved variable ${value.variableDescriptor}"
    }
    val variableDescriptorId = context.variablePool.getId(value.variableDescriptor.key)
    writeInt(variableDescriptorId)
}

private fun DataInput.readLocalVariableAccessLocation(context: TraceContext): LocalVariableAccessLocation {
    val variableDescriptorId = readInt()
    return LocalVariableAccessLocation(context.variablePool[variableDescriptorId])
}

private fun DataOutput.writeStaticFieldAccessLocation(context: TraceContext, value: StaticFieldAccessLocation) {
    check(context.fieldPool.contains(value.fieldDescriptor.key)) {
        "Access location references must be saved beforehand, but location $value has unsaved field ${value.fieldDescriptor}"
    }
    val fieldDescriptorId = context.fieldPool.getId(value.fieldDescriptor.key)
    writeInt(fieldDescriptorId)
}

private fun DataInput.readStaticFieldAccessLocation(context: TraceContext): StaticFieldAccessLocation {
    val fieldDescriptorId = readInt()
    return StaticFieldAccessLocation(context.fieldPool[fieldDescriptorId])
}

private fun DataOutput.writeObjectFieldAccessLocation(context: TraceContext, value: ObjectFieldAccessLocation) {
    check(context.fieldPool.contains(value.fieldDescriptor.key)) {
        "Access location references must be saved beforehand, but location $value has unsaved field ${value.fieldDescriptor}"
    }
    val fieldDescriptorId = context.fieldPool.getId(value.fieldDescriptor.key)
    writeInt(fieldDescriptorId)
}

private fun DataInput.readObjectFieldAccessLocation(context: TraceContext): ObjectFieldAccessLocation {
    val fieldDescriptorId = readInt()
    return ObjectFieldAccessLocation(context.fieldPool[fieldDescriptorId])
}

@Suppress("UNUSED_PARAMETER")
private fun DataOutput.writeArrayElementByIndexAccessLocation(context: TraceContext, value: ArrayElementByIndexAccessLocation) {
    writeInt(value.index)
}

@Suppress("UNUSED_PARAMETER")
private fun DataInput.readArrayElementByIndexAccessLocation(context: TraceContext): ArrayElementByIndexAccessLocation {
    val index = readInt()
    return ArrayElementByIndexAccessLocation(index)
}

private fun DataOutput.writeArrayElementByNameAccessLocation(context: TraceContext, value: ArrayElementByNameAccessLocation) {
    check(context.accessPathPool.contains(value.indexAccessPath)) {
        "Access location references must be saved beforehand, but location $value has unsaved access path ${value.indexAccessPath}"
    }
    val indexId = context.accessPathPool.getId(value.indexAccessPath)
    writeInt(indexId)
}

private fun DataInput.readArrayElementByNameAccessLocation(context: TraceContext): ArrayElementByNameAccessLocation {
    val accessPathId = readInt()
    return ArrayElementByNameAccessLocation(context.getAccessPath(accessPathId))
}

// ======== TR Values ========

internal fun DataOutput.writeTRValue(value: TRValue?) {
    when (value) {
        null -> {
            writeInt(TR_OBJECT_NULL_CLASSNAME)
        }

        is TRObject -> {
            // Negatives are special markers
            writeInt(value.classNameId)
            if (value.classNameId >= 0) {
                writeInt(value.identityHashCode)

                // Positive for objects
                writeInt(value.fields.size)
                value.fields.forEach { (fieldName, fieldValue) ->
                    writeString(fieldName)
                    this@writeTRValue.writeTRValue(fieldValue)
                }
            }
        }

        is TRPrimitive -> {
            writeInt(value.classNameId)
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
                    writeString(value.primitiveValue)
                }

                is String -> writeString(value.primitiveValue) // Both STRING and RAW_STRING
                is Boolean -> writeBoolean(value.primitiveValue)
                is Unit -> {}
                else -> error("Unknown primitive value ${value.primitiveValue}")
            }
        }

        is TRArray -> {
            writeInt(value.classNameId)
            if (value.classNameId >= 0) {
                writeInt(value.identityHashCode)

                // Negative for arrays where -1 is empty array
                val encodedElementsSize = (value.capturedElements.size + 1) * -1
                writeInt(encodedElementsSize)
                value.capturedElements.forEach { element -> this@writeTRValue.writeTRValue(element) }
                writeInt(value.totalSize)
            }
        }
    }
}

internal fun DataInput.readTRValue(context: TraceContext): TRValue? {
    return when (val classNameId = readInt()) {
        TR_OBJECT_NULL_CLASSNAME -> null
        TR_OBJECT_VOID_CLASSNAME -> TR_OBJECT_VOID
        TR_OBJECT_P_BYTE -> TRPrimitive(classNameId, 0, readByte())
        TR_OBJECT_P_SHORT -> TRPrimitive(classNameId, 0, readShort())
        TR_OBJECT_P_INT -> TRPrimitive(classNameId, 0, readInt())
        TR_OBJECT_P_LONG -> TRPrimitive(classNameId, 0, readLong())
        TR_OBJECT_P_FLOAT -> TRPrimitive(classNameId, 0, readFloat())
        TR_OBJECT_P_DOUBLE -> TRPrimitive(classNameId, 0, readDouble())
        TR_OBJECT_P_CHAR -> TRPrimitive(classNameId, 0, readChar())
        TR_OBJECT_P_STRING -> TRPrimitive(classNameId, 0, readString())
        TR_OBJECT_P_UNIT -> TRPrimitive(classNameId, 0, Unit)
        TR_OBJECT_P_RAW_STRING -> TRPrimitive(classNameId, 0, readString())
        TR_OBJECT_P_BOOLEAN -> TRPrimitive(classNameId, 0, readBoolean())
        TR_OBJECT_P_JAVA_CLASS -> TRPrimitive(classNameId, 0, readString())
        TR_OBJECT_P_KOTLIN_CLASS -> TRPrimitive(classNameId, 0, readString())
        TR_OBJECT_P_STRING_BUILDER -> TRPrimitive(classNameId, readInt(), readString())
        else if (classNameId >= 0) -> {
            val identityHashCode = readInt()
            val childrenSize = readInt()
            if (childrenSize < 0) {
                val decodedElementsSize = childrenSize * -1 - 1
                val capturedElements = buildList {
                    repeat(decodedElementsSize) {
                        add(readTRValue(context))
                    }
                }
                val totalSize = readInt()
                TRArray(classNameId, identityHashCode, context.classPool[classNameId], totalSize, capturedElements)
            } else {
                val fields = buildMap {
                    repeat(childrenSize) {
                        val fieldName = readString()
                        val fieldValue = readTRValue(context)
                        put(fieldName, fieldValue)
                    }
                }
                TRObject(classNameId, identityHashCode, context.classPool[classNameId], fields)
            }
        }
        else -> error("TRObject: Unknown Class Id $classNameId")
    }
}

// ======== Diff Status ========

internal fun DataOutput.writeDiffStatus(value: DiffStatus?): Unit {
    writeByte(value?.ordinal ?: -1)
}

internal fun DataInput.readDiffStatus(): DiffStatus? {
    val ordinal = readByte().toInt()
    if (ordinal == -1) return null

    val values = DiffStatus.entries
    if (ordinal !in values.indices) {
        throw IOException("Cannot read DiffStatus: unknown ordinal $ordinal")
    }
    return values[ordinal]
}

// Wire layout for a diff statuses set: a single signed-byte size prefix followed by [size] diff-status bytes.
// A size of `-1` denotes `null`, distinguishing the absent set from an empty set.

internal fun DataOutput.writeDiffStatusesSet(statuses: EnumSet<DiffStatus>?) {
    if (statuses == null) {
        writeByte(-1)
        return
    }
    writeByte(statuses.size)
    statuses.forEach { writeDiffStatus(it) }
}

internal fun DataInput.readDiffStatusesSet(): EnumSet<DiffStatus>? {
    val size = readByte().toInt()
    if (size == -1) return null

    val set = EnumSet.noneOf(DiffStatus::class.java)
    repeat(size) { set.add(readDiffStatus()) }
    return set
}

// ======== Trace Points ========

// This section contains the trace-point dispatch infrastructure:
// a [TRTracePointKind] enum keyed by wire byte (one entry per [TRTracePoint] subclass),
// the matching kind discriminator, the children-diff-statuses pair used by container tracepoints,
// and the top-level [writeTRTracePoint] and [readTRTracePoint] entry points.

// The enum ordinal is the on-wire class id.
// If you reorder entries — remember to update `TRACE_VERSION`
// (kind numeration order is part of serialization format).
internal enum class TRTracePointKind {
    // writes
    WRITE_FIELD,
    WRITE_ARRAY,
    WRITE_LOCAL_VARIABLE,

    // reads
    READ_FIELD,
    READ_ARRAY,
    READ_LOCAL_VARIABLE,

    // method calls
    METHOD_CALL,

    // loops
    LOOP,
    LOOP_ITERATION,

    // exceptions
    THROW,
    CATCH,

    // breakpoints
    SNAPSHOT_LINE_BREAKPOINT,
}

internal val TRTracePointKind.isContainer: Boolean
    get() = when (this) {
        TRTracePointKind.METHOD_CALL, TRTracePointKind.LOOP, TRTracePointKind.LOOP_ITERATION -> true
        else -> false
    }

internal val TRTracePoint.kind: TRTracePointKind get() = when (this) {
    is TRWriteFieldTracePoint               -> TRTracePointKind.WRITE_FIELD
    is TRWriteArrayTracePoint               -> TRTracePointKind.WRITE_ARRAY
    is TRWriteLocalVariableTracePoint       -> TRTracePointKind.WRITE_LOCAL_VARIABLE
    is TRReadFieldTracePoint                -> TRTracePointKind.READ_FIELD
    is TRReadArrayTracePoint                -> TRTracePointKind.READ_ARRAY
    is TRReadLocalVariableTracePoint        -> TRTracePointKind.READ_LOCAL_VARIABLE
    is TRMethodCallTracePoint               -> TRTracePointKind.METHOD_CALL
    is TRLoopTracePoint                     -> TRTracePointKind.LOOP
    is TRLoopIterationTracePoint            -> TRTracePointKind.LOOP_ITERATION
    is TRThrowTracePoint                    -> TRTracePointKind.THROW
    is TRCatchTracePoint                    -> TRTracePointKind.CATCH
    is TRSnapshotLineBreakpointTracePoint   -> TRTracePointKind.SNAPSHOT_LINE_BREAKPOINT
}

internal fun DataOutput.writeTRTracePointKind(value: TRTracePointKind) {
    writeByte(value.ordinal)
}

internal fun DataInput.readTRTracePointKind(): TRTracePointKind {
    val ordinal = readByte().toInt()
    val values = TRTracePointKind.entries
    if (ordinal !in values.indices) {
        throw IOException("Cannot read TRTracePointKind: unknown ordinal $ordinal")
    }
    return values[ordinal]
}

// The `writeTRTracePoint` and `readTRTracePoint` functions are the only
// public entry points for serializing/deserializing a tracepoint.
//
// They handle the kind discriminator byte, the common header (codeLocationId, threadId, eventId, diffStatus),
// and the children diff-statuses set for container tracepoints;
// then dispatch to a per-subclass body writer/reader that emits/consumes the subclass-specific fields.
//
// The per-subclass body writers/readers are private to this file on purpose:
// every tracepoint on the wire begins with the common header,
// and the only way to honor that contract is to go through these dispatchers.

internal fun DataOutput.writeTRTracePoint(value: TRTracePoint) {
    writeTRTracePointKind(value.kind)
    writeInt(value.codeLocationId)
    writeInt(value.threadId)
    writeInt(value.eventId)
    writeDiffStatus(value.diffStatus)
    if (value is TRContainerTracePoint) {
        writeDiffStatusesSet(value.childrenDiffStatuses)
    }

    when (value) {
        // writes
        is TRWriteFieldTracePoint             -> writeFieldTracePoint(value)
        is TRWriteArrayTracePoint             -> writeArrayTracePoint(value)
        is TRWriteLocalVariableTracePoint     -> writeLocalVariableTracePoint(value)

        // reads
        is TRReadFieldTracePoint              -> writeFieldTracePoint(value)
        is TRReadArrayTracePoint              -> writeArrayTracePoint(value)
        is TRReadLocalVariableTracePoint      -> writeLocalVariableTracePoint(value)

        // method calls
        is TRMethodCallTracePoint             -> writeMethodCallTracePoint(value)

        // loops
        is TRLoopTracePoint                   -> writeLoopTracePoint(value)
        is TRLoopIterationTracePoint          -> writeLoopIterationTracePoint(value)

        // exceptions
        is TRThrowTracePoint                  -> writeExceptionProcessingTracePoint(value)
        is TRCatchTracePoint                  -> writeExceptionProcessingTracePoint(value)

        // breakpoints
        is TRSnapshotLineBreakpointTracePoint -> writeSnapshotLineBreakpointTracePoint(value)
    }
}

internal fun DataInput.readTRTracePoint(context: TraceContext): TRTracePoint {
    val kind = readTRTracePointKind()
    val codeLocationId = readInt()
    val threadId = readInt()
    val eventId = readInt()
    val diffStatus = readDiffStatus()
    val childrenDiffStatuses = if (kind.isContainer) readDiffStatusesSet() else null

    val tracePoint = when (kind) {
        // fields
        TRTracePointKind.WRITE_FIELD,
        TRTracePointKind.READ_FIELD ->
            readFieldTracePoint(context, kind, codeLocationId, threadId, eventId)

        // arrays
        TRTracePointKind.WRITE_ARRAY,
        TRTracePointKind.READ_ARRAY ->
            readArrayTracePoint(context, kind, codeLocationId, threadId, eventId)

        // local variables
        TRTracePointKind.WRITE_LOCAL_VARIABLE,
        TRTracePointKind.READ_LOCAL_VARIABLE ->
            readLocalVariableTracePoint(context, kind, codeLocationId, threadId, eventId)

        // method calls
        TRTracePointKind.METHOD_CALL ->
            readMethodCallTracePoint(context, codeLocationId, threadId, eventId)

        // loops
        TRTracePointKind.LOOP           ->
            readLoopTracePoint(context, codeLocationId, threadId, eventId)
        TRTracePointKind.LOOP_ITERATION ->
            readLoopIterationTracePoint(context, codeLocationId, threadId, eventId)

        // exceptions
        TRTracePointKind.THROW,
        TRTracePointKind.CATCH ->
            readExceptionProcessingTracePoint(context, kind, codeLocationId, threadId, eventId)

        // breakpoints
        TRTracePointKind.SNAPSHOT_LINE_BREAKPOINT ->
            readSnapshotLineBreakpointTracePoint(context, codeLocationId, threadId, eventId)
    }

    if (diffStatus != null) {
        tracePoint.diffStatus = diffStatus
    }
    if (tracePoint is TRContainerTracePoint) {
        check(kind.isContainer) {
            "Container tracepoint of kind $kind is not marked as container"
        }
        tracePoint.childrenDiffStatuses = childrenDiffStatuses
    }

    return tracePoint
}

// ======== Per-subclass body (de)serialization helpers ========

// These functions handle ONLY the subclass-specific body bytes.
// The kind discriminator, the common header (codeLocationId, threadId, eventId, diffStatus),
// and the children-diff-statuses set (for container tracepoints)
// are emitted/consumed inline by `writeTRTracePoint`/ `readTRTracePoint` above.
//
// All per-subclass body helpers are `private` to this file:
// tracepoint bytes can only be produced/consumed through `writeTRTracePoint`/`readTRTracePoint`,
// which guarantees that the common header is in place.
//
// Per-subclass footer helpers stay `internal` because the model classes' `saveFooter`/`loadFooter` methods
// invoke them between bookkeeping calls that can't be inlined here.

// -------- Field --------
//
// Shared writer for both `TRReadFieldTracePoint` and `TRWriteFieldTracePoint` (their bodies
// are identical; the kind byte in the header tells them apart on the read side).

private fun DataOutput.writeFieldTracePoint(value: TRFieldTracePoint) {
    writeInt(value.fieldId)
    writeTRValue(value.obj)
    writeTRValue(value.value)
}

private fun DataInput.readFieldTracePoint(
    context: TraceContext,
    kind: TRTracePointKind,
    codeLocationId: Int,
    threadId: Int,
    eventId: Int,
): TRFieldTracePoint {
    val fieldId = readInt()
    val obj = readTRValue(context)
    val value = readTRValue(context)
    return when (kind) {
        TRTracePointKind.READ_FIELD  ->
            TRReadFieldTracePoint(context, threadId, codeLocationId, fieldId, obj, value, eventId)
        TRTracePointKind.WRITE_FIELD ->
            TRWriteFieldTracePoint(context, threadId, codeLocationId, fieldId, obj, value, eventId)
        else ->
            error("Unexpected kind for field tracepoint: $kind")
    }
}

// -------- Array --------

private fun DataOutput.writeArrayTracePoint(value: TRArrayTracePoint) {
    writeTRValue(value.array)
    writeInt(value.index)
    writeTRValue(value.value)
}

private fun DataInput.readArrayTracePoint(
    context: TraceContext,
    kind: TRTracePointKind,
    codeLocationId: Int,
    threadId: Int,
    eventId: Int,
): TRArrayTracePoint {
    val array = readTRValue(context) ?: TR_OBJECT_NULL
    val index = readInt()
    val value = readTRValue(context)
    return when (kind) {
        TRTracePointKind.READ_ARRAY  ->
            TRReadArrayTracePoint(context, threadId, codeLocationId, array, index, value, eventId)
        TRTracePointKind.WRITE_ARRAY ->
            TRWriteArrayTracePoint(context, threadId, codeLocationId, array, index, value, eventId)
        else -> error("Unexpected kind for array tracepoint: $kind")
    }
}

// -------- Local Variable --------

private fun DataOutput.writeLocalVariableTracePoint(value: TRLocalVariableTracePoint) {
    writeInt(value.localVariableId)
    writeTRValue(value.value)
}

private fun DataInput.readLocalVariableTracePoint(
    context: TraceContext,
    kind: TRTracePointKind,
    codeLocationId: Int,
    threadId: Int,
    eventId: Int,
): TRLocalVariableTracePoint {
    val localVariableId = readInt()
    val value = readTRValue(context)
    return when (kind) {
        TRTracePointKind.READ_LOCAL_VARIABLE  ->
            TRReadLocalVariableTracePoint(context, threadId, codeLocationId, localVariableId, value, eventId)
        TRTracePointKind.WRITE_LOCAL_VARIABLE ->
            TRWriteLocalVariableTracePoint(context, threadId, codeLocationId, localVariableId, value, eventId)
        else -> error("Unexpected kind for local-variable tracepoint: $kind")
    }
}

// -------- Method Call --------

private fun DataOutput.writeMethodCallTracePoint(value: TRMethodCallTracePoint) {
    writeInt(value.methodId)
    writeTRValue(value.obj)
    writeInt(value.parameters.size)
    value.parameters.forEach { writeTRValue(it) }
    writeShort(value.flags.toInt())
}

private fun DataInput.readMethodCallTracePoint(
    context: TraceContext,
    codeLocationId: Int,
    threadId: Int,
    eventId: Int,
): TRMethodCallTracePoint {
    val methodId = readInt()
    val obj = readTRValue(context)
    val parametersCount = readInt()
    val parameters = List(parametersCount) { readTRValue(context) }
    val flags = readShort()
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

internal fun DataOutput.writeMethodCallTracePointFooter(value: TRMethodCallTracePoint) {
    writeTRValue(value.result)
    writeNullableString(value.exceptionClassName)
}

internal fun DataInput.readMethodCallTracePointFooter(context: TraceContext, value: TRMethodCallTracePoint) {
    value.result = readTRValue(context)
    value.exceptionClassName = readNullableString()
}

// -------- Loop --------

private fun DataOutput.writeLoopTracePoint(value: TRLoopTracePoint) {
    writeInt(value.loopId)
}

private fun DataInput.readLoopTracePoint(
    context: TraceContext,
    codeLocationId: Int,
    threadId: Int,
    eventId: Int,
): TRLoopTracePoint {
    val loopId = readInt()
    return TRLoopTracePoint(
        context = context,
        threadId = threadId,
        codeLocationId = codeLocationId,
        loopId = loopId,
        eventId = eventId,
    )
}

internal fun DataOutput.writeLoopTracePointFooter(value: TRLoopTracePoint) {
    writeInt(value.iterations)
}

internal fun DataInput.readLoopTracePointFooter(value: TRLoopTracePoint) {
    value.iterations = readInt()
}

// -------- Loop Iteration --------

// Note: `TRLoopIterationTracePoint` has no footer body — its footer consists solely of the
// container-tracepoint footer-position bookkeeping handled by the wrapper.

private fun DataOutput.writeLoopIterationTracePoint(value: TRLoopIterationTracePoint) {
    writeInt(value.loopId)
    writeInt(value.loopIteration)
}

private fun DataInput.readLoopIterationTracePoint(
    context: TraceContext,
    codeLocationId: Int,
    threadId: Int,
    eventId: Int,
): TRLoopIterationTracePoint {
    val loopId = readInt()
    val loopIteration = readInt()
    return TRLoopIterationTracePoint(
        context = context,
        threadId = threadId,
        codeLocationId = codeLocationId,
        loopId = loopId,
        loopIteration = loopIteration,
        eventId = eventId,
    )
}

// -------- Exception Processing --------

private fun DataOutput.writeExceptionProcessingTracePoint(value: TRExceptionProcessingTracePoint) {
    writeTRValue(value.exception)
}

private fun DataInput.readExceptionProcessingTracePoint(
    context: TraceContext,
    kind: TRTracePointKind,
    codeLocationId: Int,
    threadId: Int,
    eventId: Int,
): TRExceptionProcessingTracePoint {
    val exception = readTRValue(context) ?: TR_OBJECT_NULL
    return when (kind) {
        TRTracePointKind.THROW -> TRThrowTracePoint(context, threadId, codeLocationId, exception, eventId)
        TRTracePointKind.CATCH -> TRCatchTracePoint(context, threadId, codeLocationId, exception, eventId)
        else -> error("Unexpected kind for exception-processing tracepoint: $kind")
    }
}

// -------- Snapshot Line Breakpoint --------

private fun DataOutput.writeSnapshotLineBreakpointTracePoint(value: TRSnapshotLineBreakpointTracePoint) {
    writeLong(value.breakpointUuid.mostSignificantBits)
    writeLong(value.breakpointUuid.leastSignificantBits)
    writeInt(value.stackTraceCodeLocationIds.size)
    value.stackTraceCodeLocationIds.forEach { writeInt(it) }
    writeLong(value.currentTimeMillis)
    writeInt(value.locals.size)
    value.locals.forEach { writeTRValue(it) }
    writeNullableString(value.traceId)
}

private fun DataInput.readSnapshotLineBreakpointTracePoint(
    context: TraceContext,
    codeLocationId: Int,
    threadId: Int,
    eventId: Int,
): TRSnapshotLineBreakpointTracePoint {
    val breakpointUuid = java.util.UUID(readLong(), readLong())
    val size = readInt()
    val stackTraceCodeLocationIds = List(size) { readInt() }
    val currentTimeMillis = readLong()
    val localsSize = readInt()
    val locals = List(localsSize) { readTRValue(context) }
    val traceId = readNullableString()
    return TRSnapshotLineBreakpointTracePoint(
        context = context,
        codeLocationId = codeLocationId,
        threadId = threadId,
        breakpointUuid = breakpointUuid,
        stackTraceCodeLocationIds = stackTraceCodeLocationIds,
        currentTimeMillis = currentTimeMillis,
        locals = locals,
        traceId = traceId,
        eventId = eventId,
    )
}