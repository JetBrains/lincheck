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
import java.util.UUID

/**
 * This file contains utilities for saving trace data in binary format using [DataInput] and [DataOutput].
 * This code is not split into serialization and deserialization parts to keep pairs of functions together,
 * so it is easy to verify that serialization and deserialization are inverses of each other.
 */

internal const val TRACE_MAGIC : Long = 0x706e547124ee5f70L
internal const val INDEX_MAGIC : Long = TRACE_MAGIC.inv()
/** Binary trace-format version this build produces and consumes. */
const val TRACE_VERSION : Long = 30

// Buffer for saving trace in one piece
internal const val OUTPUT_BUFFER_SIZE: Int = 16 * 1024 * 1024

internal const val BLOCK_HEADER_SIZE: Int = Byte.SIZE_BYTES + Int.SIZE_BYTES
internal const val BLOCK_FOOTER_SIZE: Int = Byte.SIZE_BYTES

internal const val INDEX_CELL_SIZE: Int = Byte.SIZE_BYTES + Int.SIZE_BYTES + Long.SIZE_BYTES * 2

// ======== Trace File Header ========

// The trace data file and the index file each start with a magic-and-version pair: the data
// file uses [TRACE_MAGIC], the index file uses [INDEX_MAGIC], both followed by [TRACE_VERSION].
//
// The data header then names the runtime that produced the trace (e.g. [RUNTIME_JVM]),
// so a client of one runtime can read a trace made by another:
// code addressing and type-name spellings are per-runtime conventions,
// and a saved trace has no hello message to carry them.
//
// The index header carries no runtime: an index is only ever opened together with its data file,
// and it holds ids and byte offsets, nothing whose meaning depends on the producer.

/**
 * Writes the data-file prelude, taking the producing runtime as a parameter because the header names it.
 *
 * Every writer in this module passes [RUNTIME_JVM]: they serve JVM producers only.
 */
internal fun DataOutput.writeTraceHeader(runtime: String) {
    writeLong(TRACE_MAGIC)
    writeLong(TRACE_VERSION)
    writeString(runtime)
}

internal fun DataOutput.writeTraceIndexHeader() {
    writeLong(INDEX_MAGIC)
    writeLong(TRACE_VERSION)
}

/**
 * Validates the data-file prelude and returns the runtime that produced the trace.
 *
 * An unrecognised runtime is not an error: it is returned as read.
 */
