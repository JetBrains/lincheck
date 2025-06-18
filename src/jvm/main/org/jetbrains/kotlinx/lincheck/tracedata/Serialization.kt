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
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

const val OUTPUT_BUFFER_SIZE: Int = 16*1024*1024
const val TRACE_MAGIC : Long = 0x706e547124ee5f70L
const val TRACE_VERSION : Long = 1

fun saveRecorderTrace(out: OutputStream, rootCallsPerThread: List<TRTracePoint>) {
    DataOutputStream(out.buffered(OUTPUT_BUFFER_SIZE)).use { output ->
        output.writeLong(TRACE_MAGIC)
        output.writeLong(TRACE_VERSION)

        saveCache(output, methodCache, DataOutput::writeMethodDescriptor)
        saveCache(output, fieldCache, DataOutput::writeFieldDescriptor)
        saveCache(output, variableCache, DataOutput::writeVariableDescriptor)

        output.writeInt(rootCallsPerThread.size)
        rootCallsPerThread.forEach { root ->
            root.save(output)
        }
    }
}

fun loadRecordedTrace(inp: InputStream): List<TRTracePoint> {
    DataInputStream(inp.buffered(OUTPUT_BUFFER_SIZE)).use { input ->
        val magic = input.readLong()
        if (magic != TRACE_MAGIC) {
            error("Wrong magic 0x${(magic.toString(16))}, expected ${TRACE_MAGIC.toString(16)}")
        }

        val version = input.readLong()
        if (version != TRACE_VERSION) {
            error("Wrong version $version (expected $TRACE_VERSION)")
        }

        loadCache(input, methodCache, DataInput::readMethodDescriptor)
        loadCache(input, fieldCache, DataInput::readFieldDescriptor)
        loadCache(input, variableCache, DataInput::readVariableDescriptor)

        val threadNum = input.readInt()
        val roots = mutableListOf<TRMethodCallTracePoint>()
        repeat(threadNum) {
            roots.add(loadTRTracePoint(input) as TRMethodCallTracePoint)
        }
        return roots
    }
}

private fun <V> saveCache(output: DataOutput, cache: IndexedPool<V>, writer: DataOutput.(V) -> Unit) {
    output.writeInt(cache.content.size)
    cache.content.forEach {
        output.writer(it)
    }
}


private fun <V> loadCache(input: DataInput, cache: IndexedPool<V>, reader: DataInput.() -> V) {
    val count = input.readInt()
    repeat(count) {
        cache.getOrCreateId(input.reader())
    }
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
    when (type.toInt()) {
        0 -> return Types.ArrayType(readType())
        1 -> return Types.BooleanType()
        2 -> return Types.ByteType()
        3 -> return Types.CharType()
        4 -> return Types.DoubleType()
        5 -> return Types.FloatType()
        6 -> return Types.IntType()
        7 -> return Types.LongType()
        8 -> return Types.ObjectType(readUTF())
        9 -> return Types.ShortType()
        10 -> return Types.VoidType()
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

private fun DataOutput.writeMethodDescriptor(value: MethodDescriptor) {
   writeUTF(value.className)
   writeMethodSignature(value.methodSignature)
}

private fun DataInput.readMethodDescriptor(): MethodDescriptor {
    return MethodDescriptor(readUTF(), readMethodSignature())
}

private fun DataOutput.writeMethodSignature(value: MethodSignature) {
    writeUTF(value.name)
    writeMethodType(value.methodType)
}

private fun DataInput.readMethodSignature(): MethodSignature {
    return MethodSignature(readUTF(), readMethodType())
}

private fun DataOutput.writeFieldDescriptor(value: FieldDescriptor) {
    writeUTF(value.className)
    writeUTF(value.fieldName)
    writeBoolean(value.isStatic)
    writeBoolean(value.isFinal)
}

private fun DataInput.readFieldDescriptor(): FieldDescriptor {
    return FieldDescriptor(
        className = readUTF(),
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
