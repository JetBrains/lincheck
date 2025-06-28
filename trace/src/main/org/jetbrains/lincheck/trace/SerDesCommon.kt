/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.tracedata

import java.io.DataInput
import java.io.DataOutput
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InvalidObjectException
import java.io.OutputStream

/**
 * This file contains data structures and extensions for DataInput and DataOutput to save "primitive" trace data.
 *
 * This code is not split into serialization and deserialization parts to keep pairs of functions
 * together, so it is easy to verify that they are true pairs and use the same formats.
 *
 * These functions are for low-level object representations, without kinds and ids.
 */

internal const val TRACE_MAGIC : Long = 0x706e547124ee5f70L
internal const val INDEX_MAGIC : Long = TRACE_MAGIC.inv()
internal const val TRACE_VERSION : Long = 7

internal const val INDEX_FILENAME_SUFFIX = ".idx"

internal enum class ObjectKind {
    CLASS_DESCRIPTOR,
    METHOD_DESCRIPTOR,
    FIELD_DESCRIPTOR,
    VARIABLE_DESCRIPTOR,
    STRING,
    CODE_LOCATION,
    TRACEPOINT,
    TRACEPOINT_FOOTER,
    EOF
}

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
            writeUTF(value.className)
        }
        is Types.ShortType ->writeByte(9)
        is Types.VoidType -> writeByte(10)
    }
}

internal fun DataInput.readType(): Types.Type {
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

internal fun DataOutput.writeClassDescriptor(value: ClassDescriptor) {
    writeUTF(value.name)
}

internal fun DataInput.readClassDescriptor(): ClassDescriptor {
    return ClassDescriptor(readUTF())
}

internal fun DataOutput.writeMethodDescriptor(value: MethodDescriptor) {
    writeInt(value.classId)
    writeMethodSignature(value.methodSignature)
}

internal fun DataInput.readMethodDescriptor(context: TraceContext): MethodDescriptor {
    return MethodDescriptor(context,readInt(), readMethodSignature())
}

internal fun DataOutput.writeMethodSignature(value: MethodSignature) {
    writeUTF(value.name)
    writeMethodType(value.methodType)
}

internal fun DataInput.readMethodSignature(): MethodSignature {
    return MethodSignature(readUTF(), readMethodType())
}

internal fun DataOutput.writeFieldDescriptor(value: FieldDescriptor) {
    writeInt(value.classId)
    writeUTF(value.fieldName)
    writeBoolean(value.isStatic)
    writeBoolean(value.isFinal)
}

internal fun DataInput.readFieldDescriptor(context: TraceContext): FieldDescriptor {
    return FieldDescriptor(
        context = context,
        classId = readInt(),
        fieldName = readUTF(),
        isStatic = readBoolean(),
        isFinal = readBoolean()
    )
}

internal fun DataOutput.writeVariableDescriptor(value: VariableDescriptor) {
    writeUTF(value.name)
}

internal fun DataInput.readVariableDescriptor(): VariableDescriptor {
    return VariableDescriptor(readUTF())
}


internal fun DataOutput.writeKind(value: ObjectKind) = writeByte(value.ordinal)

internal fun DataInput.readKind(): ObjectKind {
    val ordinal = readByte()
    val values = ObjectKind.entries
    if (ordinal < 0 || ordinal > values.size) {
        throw IOException("Cannot read ObjectKind: unknown ordinal $ordinal")
    }
    return values[ordinal.toInt()]
}

internal fun openNewFile(name: String): OutputStream {
    val f = File(name)
    f.parentFile?.mkdirs()
    f.createNewFile()
    return f.outputStream()
}

internal fun openExistingFile(name: String?): InputStream? {
    val f = File(name)
    if (!f.exists()) return null
    return f.inputStream()
}