internal fun DataInput.checkTraceHeader(): String {
    val magic = readLong()
    check(magic == TRACE_MAGIC) {
        "Wrong trace data magic 0x${magic.toString(16)}, expected 0x${TRACE_MAGIC.toString(16)}"
    }
    val version = readLong()
    check(version == TRACE_VERSION) {
        "Wrong trace data version $version, expected $TRACE_VERSION"
    }
    return readString()
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

// ======== UUIDs ========

private fun DataOutput.writeUUID(value: UUID) {
    writeLong(value.mostSignificantBits)
    writeLong(value.leastSignificantBits)
}

private fun DataInput.readUUID(): UUID {
    val mostSignificantBits = readLong()
    val leastSignificantBits = readLong()
    return UUID(mostSignificantBits, leastSignificantBits)
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
    LOOP,
}

internal val CodeLocation.kind: CodeLocationKind get() = when (this) {
    is LineCodeLocation -> CodeLocationKind.LINE
    is LoopHeaderCodeLocation -> CodeLocationKind.LOOP
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
// Trace-point and `TraceValue` writers receive already-resolved ids and never look up the pool,
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

// The enum ordinal is the on-wire kind discriminator (byte). One entry per concrete `TraceValue`
// subclass, plus one per discriminated flavour where a single subclass carries several:
// the eight `TraceScalar` encodings, the two `TraceTypeReference` flavours.
//
// If you reorder entries — remember to update `TRACE_VERSION` (kind numeration order is part of
// the serialization format).
internal enum class TraceValueKind {
    // language-level singletons
    NULL,
    VOID,
    UNIT,

    // scalars
    SCALAR_BYTE,
    SCALAR_SHORT,
    SCALAR_INT,
    SCALAR_LONG,
    SCALAR_FLOAT,
    SCALAR_DOUBLE,
    SCALAR_CHAR,
    SCALAR_BOOLEAN,

    // value-like types
    STRING,
    ENUM,
    ARBITRARY_INTEGER,
    ARBITRARY_DECIMAL,

    // reference-like types
    OBJECT,
    OBJECT_SNAPSHOT,
    ARRAY,
    ARRAY_SNAPSHOT,

    // char sequence
    TEXT_SNAPSHOT,

    // exception (Throwable) — class descriptor + identity
    EXCEPTION,
    // exception snapshot (Throwable) — class descriptor + identity + message + rendered stack-trace frames
    EXCEPTION_SNAPSHOT,

    // reflection class types
    JAVA_CLASS,
    KOTLIN_CLASS,

    // synthetic markers
    UNFINISHED_METHOD_RESULT,
    UNTRACKED_METHOD_RESULT,

    // capture-time redaction marker
    REDACTED,

    // pre-rendered leaf value from an agent that cannot capture the value structurally
    RENDERED,

    // key-value container
    MAP_SNAPSHOT,
}

internal fun DataOutput.writeTraceValueKind(value: TraceValueKind) {
    writeByte(value.ordinal)
}

internal fun DataInput.readTraceValueKind(): TraceValueKind {
    val ordinal = readByte().toInt()
    val values = TraceValueKind.entries
    if (ordinal !in values.indices) {
        throw IOException("Cannot read TraceValueKind: unknown ordinal $ordinal")
    }
    return values[ordinal]
}

internal fun DataOutput.writeTraceValue(value: TraceValue) {
    when (value) {
        is TraceNull -> writeTraceValueKind(TraceValueKind.NULL)
        is TraceVoid -> writeTraceValueKind(TraceValueKind.VOID)
        is TraceUnit -> writeTraceValueKind(TraceValueKind.UNIT)
        is TraceRedacted -> {
            writeTraceValueKind(TraceValueKind.REDACTED)
            val classDescriptor = value.classDescriptor
            writeBoolean(classDescriptor != null)
            if (classDescriptor != null) writeClassDescriptor(classDescriptor)
            val templateUuid = value.templateUuid
            writeBoolean(templateUuid != null)
            if (templateUuid != null) writeUUID(templateUuid)
            writeNullableString(value.templateName)
        }

        is TraceRenderedValue -> {
            writeTraceValueKind(TraceValueKind.RENDERED)
            writeString(value.rendered)
        }

        // scalars
        is TraceScalar -> when (val v = value.value) {
            is Byte    -> { writeTraceValueKind(TraceValueKind.SCALAR_BYTE);       writeByte(v.toInt())  }
            is Short   -> { writeTraceValueKind(TraceValueKind.SCALAR_SHORT);      writeShort(v.toInt()) }
            is Int     -> { writeTraceValueKind(TraceValueKind.SCALAR_INT);        writeInt(v)           }
            is Long    -> { writeTraceValueKind(TraceValueKind.SCALAR_LONG);       writeLong(v)          }
            is Float   -> { writeTraceValueKind(TraceValueKind.SCALAR_FLOAT);      writeFloat(v)         }
            is Double  -> { writeTraceValueKind(TraceValueKind.SCALAR_DOUBLE);     writeDouble(v)        }
            is Char    -> { writeTraceValueKind(TraceValueKind.SCALAR_CHAR);       writeChar(v.code)     }
            is Boolean -> { writeTraceValueKind(TraceValueKind.SCALAR_BOOLEAN);    writeBoolean(v)       }

            else -> error("Unknown primitive value $v")
        }

        // value-like types
        is TraceString -> {
            writeTraceValueKind(TraceValueKind.STRING)
            writeString(value.value)
        }
        is TraceEnum -> {
            writeTraceValueKind(TraceValueKind.ENUM)
            writeInt(value.classDescriptor.id)
            writeNullableString(value.name)
        }
        is TraceArbitraryInteger -> {
            writeTraceValueKind(TraceValueKind.ARBITRARY_INTEGER)
            writeString(value.value)
        }
        is TraceArbitraryDecimal -> {
            writeTraceValueKind(TraceValueKind.ARBITRARY_DECIMAL)
            writeString(value.value)
        }

        // reference-like types
        is TraceObject -> {
            writeTraceValueKind(TraceValueKind.OBJECT)
            writeInt(value.classDescriptor.id)
            writeLong(value.identity)
            writeNullableString(value.rendered)
        }
        is TraceObjectSnapshot -> {
            writeTraceValueKind(TraceValueKind.OBJECT_SNAPSHOT)
            writeInt(value.classDescriptor.id)
            writeLong(value.identity)
            writeNullableString(value.rendered)
            writeInt(value.fields.size)
            value.fields.forEach { (fieldName, fieldValue) ->
                writeString(fieldName)
                this@writeTraceValue.writeTraceValue(fieldValue)
            }
        }
        is TraceArray -> {
            writeTraceValueKind(TraceValueKind.ARRAY)
            writeInt(value.classDescriptor.id)
            writeLong(value.identity)
            writeInt(value.totalSize)
        }
        is TraceArraySnapshot -> {
            writeTraceValueKind(TraceValueKind.ARRAY_SNAPSHOT)
            writeInt(value.classDescriptor.id)
            writeLong(value.identity)
            writeInt(value.totalSize)
            writeInt(value.capturedElements.size)
            value.capturedElements.forEach { element -> this@writeTraceValue.writeTraceValue(element) }
        }
        is TraceMapSnapshot -> {
            writeTraceValueKind(TraceValueKind.MAP_SNAPSHOT)
            writeInt(value.classDescriptor.id)
            writeLong(value.identity)
            writeInt(value.totalSize)
            writeInt(value.capturedEntries.size)
            value.capturedEntries.forEach { (key, entryValue) ->
                this@writeTraceValue.writeTraceValue(key)
                this@writeTraceValue.writeTraceValue(entryValue)
            }
        }

        // char sequence
        is TraceTextSnapshot -> {
            writeTraceValueKind(TraceValueKind.TEXT_SNAPSHOT)
            writeInt(value.classDescriptor.id)
            writeLong(value.identity)
            writeString(value.content)
        }

        // exception
        is TraceException -> {
            writeTraceValueKind(TraceValueKind.EXCEPTION)
            writeInt(value.classDescriptor.id)
            writeLong(value.identity)
        }

        // exception snapshot
        is TraceExceptionSnapshot -> {
            writeTraceValueKind(TraceValueKind.EXCEPTION_SNAPSHOT)
            writeInt(value.classDescriptor.id)
            writeLong(value.identity)
            writeTraceValue(value.message)
            writeInt(value.stackTrace.size)
            value.stackTrace.forEach { writeString(it) }
        }

        // reflection class types
        is TraceTypeReference -> {
            writeTraceValueKind(
                when (value.flavor) {
                    TypeFlavor.JAVA_CLASS -> TraceValueKind.JAVA_CLASS
                    TypeFlavor.KOTLIN_CLASS -> TraceValueKind.KOTLIN_CLASS
                }
            )
            writeString(value.referencedClassName)
        }

        // synthetic markers
        is TraceUnfinishedMethodResult -> writeTraceValueKind(TraceValueKind.UNFINISHED_METHOD_RESULT)
        is TraceUntrackedMethodResult -> writeTraceValueKind(TraceValueKind.UNTRACKED_METHOD_RESULT)
    }
}

internal fun DataInput.readTraceValue(context: TraceContext): TraceValue = when (readTraceValueKind()) {
    // language-level singletons
    TraceValueKind.NULL -> TraceNull
    TraceValueKind.VOID -> TraceVoid
    TraceValueKind.UNIT -> TraceUnit
    TraceValueKind.REDACTED -> TraceRedacted(
        classDescriptor = if (readBoolean()) readClassDescriptor(context) else null,
        templateUuid = if (readBoolean()) readUUID() else null,
        templateName = readNullableString(),
    )
    TraceValueKind.RENDERED -> TraceRenderedValue(readString())

    // scalars
    TraceValueKind.SCALAR_BYTE    -> TraceScalar(readByte())
    TraceValueKind.SCALAR_SHORT   -> TraceScalar(readShort())
    TraceValueKind.SCALAR_INT     -> TraceScalar(readInt())
    TraceValueKind.SCALAR_LONG    -> TraceScalar(readLong())
    TraceValueKind.SCALAR_FLOAT   -> TraceScalar(readFloat())
    TraceValueKind.SCALAR_DOUBLE  -> TraceScalar(readDouble())
    TraceValueKind.SCALAR_CHAR    -> TraceScalar(readChar())
    TraceValueKind.SCALAR_BOOLEAN -> TraceScalar(readBoolean())

    // value-like types
    TraceValueKind.STRING -> TraceString(readString())
    TraceValueKind.ENUM -> TraceEnum(context.classPool[readInt()], readNullableString())
    TraceValueKind.ARBITRARY_INTEGER -> TraceArbitraryInteger(readString())
    TraceValueKind.ARBITRARY_DECIMAL -> TraceArbitraryDecimal(readString())

    // reference-like types
    TraceValueKind.OBJECT -> {
        val cd = context.classPool[readInt()]
        val identity = readLong()
        val toStr = readNullableString()
        TraceObject(cd, identity, toStr)
    }
    TraceValueKind.OBJECT_SNAPSHOT -> {
        val cd = context.classPool[readInt()]
        val identity = readLong()
        val toStr = readNullableString()
        val fieldsSize = readInt()
        val fields = buildMap {
            repeat(fieldsSize) {
                val fieldName = readString()
                val fieldValue = readTraceValue(context)
                put(fieldName, fieldValue)
            }
        }
        TraceObjectSnapshot(cd, identity, toStr, fields)
    }
    TraceValueKind.ARRAY -> {
        val cd = context.classPool[readInt()]
        val hash = readLong()
        val totalSize = readInt()
        TraceArray(cd, hash, totalSize)
    }
    TraceValueKind.ARRAY_SNAPSHOT -> {
        val cd = context.classPool[readInt()]
        val hash = readLong()
        val totalSize = readInt()
        val capturedSize = readInt()
        val capturedElements = buildList { repeat(capturedSize) { add(readTraceValue(context)) } }
        TraceArraySnapshot(cd, hash, totalSize, capturedElements)
    }
    TraceValueKind.MAP_SNAPSHOT -> {
        val cd = context.classPool[readInt()]
        val hash = readLong()
        val totalSize = readInt()
        val capturedSize = readInt()
        val capturedEntries = buildList {
            repeat(capturedSize) { add(readTraceValue(context) to readTraceValue(context)) }
        }
        TraceMapSnapshot(cd, hash, totalSize, capturedEntries)
    }

    // char sequence
    TraceValueKind.TEXT_SNAPSHOT -> {
        val cd = context.classPool[readInt()]
        val hash = readLong()
        val content = readString()
        TraceTextSnapshot(cd, hash, content)
    }

    // exception
    TraceValueKind.EXCEPTION -> {
        val cd = context.classPool[readInt()]
        val hash = readLong()
        TraceException(cd, hash)
    }

    // exception snapshot
    TraceValueKind.EXCEPTION_SNAPSHOT -> {
        val cd = context.classPool[readInt()]
        val hash = readLong()
        val message = readTraceValue(context)
        val framesSize = readInt()
        val stackTrace = buildList { repeat(framesSize) { add(readString()) } }
        TraceExceptionSnapshot(cd, hash, message, stackTrace)
    }

    // reflection class types
    TraceValueKind.JAVA_CLASS -> TraceTypeReference(readString(), TypeFlavor.JAVA_CLASS)
    TraceValueKind.KOTLIN_CLASS -> TraceTypeReference(readString(), TypeFlavor.KOTLIN_CLASS)

    // synthetic markers
    TraceValueKind.UNFINISHED_METHOD_RESULT -> TraceUnfinishedMethodResult
    TraceValueKind.UNTRACKED_METHOD_RESULT -> TraceUntrackedMethodResult
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
// a [TracePointKind] enum keyed by wire byte (one entry per [TracePoint] subclass),
// the matching kind discriminator, the children-diff-statuses pair used by container tracepoints,
// and the top-level [writeTracePointData] and [readTracePointData] entry points.

// The enum ordinal is the on-wire class id.
// If you reorder entries — remember to update `TRACE_VERSION`
// (kind numeration order is part of serialization format).
internal enum class TracePointKind {
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
    METHOD_CALL_RESULT,

    // loops
    LOOP,
    LOOP_END,
    LOOP_ITERATION,
    LOOP_ITERATION_END,

    // exceptions
    THROW,
    CATCH,

    // breakpoints
    SNAPSHOT_LINE_BREAKPOINT,
}

internal val TracePointKind.isContainer: Boolean
    get() = when (this) {
        TracePointKind.METHOD_CALL, TracePointKind.LOOP, TracePointKind.LOOP_ITERATION -> true
        else -> false
    }

/** `true` for the kinds of the closing side of a container tracepoint (see [ContainerFooterTracePoint]). */
internal val TracePointKind.isContainerEnd: Boolean
    get() = when (this) {
        TracePointKind.METHOD_CALL_RESULT,
        TracePointKind.LOOP_END,
        TracePointKind.LOOP_ITERATION_END -> true
        else -> false
    }

internal val TracePoint.kind: TracePointKind get() = when (this) {
    is WriteFieldTracePoint               -> TracePointKind.WRITE_FIELD
    is WriteArrayTracePoint               -> TracePointKind.WRITE_ARRAY
    is WriteLocalVariableTracePoint       -> TracePointKind.WRITE_LOCAL_VARIABLE
    is ReadFieldTracePoint                -> TracePointKind.READ_FIELD
    is ReadArrayTracePoint                -> TracePointKind.READ_ARRAY
    is ReadLocalVariableTracePoint        -> TracePointKind.READ_LOCAL_VARIABLE
    is MethodCallTracePoint               -> TracePointKind.METHOD_CALL
    is MethodCallResultTracePoint         -> TracePointKind.METHOD_CALL_RESULT
    is LoopTracePoint                     -> TracePointKind.LOOP
    is LoopEndTracePoint                  -> TracePointKind.LOOP_END
    is LoopIterationTracePoint            -> TracePointKind.LOOP_ITERATION
    is LoopIterationEndTracePoint         -> TracePointKind.LOOP_ITERATION_END
    is ThrowTracePoint                    -> TracePointKind.THROW
    is CatchTracePoint                    -> TracePointKind.CATCH
    is SnapshotLineBreakpointTracePoint   -> TracePointKind.SNAPSHOT_LINE_BREAKPOINT
}

/** Placeholder [ContainerFooterTracePoint.containerEventId] for tracepoints which do not close a container. */
private const val NO_CONTAINER_EVENT_ID: Int = -1

internal fun DataOutput.writeTracePointKind(value: TracePointKind) {
    writeByte(value.ordinal)
}

internal fun DataInput.readTracePointKind(): TracePointKind {
    val ordinal = readByte().toInt()
    val values = TracePointKind.entries
    if (ordinal !in values.indices) {
        throw IOException("Cannot read TracePointKind: unknown ordinal $ordinal")
    }
    return values[ordinal]
}

// The `writeTracePointData` and `readTracePointData` functions are the only
// public entry points for serializing/deserializing a tracepoint.
//
// They handle the kind discriminator byte, the common header (codeLocationId, threadId, eventId, diffStatus),
// and the children diff-statuses set for container tracepoints;
// then dispatch to a per-subclass body writer/reader that emits/consumes the subclass-specific fields.
//
// The per-subclass body writers/readers are private to this file on purpose:
// every tracepoint on the wire begins with the common header,
// and the only way to honor that contract is to go through these dispatchers.

internal fun DataOutput.writeTracePointData(value: TracePoint) {
    writeTracePointKind(value.kind)
    writeInt(value.codeLocationId)
    writeInt(value.threadId)
    writeInt(value.eventId)
    writeDiffStatus(value.diffStatus)
    if (value is ContainerHeaderTracePoint) {
        writeDiffStatusesSet(value.childrenDiffStatuses)
    }
    if (value is ContainerFooterTracePoint) {
        writeInt(value.containerEventId)
    }

    when (value) {
        // writes
        is WriteFieldTracePoint             -> writeFieldTracePoint(value)
        is WriteArrayTracePoint             -> writeArrayTracePoint(value)
        is WriteLocalVariableTracePoint     -> writeLocalVariableTracePoint(value)

        // reads
        is ReadFieldTracePoint              -> writeFieldTracePoint(value)
        is ReadArrayTracePoint              -> writeArrayTracePoint(value)
        is ReadLocalVariableTracePoint      -> writeLocalVariableTracePoint(value)

        // method calls
        is MethodCallTracePoint             -> writeMethodCallTracePoint(value)
        is MethodCallResultTracePoint       -> writeMethodCallResultTracePoint(value)

        // loops
        is LoopTracePoint                   -> writeLoopTracePoint(value)
        is LoopEndTracePoint                -> writeLoopEndTracePoint(value)
        is LoopIterationTracePoint          -> writeLoopIterationTracePoint(value)
        // an iteration's closing record carries nothing beyond the common header
        is LoopIterationEndTracePoint       -> {}

        // exceptions
        is ThrowTracePoint                  -> writeExceptionProcessingTracePoint(value)
        is CatchTracePoint                  -> writeExceptionProcessingTracePoint(value)

        // breakpoints
        is SnapshotLineBreakpointTracePoint -> writeSnapshotLineBreakpointTracePoint(value)
    }
}

internal fun DataInput.readTracePointData(context: TraceContext): TracePoint {
    val kind = readTracePointKind()
    val codeLocationId = readInt()
    val threadId = readInt()
    val eventId = readInt()
    val diffStatus = readDiffStatus()
    val childrenDiffStatuses = if (kind.isContainer) readDiffStatusesSet() else null
    val containerEventId = if (kind.isContainerEnd) readInt() else NO_CONTAINER_EVENT_ID

    val tracePoint = when (kind) {
        // fields
        TracePointKind.WRITE_FIELD,
        TracePointKind.READ_FIELD ->
            readFieldTracePoint(context, kind, codeLocationId, threadId, eventId)

        // arrays
        TracePointKind.WRITE_ARRAY,
        TracePointKind.READ_ARRAY ->
            readArrayTracePoint(context, kind, codeLocationId, threadId, eventId)

        // local variables
        TracePointKind.WRITE_LOCAL_VARIABLE,
        TracePointKind.READ_LOCAL_VARIABLE ->
            readLocalVariableTracePoint(context, kind, codeLocationId, threadId, eventId)

        // method calls
        TracePointKind.METHOD_CALL ->
            readMethodCallTracePoint(context, codeLocationId, threadId, eventId)
        TracePointKind.METHOD_CALL_RESULT ->
            readMethodCallResultTracePoint(context, codeLocationId, threadId, eventId, containerEventId)

        // loops
        TracePointKind.LOOP           ->
            readLoopTracePoint(context, codeLocationId, threadId, eventId)
        TracePointKind.LOOP_END       ->
            readLoopEndTracePoint(context, codeLocationId, threadId, eventId, containerEventId)
        TracePointKind.LOOP_ITERATION ->
            readLoopIterationTracePoint(context, codeLocationId, threadId, eventId)
        TracePointKind.LOOP_ITERATION_END ->
            LoopIterationEndTracePoint(context, threadId, codeLocationId, containerEventId, eventId)

        // exceptions
        TracePointKind.THROW,
        TracePointKind.CATCH ->
            readExceptionProcessingTracePoint(context, kind, codeLocationId, threadId, eventId)

        // breakpoints
        TracePointKind.SNAPSHOT_LINE_BREAKPOINT ->
            readSnapshotLineBreakpointTracePoint(context, codeLocationId, threadId, eventId)
    }

    if (diffStatus != null) {
        tracePoint.diffStatus = diffStatus
    }
    if (tracePoint is ContainerHeaderTracePoint) {
        check(kind.isContainer) {
            "Container tracepoint of kind $kind is not marked as container"
        }
        tracePoint.childrenDiffStatuses = childrenDiffStatuses
    }

    return tracePoint
}

/**
 * Reads the closing side of [container] and attaches it to the container.
 *
 * @throws IllegalStateException if the record read is not the closing side of [container].
 */
internal fun DataInput.readContainerFooterTracePoint(
    context: TraceContext,
    container: ContainerHeaderTracePoint,
): ContainerFooterTracePoint {
    val tracePoint = readTracePointData(context)
    check(tracePoint is ContainerFooterTracePoint) {
        "Expected a closing tracepoint, got ${tracePoint::class.java.simpleName}, broken file"
    }
    container.attachFooterTracePoint(tracePoint)
    return tracePoint
}

// ======== Per-subclass body (de)serialization helpers ========

// These functions handle ONLY the subclass-specific body bytes.
// The kind discriminator, the common header (codeLocationId, threadId, eventId, diffStatus),
// the children-diff-statuses set (for container tracepoints), and the container event id
// (for the closing side of container tracepoints)
// are emitted/consumed inline by `writeTracePointData`/ `readTracePointData` above.
//
// All per-subclass body helpers are `private` to this file:
// tracepoint bytes can only be produced/consumed through `writeTracePointData`/`readTracePointData`,
// which guarantees that the common header is in place.

// -------- Field --------
//
// Shared writer for both `ReadFieldTracePoint` and `WriteFieldTracePoint` (their bodies
// are identical; the kind byte in the header tells them apart on the read side).

private fun DataOutput.writeFieldTracePoint(value: FieldTracePoint) {
    writeInt(value.fieldId)
    writeTraceValue(value.obj)
    writeTraceValue(value.value)
}

private fun DataInput.readFieldTracePoint(
    context: TraceContext,
    kind: TracePointKind,
    codeLocationId: Int,
    threadId: Int,
    eventId: Int,
): FieldTracePoint {
    val fieldId = readInt()
    val obj = readTraceValue(context)
    val value = readTraceValue(context)
    return when (kind) {
        TracePointKind.READ_FIELD  ->
            ReadFieldTracePoint(context, threadId, codeLocationId, fieldId, obj, value, eventId)
        TracePointKind.WRITE_FIELD ->
            WriteFieldTracePoint(context, threadId, codeLocationId, fieldId, obj, value, eventId)
        else ->
            error("Unexpected kind for field tracepoint: $kind")
    }
}

// -------- Array --------

private fun DataOutput.writeArrayTracePoint(value: ArrayTracePoint) {
    writeTraceValue(value.array)
    writeInt(value.index)
    writeTraceValue(value.value)
}

private fun DataInput.readArrayTracePoint(
    context: TraceContext,
    kind: TracePointKind,
    codeLocationId: Int,
    threadId: Int,
    eventId: Int,
): ArrayTracePoint {
    val array = readTraceValue(context) ?: TraceNull
    val index = readInt()
    val value = readTraceValue(context)
    return when (kind) {
        TracePointKind.READ_ARRAY  ->
            ReadArrayTracePoint(context, threadId, codeLocationId, array, index, value, eventId)
        TracePointKind.WRITE_ARRAY ->
            WriteArrayTracePoint(context, threadId, codeLocationId, array, index, value, eventId)
        else -> error("Unexpected kind for array tracepoint: $kind")
    }
}

// -------- Local Variable --------

private fun DataOutput.writeLocalVariableTracePoint(value: LocalVariableTracePoint) {
    writeInt(value.localVariableId)
    writeTraceValue(value.value)
}

private fun DataInput.readLocalVariableTracePoint(
    context: TraceContext,
    kind: TracePointKind,
    codeLocationId: Int,
    threadId: Int,
    eventId: Int,
): LocalVariableTracePoint {
    val localVariableId = readInt()
    val value = readTraceValue(context)
    return when (kind) {
        TracePointKind.READ_LOCAL_VARIABLE  ->
            ReadLocalVariableTracePoint(context, threadId, codeLocationId, localVariableId, value, eventId)
        TracePointKind.WRITE_LOCAL_VARIABLE ->
            WriteLocalVariableTracePoint(context, threadId, codeLocationId, localVariableId, value, eventId)
        else -> error("Unexpected kind for local-variable tracepoint: $kind")
    }
}

// -------- Method Call --------

private fun DataOutput.writeMethodCallTracePoint(value: MethodCallTracePoint) {
    writeInt(value.methodId)
    writeTraceValue(value.obj)
    writeInt(value.parameters.size)
    value.parameters.forEach { writeTraceValue(it) }
    writeShort(value.flags.toInt())
}

private fun DataInput.readMethodCallTracePoint(
    context: TraceContext,
    codeLocationId: Int,
    threadId: Int,
    eventId: Int,
): MethodCallTracePoint {
    val methodId = readInt()
    val obj = readTraceValue(context)
    val parametersCount = readInt()
    val parameters = List(parametersCount) { readTraceValue(context) }
    val flags = readShort()
    return MethodCallTracePoint(
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

private fun DataOutput.writeMethodCallResultTracePoint(value: MethodCallResultTracePoint) {
    writeTraceValue(value.result)
    writeNullableString(value.exceptionClassName)
}

private fun DataInput.readMethodCallResultTracePoint(
    context: TraceContext,
    codeLocationId: Int,
    threadId: Int,
    eventId: Int,
    methodCallEventId: Int,
): MethodCallResultTracePoint {
    val result = readTraceValue(context)
    val exceptionClassName = readNullableString()
    return MethodCallResultTracePoint(
        context = context,
        threadId = threadId,
        codeLocationId = codeLocationId,
        methodCallEventId = methodCallEventId,
        result = result,
        exceptionClassName = exceptionClassName,
        eventId = eventId,
    )
}

// -------- Loop --------

private fun DataOutput.writeLoopTracePoint(value: LoopTracePoint) {
    writeInt(value.loopId)
}

private fun DataInput.readLoopTracePoint(
    context: TraceContext,
    codeLocationId: Int,
    threadId: Int,
    eventId: Int,
): LoopTracePoint {
    val loopId = readInt()
    return LoopTracePoint(
        context = context,
        threadId = threadId,
        codeLocationId = codeLocationId,
        loopId = loopId,
        eventId = eventId,
    )
}

private fun DataOutput.writeLoopEndTracePoint(value: LoopEndTracePoint) {
    writeInt(value.iterations)
}

private fun DataInput.readLoopEndTracePoint(
    context: TraceContext,
    codeLocationId: Int,
    threadId: Int,
    eventId: Int,
    loopEventId: Int,
): LoopEndTracePoint {
    val iterations = readInt()
    return LoopEndTracePoint(
        context = context,
        threadId = threadId,
        codeLocationId = codeLocationId,
        loopEventId = loopEventId,
        iterations = iterations,
        eventId = eventId,
    )
}

// -------- Loop Iteration --------

private fun DataOutput.writeLoopIterationTracePoint(value: LoopIterationTracePoint) {
    writeInt(value.loopId)
    writeInt(value.loopIteration)
}

private fun DataInput.readLoopIterationTracePoint(
    context: TraceContext,
    codeLocationId: Int,
    threadId: Int,
    eventId: Int,
): LoopIterationTracePoint {
    val loopId = readInt()
    val loopIteration = readInt()
    return LoopIterationTracePoint(
        context = context,
        threadId = threadId,
        codeLocationId = codeLocationId,
        loopId = loopId,
        loopIteration = loopIteration,
        eventId = eventId,
    )
}

// -------- Exception Processing --------

private fun DataOutput.writeExceptionProcessingTracePoint(value: ExceptionProcessingTracePoint) {
    writeTraceValue(value.exception)
}

private fun DataInput.readExceptionProcessingTracePoint(
    context: TraceContext,
    kind: TracePointKind,
    codeLocationId: Int,
    threadId: Int,
    eventId: Int,
): ExceptionProcessingTracePoint {
    val exception = readTraceValue(context) ?: TraceNull
    return when (kind) {
        TracePointKind.THROW -> ThrowTracePoint(context, threadId, codeLocationId, exception, eventId)
        TracePointKind.CATCH -> CatchTracePoint(context, threadId, codeLocationId, exception, eventId)
        else -> error("Unexpected kind for exception-processing tracepoint: $kind")
    }
}

// -------- Snapshot Line Breakpoint --------

private fun DataOutput.writeSnapshotLineBreakpointTracePoint(value: SnapshotLineBreakpointTracePoint) {
    writeUUID(value.breakpointUuid)
    writeInt(value.stackTraceCodeLocationIds.size)
    value.stackTraceCodeLocationIds.forEach { writeInt(it) }
    writeLong(value.currentTimeMillis)
    writeInt(value.locals.size)
    value.locals.forEach { writeTraceValue(it) }
    writeInt(value.watches.size)
    value.watches.forEach { writeTraceValue(it) }
    writeNullableString(value.traceId)
}

private fun DataInput.readSnapshotLineBreakpointTracePoint(
    context: TraceContext,
    codeLocationId: Int,
    threadId: Int,
    eventId: Int,
): SnapshotLineBreakpointTracePoint {
    val breakpointUuid = readUUID()
    val size = readInt()
    val stackTraceCodeLocationIds = List(size) { readInt() }
    val currentTimeMillis = readLong()
    val localsSize = readInt()
    val locals = List(localsSize) { readTraceValue(context) }
    val watchValuesSize = readInt()
    val watchValues = List(watchValuesSize) { readTraceValue(context) }
    val traceId = readNullableString()
    return SnapshotLineBreakpointTracePoint(
        context = context,
        codeLocationId = codeLocationId,
        threadId = threadId,
        breakpointUuid = breakpointUuid,
        stackTraceCodeLocationIds = stackTraceCodeLocationIds,
        currentTimeMillis = currentTimeMillis,
        locals = locals,
        watches = watchValues,
        traceId = traceId,
        eventId = eventId,
    )
}
